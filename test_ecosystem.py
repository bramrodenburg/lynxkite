#!/usr/bin/env python3
"""
Command-line utility to spin up an EMR cluster
(optionally with an RDS database), and run
performance tests on it.

Examples:

Running the default big data tests on the small data set using
the 1.9.6 native release.

    ./test_ecosystem.py --test --bigdata

Running all big data tests on the normal data set using the current branch.

    ./test_ecosystem.py --test --bigdata --bigdata_test_set normal \
                        --task AllTests --lynx_release_dir ecosystem/native/dist

Running JDBC tests on a cluster named `JDBC-test-cluster` using version 1.9.5.

    ./test_ecosystem.py --cluster_name JDBC-test-cluster --test \
                        --with_rds --lynx_version native-1.9.5 \
                        --task_module test_tasks.jdbc --task JDBCTestAll

Running ModularClustering test on the large data set using the current branch
and downloading application logs from the cluster to `/home/user/cluster-logs`.

    ./test_ecosystem.py --test --bigdata --bigdata_test_set large \
                        --task ModularClustering \
                        --lynx_release_dir ecosystem/native/dist \
                        --log_dir /home/user/cluster-logs

Running the default big data tests on the normal data set using a cluster
with 6 nodes (1 master, 5 worker) and the 1.9.6 native release.

    ./test_ecosystem.py --test --bigdata --bigdata_test_set normal \
                        --emr_instance_count 6
"""
import argparse
import os
import sys
# Set up import path for our modules.
os.chdir(os.path.dirname(__file__))
sys.path.append('remote_api/python')
from utils.emr_lib import EMRLib


#  Big data test sets in the  `s3://lynxkite-test-data/` bucket.
#  fake_westeros_v3_100k_2m     100k vertices, 2m edges (small)
#  fake_westeros_v3_5m_145m     5m vertices, 145m edges (normal)
#  fake_westeros_v3_10m_303m    10m vertices, 303m edges (large)
#  fake_westeros_v3_25m_799m    25m vertices 799m edges (xlarge)

test_sets = {
    'small': dict(data='fake_westeros_v3_100k_2m', instances=3),
    'normal': dict(data='fake_westeros_v3_5m_145m', instances=4),
    'large': dict(data='fake_westeros_v3_10m_303m', instances=8),
    'xlarge': dict(data='fake_westeros_v3_25m_799m', instances=20),
}


parser = argparse.ArgumentParser()
parser.add_argument(
    '--cluster_name',
    default=os.environ['USER'] + '-ecosystem-test',
    help='Name of the cluster to start')
parser.add_argument(
    '--biggraph_releases_dir',
    default=os.environ['HOME'] + '/biggraph_releases',
    help='''Directory containing the downloader script, typically the root of
         the biggraph_releases repo. The downloader script will have the form of
         BIGGRAPH_RELEASES_DIR/download-lynx-LYNX_VERSION.sh''')
parser.add_argument(
    '--lynx_version',
    default='native-1.9.6',
    help='''Version of the ecosystem release to test. A downloader script of the
          following form will be used for obtaining the release:
         BIGGRAPH_RELEASES_DIR/download-lynx-LYNX_VERSION.sh''')
parser.add_argument(
    '--lynx_release_dir',
    default='',
    help='''If non-empty, then this local directory is directly uploaded instead of
         using LYNX_VERSION and BIGGRAPH_RELEASES_DIR. The directory of the current
         native code is ecosystem/native/dist.''')
parser.add_argument(
    '--task_module',
#TODO airflow
    default='test_tasks.bigdata_tests',
    help='Module of the task which will run on the cluster.')
parser.add_argument(
#TODO airflow
    '--task',
    default='DefaultTests',
    help='Task to run when the cluster is started.')
parser.add_argument(
    '--ec2_key_file',
    default=os.environ['HOME'] + '/.ssh/lynx-cli.pem')
parser.add_argument(
    '--ec2_key_name',
    default='lynx-cli')
parser.add_argument(
    '--emr_instance_count',
    type=int,
    default=0,
    help='Number of instances on EMR cluster, including master.' +
    ' Set according to bigdata_test_set by default.')
parser.add_argument(
    '--emr_region',
    default='us-east-1',
    help='Region of the EMR cluster.' +
    ' Possible values: us-east-1, ap-southeast-1, eu-central-1, ...')
parser.add_argument(
    '--results_dir',
    default='./ecosystem/tests/results/',
    help='Test results are downloaded to this directory.')
parser.add_argument(
    '--log_dir',
    default='',
    help='''Cluster log files are downloaded to this directory.
    If it is an empty string, no log file is downloaded.''')
