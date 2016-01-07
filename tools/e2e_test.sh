#!/bin/bash -ue

DIR=$(dirname $0)
cd $DIR/..
if [ ! -f "stage.sh" ]; then
  echo "You must run this script from the source tree, not from inside a stage!"
  exit 1
fi

# Compile.
./stage.sh

# Create config.
TMP=$(mktemp -d)
PORT=$[ 9100 + RANDOM % 100 ]
PID_FILE=${TMP}/pid
cat > "$TMP/overrides"  <<EOF
export KITE_META_DIR="$TMP/meta"
export KITE_DATA_DIR="file:$TMP/data"
export KITE_HTTP_PORT=$PORT
export KITE_PID_FILE=$PID_FILE
EOF

# Start backend.
KITE_SITE_CONFIG="conf/kiterc_template" \
KITE_SITE_CONFIG_OVERRIDES="$TMP/overrides" stage/bin/biggraph start
KITE_PID=`cat ${PID_FILE}`
function kill_backend {
  echo "Shutting down server on port $KITE_PID."
  kill $KITE_PID
  rm -rf "$TMP"
}
trap kill_backend EXIT
echo "Kite running on port: $PORT"

cd web
# Make sure the webdriver is installed.
node node_modules/protractor/bin/webdriver-manager update
# Run test against backend.
grunt test --port=$PORT "$@"
