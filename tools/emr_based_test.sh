#!/bin/bash

# Brings up a small EMR cluster and runs tests on it.
# Usage:
#
# emr_based_test.sh frontend  #  Run e2e frontend tests.
#
# emr_based_test.sh perf file1.groovy file2.groovy ... [-- key1:value1, key2:value2... ]
#   Run performance tests on the groovy files, and pass the key:value parameters to them.
#
#   Example:
#   emr_based_test.sh perf kitescripts/perf/*.groovy -- seed:1234
#   This will run all groovy files in kitescripts/perf/ and all these
#   groovy files will receive the seed parameter as 1234.


set -ueo pipefail
trap "echo $0 has failed" ERR

cd "$(dirname $0)/.."

MODE=${1}
shift

CLUSTER_NAME="${USER}-test-cluster"
EMR_TEST_SPEC="/tmp/${CLUSTER_NAME}.emr_test_spec"

./stage.sh

cp stage/tools/emr_spec_template ${EMR_TEST_SPEC}
cat >>${EMR_TEST_SPEC} <<EOF

# Override values for the test setup:
CLUSTER_NAME=${CLUSTER_NAME}
NUM_INSTANCES=3
S3_DATAREPO=""
KITE_INSTANCE_BASE_NAME=testemr
EOF

CLUSTERID=$(stage/tools/emr.sh clusterid ${EMR_TEST_SPEC})
if [ -n "$CLUSTERID" ]; then
  echo "Reusing already running cluster instance ${CLUSTERID}."
  stage/tools/emr.sh reset-yes ${EMR_TEST_SPEC}
else
  echo "Starting new cluster instance."
  stage/tools/emr.sh start ${EMR_TEST_SPEC}
fi

stage/tools/emr.sh kite ${EMR_TEST_SPEC}

case $MODE in
  perf )
    stage/tools/emr.sh batch ${EMR_TEST_SPEC} $@
    stage/tools/emr.sh uploadLogs ${EMR_TEST_SPEC}
    ;;
  frontend )
    stage/tools/emr.sh connect ${EMR_TEST_SPEC} &
    CONNECTION_PID=$!
    sleep 15

    pushd web
    PORT=4044 gulp test || echo "Frontend tests failed."
    popd

    pkill -TERM -P ${CONNECTION_PID}
    ;;
  * )
    echo "Invalid mode was specified: ${MODE}"
    echo "Usage: $0 perf|frontend"
    exit 1
esac

read -p "Test completed. Terminate cluster? [y/N] " answer
case ${answer:0:1} in
  y|Y )
    stage/tools/emr.sh terminate-yes ${EMR_TEST_SPEC}
    ;;
  * )
    echo "Use 'stage/tools/emr.sh ssh ${EMR_TEST_SPEC}' to log in to master."
    echo "Please don't forget to shut down the cluster."
    ;;
esac

