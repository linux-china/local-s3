#!/usr/bin/env bash
# Runs the Python data tools, PyIceberg, delta-rs, Polars, Lance, LanceDB, s3fs and pandas, against the executable jar of LocalS3,
# which conftest.py starts; see README.md.
#
#   ceph-s3-tests/data-tools/run.sh              every test
#   ceph-s3-tests/data-tools/run.sh -k delta     the arguments go to pytest
#
# LOCAL_S3_JAR overrides the jar, local-s3-standalone/build/libs/s3.jar, which `./gradlew :local-s3-standalone:jar`
# builds. uv (https://docs.astral.sh/uv/) manages the Python environment, from pyproject.toml and uv.lock.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"

if ! command -v uv >/dev/null; then
  echo "uv is needed to run the tests, see https://docs.astral.sh/uv/getting-started/installation/" >&2
  exit 1
fi
# The environment of uv.lock, in data-tools/.venv; --locked fails if uv.lock no longer matches pyproject.toml.
uv sync --quiet --locked --project "$here"

cd "$here"
exec uv run --quiet --locked --no-sync --project "$here" python -m pytest -p no:cacheprovider -q -rfE \
  --junitxml "$here/../build/data-tools-report.xml" "$@"