parser.add_argument(
    '--rm',
    action='store_true',
    help='''Delete the cluster after completion.''')
parser.add_argument(
    '--test',
    action='store_true',
    help='''If this switch is used, it means the EMR cluster was started to
    run tests. In that case `task` will be started by `test_runner` and after
    completion, the results will be downloaded to the local machine.''')
parser.add_argument(
    '--monitor_nodes',
    action='store_true',
    help='Setup and start monitoring on the extra nodes. The default is false.')
parser.add_argument(
    '--bigdata',
    action='store_true',
    help='The given task is a big data test task. A bigdata_test_set parameter also have to be given.')
parser.add_argument(
    '--bigdata_test_set',
    default='small',
    help='Test set for big data tests. Possible values: small, normal, large, xlarge.')
parser.add_argument(
    '--emr_log_uri',
    default='s3://test-ecosystem-log',
    help='URI of the S3 bucket where the EMR logs will be written.')
parser.add_argument(
    '--with_rds',
    action='store_true',
    help='Spin up a mysql RDS instance to test database operations.')
parser.add_argument(
    '--s3_data_dir',
    help='S3 path to be used as non-ephemeral data directory.')


def main(args):
  # Checking argument dependencies.
  check_arguments(args)
  # Create an EMR cluster.
  lib = EMRLib(
      ec2_key_file=args.ec2_key_file,
      ec2_key_name=args.ec2_key_name,
      region=args.emr_region)
  if args.emr_instance_count == 0:
    if args.bigdata:
      args.emr_instance_count = bigdata_test_set(args)['instances']
    else:
      args.emr_instance_count = 3
  cluster = lib.create_or_connect_to_emr_cluster(
      name=args.cluster_name,
      log_uri=args.emr_log_uri,
      instance_count=args.emr_instance_count,
      hdfs_replication='1')
  instances = [cluster]
  # Spin up a mysql RDS instance only if requested.
  jdbc_url = ''
  if args.with_rds:
    mysql_instance = lib.create_or_connect_to_rds_instance(
        name=args.cluster_name + '-mysql')
    # Wait for startup of both.
    instances = instances + [mysql_instance]
    lib.wait_for_services(instances)
    mysql_address = mysql_instance.get_address()
    jdbc_url = 'jdbc:mysql://{mysql_address}/db?user=root&password=rootroot'.format(
        mysql_address=mysql_address)
  else:
    lib.wait_for_services(instances)
  # Install and configure ecosystem on cluster.
  upload_installer_script(cluster, args)
  upload_tasks(cluster)
  upload_tools(cluster)
  download_and_unpack_release(cluster, args)
  install_native(cluster)
  config_and_prepare_native(cluster, args)
  config_aws_s3_native(cluster)
  if args.monitor_nodes:
    start_monitoring_on_extra_nodes_native(args.ec2_key_file, cluster)
  start_supervisor_native(cluster)
  # Run tests and download results.
  if args.test:
    run_tests_native(cluster, jdbc_url, args)
  if args.log_dir:
    download_logs_native(cluster, args)
  if args.test:
    cluster.turn_termination_protection_off()
    shut_down_instances(instances)


def results_local_dir(args):
  '''
  In case of big data tests, the name of the result dir includes the number of instances,
  the number of executors and the name of the test data set.
  '''
  if args.bigdata:
    basedir = args.results_dir
    dataset = bigdata_test_set(args)['data']
    instance_count = args.emr_instance_count
    executors = instance_count - 1
    return "{bd}emr_{e}_{i}_{ds}".format(
        bd=basedir,
        e=executors,
        i=instance_count,
        ds=dataset
    )
  else:
    return args.results_dir


def results_name(args):
  return "/{task}-result.txt".format(
      task=args.task
  )


def check_bigdata(args):
  '''Possible values of `--bigdata_test_set`.'''
  if args.bigdata:
    if args.bigdata_test_set not in test_sets.keys():
      raise ValueError('bigdata_test_set = '
                       + args.bigdata_test_set
                       + ', possible values are: '
                       + ", ".join(test_sets.keys()))


def check_arguments(args):
  check_bigdata(args)


def bigdata_test_set(args):
  return test_sets[args.bigdata_test_set]


def upload_installer_script(cluster, args):
  if not args.lynx_release_dir:
    cluster.rsync_up(
        src='{dir}/download-lynx-{version}.sh'.format(
            dir=args.biggraph_releases_dir,
            version=args.lynx_version),
        dst='/mnt/')


def upload_tasks(cluster):
  # TODO airflow
  pass


