#!/usr/bin/env bash
# Runs the core S3 tests of ceph/s3-tests against the executable jar of LocalS3; see README.md.
#
#   ceph-s3-tests/run.sh                          the tests; fails on a new failure or on a known one that passes
#   ceph-s3-tests/run.sh --update-known-failures  the tests, writing the failed ones to known-failures.txt
#   ceph-s3-tests/run.sh -k multipart -n 0        the arguments other than the above go to pytest
#
# LOCAL_S3_JAR overrides the jar, local-s3-standalone/build/libs/s3.jar, which `./gradlew :local-s3-standalone:jar`
# builds. Python 3.10+ is needed, and uv is used if it is installed.
set -euo pipefail

S3_TESTS_REPOSITORY=https://github.com/ceph/s3-tests.git
S3_TESTS_COMMIT=5522d1c351f75bc00ae0f64f742f3f095f5939d9
ACCESS_KEY=local-s3-tests
SECRET_KEY=local-s3-tests-secret

here="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$here")"
work="$here/build"
jar="${LOCAL_S3_JAR:-$root/local-s3-standalone/build/libs/s3.jar}"

pytest_args=()
for arg in "$@"; do
  if [[ "$arg" == "--update-known-failures" ]]; then
    export S3_TESTS_UPDATE_KNOWN_FAILURES=1
  else
    pytest_args+=("$arg")
  fi
done

if [[ ! -f "$jar" ]]; then
  echo "No LocalS3 jar at $jar; build it with ./gradlew :local-s3-standalone:jar" >&2
  exit 1
fi
mkdir -p "$work"

# The tests at the pinned commit.
if [[ "$(git -C "$work/s3-tests" rev-parse HEAD 2>/dev/null || true)" != "$S3_TESTS_COMMIT" ]]; then
  rm -rf "$work/s3-tests"
  git init -q "$work/s3-tests"
  git -C "$work/s3-tests" fetch -q --depth 1 "$S3_TESTS_REPOSITORY" "$S3_TESTS_COMMIT"
  git -C "$work/s3-tests" checkout -q FETCH_HEAD
fi

# A virtual environment, installed again when requirements.txt changes.
venv="$work/venv"
if ! cmp -s "$here/requirements.txt" "$venv/requirements.txt"; then
  rm -rf "$venv"
  if command -v uv >/dev/null; then
    uv venv -q -p 3.12 "$venv"
    uv pip install -q -p "$venv" -r "$here/requirements.txt"
  else
    python3 -m venv "$venv"
    "$venv/bin/pip" install -q -r "$here/requirements.txt"
  fi
  cp "$here/requirements.txt" "$venv/requirements.txt"
fi

# The shell of a developer often exports the AWS_* variables of a real account, and LOCAL_S3_* ones of another
# service: neither LocalS3 nor boto3 is to see them.
clean_env=(env)
while IFS='=' read -r name _; do
  [[ "$name" == AWS_* || "$name" == LOCAL_S3_* ]] && clean_env+=(-u "$name")
done < <(env)

port="$("$venv/bin/python" -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
"${clean_env[@]}" java -jar "$jar" --port "$port" --access-key "$ACCESS_KEY" --secret-key "$SECRET_KEY" \
  > "$work/local-s3.log" 2>&1 &
local_s3_pid=$!
trap 'kill "$local_s3_pid" 2>/dev/null || true' EXIT

for _ in $(seq 1 120); do
  if ! kill -0 "$local_s3_pid" 2>/dev/null; then
    echo "LocalS3 exited on startup, see $work/local-s3.log:" >&2
    tail -20 "$work/local-s3.log" >&2
    exit 1
  fi
  grep -q "LocalS3 started" "$work/local-s3.log" && break
  sleep 0.5
done

sed -e "s/@PORT@/$port/" -e "s/@ACCESS_KEY@/$ACCESS_KEY/" -e "s#@SECRET_KEY@#$SECRET_KEY#" \
  "$here/s3tests.conf.template" > "$work/s3tests.conf"

cd "$work/s3-tests"
set +e
"${clean_env[@]}" S3TEST_CONF="$work/s3tests.conf" PYTHONPATH="$here" \
  "$venv/bin/python" -m pytest -p local_s3_plugin -p no:cacheprovider \
  s3tests/functional/test_s3.py s3tests/functional/test_headers.py \
  -n 8 --timeout 120 -q -rfE --junitxml "$work/report.xml" "${pytest_args[@]+"${pytest_args[@]}"}"
status=$?
set -e
if [[ -n "${S3_TESTS_UPDATE_KNOWN_FAILURES:-}" && $status -eq 1 ]]; then
  # The failures are what was asked for.
  status=0
fi
exit $status
