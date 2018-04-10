# Can be set from the command line. E.g.:
#   make ecosystem-release VERSION=2.0.0
export VERSION=snapshot
export TEST_SET_SIZE=medium

find = git ls-files --others --exclude-standard --cached
pip = .build/pip3-packages-installed

.SUFFIXES: # Disable built-in rules.
.PHONY: all
all: backend

# Remove all ignored files. The ecosystem folder is ignored because of files created by
# docker containers are owned by root and cannot be deleted by others.
# Deleting the .idea folder messes with IntelliJ, so exclude that too.
.PHONY: clean
clean:
	git clean -f -X -d --exclude="!.idea/" --exclude="!ecosystem/**"

.build/gulp-done: $(shell $(find) web/app) web/gulpfile.js web/package.json
	cd web && LC_ALL=C yarn --frozen-lockfile && gulp && cd - && touch $@
.build/documentation-verified: $(shell $(find) app) .build/gulp-done
	./tools/check_documentation.sh && touch $@
$(pip): python_requirements.txt
	pip3 install --user -r python_requirements.txt && touch $@
.build/backend-done: \
	$(shell $(find) app project lib conf built-ins) tools/call_spark_submit.sh build.sbt .build/gulp-done
	./tools/install_spark.sh && sbt stage < /dev/null && touch $@
.build/backend-test-passed: $(shell $(find) app test project conf) build.sbt
	./tools/install_spark.sh && ./.test_backend.sh && touch $@
.build/frontend-test-passed: \
		$(shell $(find) web/test) build.sbt .build/backend-done \
		.build/documentation-verified .build/gulp-done
	./.test_frontend.sh && touch $@
.build/remote_api-python-test-passed: $(shell $(find) remote_api/python) .build/backend-done $(pip)
	tools/with_lk.sh remote_api/python/test.sh && touch $@
.build/mobile-prepaid-scv-test-passed: $(shell $(find) mobile-prepaid-scv) .build/backend-done $(pip)
	tools/with_lk.sh mobile-prepaid-scv/unit_test.sh && touch $@
.build/documentation-done-${VERSION}: $(shell $(find) ecosystem/documentation remote_api/python) python_requirements.txt
	ecosystem/documentation/build.sh native && touch $@
.build/ecosystem-done: \
		$(shell $(find) ecosystem/native remote_api ) \
		.build/backend-done .build/documentation-done-${VERSION} \
		ecosystem/docker/run_in_docker.sh
	ecosystem/native/tools/build-monitoring.sh && \
	ecosystem/native/bundle.sh && touch $@
.build/ecosystem-docker-base-done: \
		.build/ecosystem-done $(shell $(find) ecosystem/docker/base)
	ecosystem/docker/base/build.sh && touch $@
.build/ecosystem-docker-release-done: \
		.build/ecosystem-docker-base-done $(shell $(find) ecosystem/docker/release)
	ecosystem/docker/release/build.sh && touch $@

# Short aliases for command-line use.
.PHONY: backend
backend: .build/backend-done
.PHONY: frontend
frontend: .build/gulp-done
.PHONY: ecosystem
ecosystem: .build/ecosystem-done
.PHONY: ecosystem-docker-base
ecosystem-docker-base: .build/ecosystem-docker-base-done
.PHONY: ecosystem-docker-release
ecosystem-docker-release: .build/ecosystem-docker-release-done
.PHONY: backend-test
backend-test: .build/backend-test-passed
.PHONY: frontend-test
frontend-test: .build/frontend-test-passed
.PHONY: remote_api-test
remote_api-test: .build/remote_api-python-test-passed
.PHONY: mobile-prepaid-scv-test
mobile-prepaid-scv-test: .build/mobile-prepaid-scv-test-passed
.PHONY: ecosystem-test
ecosystem-test: remote_api-test mobile-prepaid-scv-test
.PHONY: test
test: backend-test frontend-test ecosystem-test
.PHONY: big-data-test
big-data-test: .build/ecosystem-done
	./test_big_data.py --test_set_size ${TEST_SET_SIZE} --rm
