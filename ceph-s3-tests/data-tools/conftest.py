"""
Starts the executable jar of LocalS3 once for the session, the way ``run.sh`` of ceph/s3-tests does: on a free port,
with an access key of its own, so every request has to be signed right, and with the built-in Iceberg REST catalog.

``LOCAL_S3_JAR`` overrides the jar, ``local-s3-standalone/build/libs/s3.jar``. The ``AWS_*`` variables of the shell
are taken out of the environment of the tests before any client reads them: boto3, s3fs, PyIceberg and the
``object_store`` crate of delta-rs and Polars would otherwise take the credentials, the region or the endpoint of a
real account from there.
"""

import json
import os
import socket
import subprocess
import time
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path

import boto3
import pytest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
WORK = HERE.parent / "build"
ACCESS_KEY = "local-s3-data-tools"
SECRET_KEY = "local-s3-data-tools-secret"
REGION = "us-east-1"

for _name in [name for name in os.environ if name.startswith("AWS_")]:
    del os.environ[_name]
# Nothing is to ask an EC2 instance metadata service for credentials when the ones given don't work.
os.environ["AWS_EC2_METADATA_DISABLED"] = "true"


@dataclass(frozen=True)
class LocalS3:
    endpoint: str
    access_key: str = ACCESS_KEY
    secret_key: str = SECRET_KEY
    region: str = REGION

    @property
    def iceberg_uri(self) -> str:
        return self.endpoint + "/iceberg"

    def s3(self):
        return boto3.client("s3", endpoint_url=self.endpoint, region_name=self.region,
                            aws_access_key_id=self.access_key, aws_secret_access_key=self.secret_key)

    def requests(self, limit: int = 500) -> list[dict]:
        """The last requests that LocalS3 answered, the most recent first, from ``GET /_admin/requests``."""
        from botocore.auth import SigV4Auth
        from botocore.awsrequest import AWSRequest
        from botocore.credentials import Credentials

        request = AWSRequest(method="GET", url=f"{self.endpoint}/_admin/requests?limit={limit}")
        SigV4Auth(Credentials(self.access_key, self.secret_key), "s3", self.region).add_auth(request)
        with urllib.request.urlopen(urllib.request.Request(request.url, headers=dict(request.headers)),
                                    timeout=10) as response:
            return json.load(response)["requests"]


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="session")
def local_s3():
    jar = Path(os.environ.get("LOCAL_S3_JAR", ROOT / "local-s3-standalone/build/libs/s3.jar"))
    if not jar.is_file():
        pytest.fail(f"No LocalS3 jar at {jar}; build it with ./gradlew :local-s3-standalone:jar")
    WORK.mkdir(exist_ok=True)
    log = WORK / "data-tools-local-s3.log"
    port = _free_port()
    env = {name: value for name, value in os.environ.items()
           if not name.startswith("AWS_") and not name.startswith("LOCAL_S3_")}
    with open(log, "w") as out:
        process = subprocess.Popen(
            ["java", "-jar", str(jar), "--port", str(port), "--access-key", ACCESS_KEY, "--secret-key", SECRET_KEY,
             "--iceberg-catalog", "true"],
            stdout=out, stderr=subprocess.STDOUT, env=env)
    try:
        deadline = time.monotonic() + 60
        while "LocalS3 started" not in log.read_text(errors="replace"):
            if process.poll() is not None:
                pytest.fail(f"LocalS3 exited on startup, see {log}:\n{log.read_text(errors='replace')[-2000:]}")
            if time.monotonic() > deadline:
                pytest.fail(f"LocalS3 didn't start within a minute, see {log}")
            time.sleep(0.2)
        yield LocalS3(endpoint=f"http://127.0.0.1:{port}")
    finally:
        process.terminate()
        try:
            process.wait(10)
        except subprocess.TimeoutExpired:
            process.kill()


@pytest.fixture
def bucket(local_s3) -> str:
    """A bucket of its own for a test."""
    name = f"data-tools-{uuid.uuid4().hex[:12]}"
    local_s3.s3().create_bucket(Bucket=name)
    return name
