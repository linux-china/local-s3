"""
A pytest plugin that runs ceph/s3-tests against LocalS3, loaded with ``-p local_s3_plugin``.

It narrows the suite to the core S3 API that LocalS3 serves with its single access key:

+ the tests of the markers in ``EXCLUDED_MARKERS`` are deselected: RGW's own features, the ones that need a
  lifecycle running in the background, Signature Version 2, and the tests that fail on Amazon S3 itself;
+ the tests that need an identity other than the main user, e.g. the alt or tenant user, an unsigned (anonymous)
  client, unsigned HTTP requests or IAM, and the tests of ACLs and bucket policies, are deselected. LocalS3 has a single access key and stores ACLs without
  enforcing them, so none of them can pass or say anything about it. A test is told apart by the names it uses,
  through the helpers of its module as well, see ``_excluded_names``.

The fixture of s3-tests cleans up the buckets of the alt and tenant users before and after every test, which a
service with a single access key answers with ``InvalidAccessKeyId``, so the plugin makes it clean up the buckets
of the main user alone.

The tests in ``known-failures.txt`` are expected to fail: they are marked ``xfail(strict=True)``, so the run fails
on a test that fails and isn't listed there, and on a listed test that passes, which is then to be taken off the
list. ``S3_TESTS_UPDATE_KNOWN_FAILURES=1`` runs every test without the list and writes the failed ones to it,
keeping the reason that follows the ``#`` of a test that still fails, e.g. ``test_x  # consistent with AWS: ...``.
"""

import ast
import os
from collections import Counter
from pathlib import Path

import pytest

KNOWN_FAILURES = Path(__file__).with_name("known-failures.txt")
UPDATE_KNOWN_FAILURES = os.environ.get("S3_TESTS_UPDATE_KNOWN_FAILURES") == "1"

EXCLUDED_MARKERS = {
    # RGW's delivery of access logs.
    "bucket_logging": "RGW specific",
    "bucket_logging_cleanup": "RGW specific",
    "fails_without_logging_rollover": "RGW specific",
    "fails_on_rgw": "RGW specific",
    "target_by_bucket": "RGW specific",
    # LocalS3 applies a lifecycle configuration only when a test asks for it, see docs/semantics.md.
    "lifecycle_expiration": "lifecycle in the background",
    "lifecycle_transition": "lifecycle in the background",
    "cloud_transition": "lifecycle in the background",
    "cloud_restore": "lifecycle in the background",
    "auth_aws2": "Signature Version 2",
    "fails_on_aws": "fails on Amazon S3",
    # Bucket policies are stored, not enforced, like ACLs.
    "bucket_policy": "access control",
    "iam_account": "IAM",
    "iam_user": "IAM",
    "iam_role": "IAM",
    "iam_tenant": "IAM",
    "iam_cross_account": "IAM",
}

# The helpers of s3tests.functional that give a client or an identity other than the main user.
OTHER_IDENTITY_NAMES = {
    "get_alt_client", "get_tenant_client", "get_v2_tenant_client", "get_unauthenticated_client",
    "get_cloud_client", "get_v2_client", "get_svc_client", "get_sts_client", "get_iam_client",
    "get_iam_s3client", "get_iam_root_client", "get_iam_root_s3client", "get_iam_alt_root_client",
    "get_tenant_iam_client", "get_alt_iam_client", "get_alt_user_id", "get_alt_display_name",
    "get_alt_email", "get_alt_account_id", "get_tenant_user_id", "get_tenant_display_name",
    "get_tenant_email", "get_tenant_account_id", "get_tenant_name", "get_iam_root_user_id",
    "get_iam_root_account_id", "get_iam_alt_root_user_id", "get_iam_alt_root_account_id",
    "get_sts_user_id", "iam_root", "iam_alt_root",
}

# The operations of ACLs. A canned ACL that a test gives when it creates a bucket or an object isn't among them:
# LocalS3 accepts it like Amazon S3 does, and what the test goes on to check is something else.
ACL_NAMES = {
    "put_bucket_acl", "put_object_acl", "get_bucket_acl", "get_object_acl", "AccessControlPolicy",
}

# Bucket policies, which LocalS3 stores without enforcing, like ACLs.
POLICY_NAMES = {"put_bucket_policy"}

# A test that sends HTTP requests of its own, with requests, sends them unsigned unless it presigns them or signs a
# form (POST Object).
RAW_HTTP = "<raw HTTP>"
SIGNED = "<signed>"
SIGNATURE_NAMES = {"generate_presigned_url", "generate_presigned_post"}
SIGNATURE_FIELDS = {"signature", "x-amz-signature", "AWSAccessKeyId"}

TRACKED_NAMES = OTHER_IDENTITY_NAMES | ACL_NAMES | POLICY_NAMES | {RAW_HTTP, SIGNED}


