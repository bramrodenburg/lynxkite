#!/bin/bash -xue
# Downloads and installs Spark. Useful for lazy people and Jenkins alike.

cd $(dirname $0)/..
VERSION=$(cat conf/SPARK_VERSION)

# Link to the given name or spark-$VERSION by default.
LINK="${1:-spark-${VERSION}}"

LOCK=/tmp/install_spark_${LINK}
exec 200>$LOCK
flock 200

HADOOP='2.7'
cd $HOME
if [[ ! -x "$LINK" ]]; then
  wget --quiet "http://d3kbcqa49mib13.cloudfront.net/spark-${VERSION}-bin-hadoop${HADOOP}.tgz"
  tar xf "spark-${VERSION}-bin-hadoop${HADOOP}.tgz"
  rm "spark-${VERSION}-bin-hadoop${HADOOP}.tgz"
  ln -s "$HOME/spark-${VERSION}-bin-hadoop${HADOOP}" "$LINK"
fi