def upload_tools(cluster):
  target_dir = '/mnt/lynx/tools'
  cluster.ssh('mkdir -p ' + target_dir)
  cluster.rsync_up('ecosystem/native/tools/', target_dir)
  cluster.rsync_up('tools/performance_collection/', target_dir)


def download_and_unpack_release(cluster, args):
  path = args.lynx_release_dir
  if path:
    cluster.rsync_up(path + '/', '/mnt/lynx')
  else:
    version = args.lynx_version
    cluster.ssh('''
      set -x
      cd /mnt
      if [ ! -f "./lynx-{version}.tgz" ]; then
        ./download-lynx-{version}.sh
        mkdir -p lynx
        tar xfz lynx-{version}.tgz -C lynx --strip-components 1
      fi
      '''.format(version=version))


def install_native(cluster):
  cluster.rsync_up('python_requirements.txt', '/mnt/lynx')
  cluster.ssh('''
    set -x
    cd /mnt/lynx
    sudo yum install -y python34-pip mysql-server gcc libffi-devel
    # Removes the given and following lines so only the necessary modules will be installed.
    sed -i -n '/# Dependencies for developing and testing/q;p'  python_requirements.txt
    sudo pip-3.4 install --upgrade -r python_requirements.txt
    sudo pip-2.6 install --upgrade requests[security] supervisor
    # mysql setup
    sudo service mysqld start
    mysqladmin  -u root password 'root' || true  # (May be set already.)
    # This mysql database is used for many things, including the testing of JDBC tasks.
    # For that purpose access needs to be granted for all executors.
    mysql -uroot -proot -e "GRANT ALL PRIVILEGES ON *.* TO 'root'@'%' IDENTIFIED BY 'root'"
  ''')


def config_and_prepare_native(cluster, args):
  hdfs_path = 'hdfs://$HOSTNAME:8020/user/$USER/lynxkite/'
  if args.s3_data_dir:
    data_dir_config = '''
      export KITE_DATA_DIR={}
      export KITE_EPHEMERAL_DATA_DIR={}
    '''.format(args.s3_data_dir, hdfs_path)
  else:
    data_dir_config = '''
      export KITE_DATA_DIR={}
    '''.format(hdfs_path)
  cluster.ssh('''
    cd /mnt/lynx
    echo 'Setting up environment variables.'
    # Removes the given and following lines so config/central does not grow constantly.
    sed -i -n '/# ---- the below lines were added by test_ecosystem.py ----/q;p'  config/central
    cat >>config/central <<'EOF'
# ---- the below lines were added by test_ecosystem.py ----
      export KITE_INSTANCE=ecosystem-test
      export KITE_MASTER_MEMORY_MB=8000
      export NUM_EXECUTORS={num_executors}
      export EXECUTOR_MEMORY=18g
      export NUM_CORES_PER_EXECUTOR=8
      # port differs from the one used in central/config
      export HDFS_ROOT=hdfs://$HOSTNAME:8020/user/$USER
      {data_dir_config}
      export LYNXKITE_ADDRESS=https://localhost:$KITE_HTTPS_PORT/
      export PYTHONPATH=/mnt/lynx/apps/remote_api/python/
      export HADOOP_CONF_DIR=/etc/hadoop/conf
      export YARN_CONF_DIR=$HADOOP_CONF_DIR
      export LYNX=/mnt/lynx
      #for tests with mysql server on master
      export DATA_DB=jdbc:mysql://$HOSTNAME:3306/'db?user=root&password=root&rewriteBatchedStatements=true'
      export KITE_INTERNAL_WATCHDOG_TIMEOUT_SECONDS=7200
EOF
    echo 'Creating hdfs directory.'
    source config/central
    hdfs dfs -mkdir -p $KITE_DATA_DIR/table_files
    echo 'Creating tasks_data directory.'
    # TODO: Find a more sane directory.
    sudo mkdir -p /tasks_data
    sudo chmod a+rwx /tasks_data
  '''.format(num_executors=args.emr_instance_count - 1, data_dir_config=data_dir_config))