def _referenced_names(node):
    """The names that a function refers to, with RAW_HTTP and SIGNED for its own HTTP requests."""
    names = set()
    for child in ast.walk(node):
        if isinstance(child, ast.Name):
            names.add(child.id)
        elif isinstance(child, ast.Attribute):
            names.add(child.attr)
            if isinstance(child.value, ast.Name) and child.value.id == "requests":
                names.add(RAW_HTTP)
            if child.attr in SIGNATURE_NAMES:
                names.add(SIGNED)
        elif isinstance(child, ast.Constant) and child.value in SIGNATURE_FIELDS:
            names.add(SIGNED)
    return names


_module_cache = {}


def _excluded_names(path):
    """
    The tracked names that each function of a module refers to, itself or through the other functions of the
    module that it calls, e.g. a test that calls ``_setup_access`` of test_s3.py, which calls ``get_alt_client``.
    """
    if path in _module_cache:
        return _module_cache[path]
    tree = ast.parse(Path(path).read_text())
    functions = {n.name: _referenced_names(n) for n in tree.body if isinstance(n, ast.FunctionDef)}
    resolved = {}

    def resolve(name, visiting):
        if name in resolved:
            return resolved[name]
        if name in visiting:
            return set()
        visiting.add(name)
        refs = functions[name]
        found = refs & TRACKED_NAMES
        for callee in refs & functions.keys():
            found |= resolve(callee, visiting)
        resolved[name] = found
        return found

    for name in functions:
        resolve(name, set())
    _module_cache[path] = resolved
    return resolved


def _exclusion(item):
    """The reason to leave out a test, or None to run it."""
    for marker in item.iter_markers():
        if marker.name in EXCLUDED_MARKERS:
            return EXCLUDED_MARKERS[marker.name]
    names = _excluded_names(str(item.path)).get(item.originalname, set())
    if names & OTHER_IDENTITY_NAMES:
        return "other identity"
    if names & ACL_NAMES:
        return "ACL"
    if names & POLICY_NAMES:
        return "access control"
    if RAW_HTTP in names and SIGNED not in names:
        return "anonymous"
    return None


def _test_id(item):
    return f"{item.path.name}::{item.name}"


def _read_known_failures():
    """The known failures, each mapped to the reason that follows its ``#``, or to ``""`` if it has none."""
    if not KNOWN_FAILURES.exists():
        return {}
    known_failures = {}
    for line in KNOWN_FAILURES.read_text().splitlines():
        test_id, _, reason = line.partition("#")
        if test_id.strip():
            known_failures[test_id.strip()] = reason.strip()
    return known_failures


_config = None


def pytest_configure(config):
    global _config
    _config = config
    config._local_s3_failed = set()
    config._local_s3_deselected = Counter()


def pytest_sessionstart(session):
    import s3tests.functional as functional

    # The main user alone: the alt and tenant users don't exist on a service with a single access key.
    def setup():
        functional.nuke_prefixed_buckets(prefix=functional.prefix)

    functional.setup = setup
    functional.teardown = setup


def pytest_collection_modifyitems(config, items):
    known_failures = {} if UPDATE_KNOWN_FAILURES else _read_known_failures()
    selected, deselected = [], []
    for item in items:
        reason = _exclusion(item)
        if reason:
            config._local_s3_deselected[reason] += 1
            deselected.append(item)
            continue
        if _test_id(item) in known_failures:
            item.add_marker(pytest.mark.xfail(reason="listed in known-failures.txt", strict=True))
        selected.append(item)
    if deselected:
        config.hook.pytest_deselected(items=deselected)
        items[:] = selected


def pytest_runtest_logreport(report):
    # A failure of the fixture's setup or teardown fails the test as well.
    if report.failed and not hasattr(report, "wasxfail"):
        _config._local_s3_failed.add(report.nodeid.rsplit("/", 1)[-1])


def pytest_sessionfinish(session):
    config = session.config
    if hasattr(config, "workerinput") or not UPDATE_KNOWN_FAILURES:
        return
    header = [
        "# The tests of ceph/s3-tests that fail on LocalS3, which local_s3_plugin.py expects to fail.",
        "# Written by `ceph-s3-tests/run.sh --update-known-failures`; take a test off once it passes.",
        "# A test without a reason is to be fixed; one marked `consistent with AWS` expects the behavior of",
        "# another S3 implementation, e.g. RGW, where LocalS3 behaves like Amazon S3, and is kept on purpose.",
    ]
    reasons = _read_known_failures()
    lines = [f"{test_id}  # {reasons[test_id]}" if reasons.get(test_id) else test_id
             for test_id in sorted(config._local_s3_failed)]
    KNOWN_FAILURES.write_text("\n".join(header + lines) + "\n")


def pytest_terminal_summary(terminalreporter, config):
    if hasattr(config, "workerinput"):
        return
    # With pytest-xdist the workers collect the tests, so only a run without it counts them here.
    if config._local_s3_deselected:
        counts = ", ".join(f"{reason}: {count}" for reason, count in config._local_s3_deselected.most_common())
        terminalreporter.write_line(f"LocalS3 deselected {config._local_s3_deselected.total()} tests ({counts})")
    if UPDATE_KNOWN_FAILURES:
        terminalreporter.write_line(f"LocalS3 wrote {len(config._local_s3_failed)} failed tests to {KNOWN_FAILURES}")