def config_aws_s3_native(cluster):
  cluster.ssh('''
    cd /mnt/lynx
    echo 'Setting s3 prefix.'
    sed -i -n '/# ---- the below lines were added by test_ecosystem.py ----/q;p'  config/prefix_definitions.txt
    cat >>config/prefix_definitions.txt <<'EOF'
# ---- the below lines were added by test_ecosystem.py ----
S3="s3://"
EOF
    echo 'Setting AWS CLASSPATH.'
    if [ -f spark/conf/spark-env.sh ]; then
      sed -i -n '/# ---- the below lines were added by test_ecosystem.py ----/q;p'  spark/conf/spark-env.sh
    fi
    cat >>spark/conf/spark-env.sh <<'EOF'
# ---- the below lines were added by test_ecosystem.py ----
AWS_CLASSPATH1=$(find /usr/share/aws/emr/emrfs/lib -name "*.jar" | tr '\\n' ':')
AWS_CLASSPATH2=$(find /usr/share/aws/aws-java-sdk -name "*.jar" | tr '\\n' ':')
AWS_CLASSPATH3=$(find /usr/share/aws/emr/instance-controller/lib -name "*.jar" | tr '\\n' ':')
AWS_CLASSPATH_ALL=$AWS_CLASSPATH1$AWS_CLASSPATH2$AWS_CLASSPATH3
export SPARK_DIST_CLASSPATH=$SPARK_DIST_CLASSPATH:${AWS_CLASSPATH_ALL::-1}
EOF
    chmod a+x spark/conf/spark-env.sh
  ''')


def start_supervisor_native(cluster):
  cluster.ssh_nohup('''
    set -x
    source /mnt/lynx/config/central
    /usr/local/bin/supervisord -c config/supervisord.conf
    ''')


def start_monitoring_on_extra_nodes_native(keyfile, cluster):
  cluster_keyfile = 'cluster_key.pem'
  cluster.rsync_up(src=keyfile, dst='/home/hadoop/.ssh/' + cluster_keyfile)
  ssh_options = '''-o UserKnownHostsFile=/dev/null \
    -o CheckHostIP=no \
    -o StrictHostKeyChecking=no \
    -i /home/hadoop/.ssh/{keyfile}'''.format(keyfile=cluster_keyfile)

  cluster.ssh('''
    yarn node -list -all | grep RUNNING | cut -d':' -f 1 > nodes.txt
    ''')

  cluster.ssh('''
    for node in `cat nodes.txt`; do
      scp {options} \
        /mnt/lynx/other_nodes/other_nodes.tgz \
        hadoop@${{node}}:/home/hadoop/other_nodes.tgz
      ssh {options} hadoop@${{node}} tar xf other_nodes.tgz
      ssh {options} hadoop@${{node}} "sh -c 'nohup ./run.sh >run.stdout 2> run.stderr &'"
    done'''.format(options=ssh_options))

# Uncomment services in configs
  cluster.ssh('''
    /mnt/lynx/tools/uncomment_config.sh /mnt/lynx/config/monitoring/prometheus.yml
    /mnt/lynx/tools/uncomment_config.sh /mnt/lynx/config/supervisord.conf
    ''')

  cluster.ssh('''
    /mnt/lynx/scripts/service_explorer.sh
  ''')


def start_tests_native(cluster, jdbc_url, args):
  '''Start running the tests in the background.'''
  cluster.ssh_nohup('''
      source /mnt/lynx/config/central
      echo 'cleaning up previous test data'
      cd /mnt/lynx
      hadoop fs -rm -r /user/hadoop/lynxkite
      sudo rm -Rf metadata/lynxkite/*
      supervisorctl restart lynxkite
  ''')


def run_tests_native(cluster, jdbc_url, args):
  start_tests_native(cluster, jdbc_url, args)
  print('Tests are now running in the background. Waiting for results.')
  cluster.fetch_output()
  if not os.path.exists(results_local_dir(args)):
    os.makedirs(results_local_dir(args))
  cluster.rsync_down(
      '/home/hadoop/test_results.txt',
      results_local_dir(args) +
      results_name(args))
  upload_perf_logs_to_gcloud(cluster, args)


def upload_perf_logs_to_gcloud(cluster, args):
  print('Uploading performance logs to gcloud.')
  instance_name = 'emr-' + args.cluster_name
  cluster.ssh('''
    cd /mnt/lynx
    tools/multi_upload.sh 0 apps/lynxkite/logs {i}
  '''.format(i=instance_name))


def download_logs_native(cluster, args):
  if not os.path.exists(args.log_dir):
    os.makedirs(args.log_dir)
  cluster.rsync_down('/mnt/lynx/logs/', args.log_dir)
  cluster.rsync_down('/mnt/lynx/apps/lynxkite/logs/', args.log_dir + '/lynxkite-logs/')


def prompt_delete():
  if args.rm:
    return True
  print('Terminate instances? [y/N] ', end='')
  choice = input().lower()
  if choice == 'y':
    return True
  else:
    print('''Please don't forget to terminate the instances!''')
    return False


def shut_down_instances(instances):
  if prompt_delete():
    print('Shutting down instances.')
    for instance in instances:
      instance.terminate()


if __name__ == '__main__':
  args = parser.parse_args()
  main(args)
