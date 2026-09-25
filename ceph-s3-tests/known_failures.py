"""
The known failures of ceph/s3-tests on LocalS3, ``known-failures.txt``, and their categories.

The reason that follows the ``#`` of a test starts with its category, e.g. ``test_x  # consistent with AWS: ...``:

+ ``consistent with AWS``: the test expects the behavior of another implementation, mostly RGW, where LocalS3 behaves
  like Amazon S3; kept on purpose.
+ ``out of scope``: the test checks what LocalS3 doesn't do by design, e.g. enforce access control; kept on purpose.
+ ``test environment``: the test fails because of how the suite runs, not because of LocalS3; kept on purpose.
+ ``unverified``: LocalS3 differs from what the test expects, and it isn't known yet which of them Amazon S3 does; to
  be checked against Amazon S3, then fixed or recategorized.
+ ``divergence``: LocalS3 differs from Amazon S3; to be fixed. A test without a reason, or whose reason starts with no
  category, is one too.

``python3 ceph-s3-tests/known_failures.py`` prints the counts of the categories and the tests to fix or verify, in
Markdown, e.g. for the summary of a CI job.
"""

from collections import Counter
from pathlib import Path

KNOWN_FAILURES = Path(__file__).with_name("known-failures.txt")

KEPT = ("consistent with AWS", "out of scope", "test environment")
UNVERIFIED = "unverified"
DIVERGENCE = "divergence"
CATEGORIES = KEPT + (UNVERIFIED, DIVERGENCE)

HEADER = [
    "# The tests of ceph/s3-tests that fail on LocalS3, which local_s3_plugin.py expects to fail.",
    "# Written by `ceph-s3-tests/run.sh --update-known-failures`; take a test off once it passes.",
    "# The reason after the `#` starts with a category, see known_failures.py: `consistent with AWS`,",
    "# `out of scope` and `test environment` are kept on purpose, `unverified` is to be checked against",
    "# Amazon S3, and `divergence`, or a test without a category, is to be fixed.",
]


def read(path=KNOWN_FAILURES):
    """The known failures, each mapped to the reason that follows its ``#``, or to ``""`` if it has none."""
    if not path.exists():
        return {}
    known_failures = {}
    for line in path.read_text().splitlines():
        test_id, _, reason = line.partition("#")
        if test_id.strip():
            known_failures[test_id.strip()] = reason.strip()
    return known_failures


def category(reason):
    """The category that a reason starts with; ``divergence`` for a reason without one."""
    for name in CATEGORIES:
        if reason.startswith(name + ":") or reason == name:
            return name
    return DIVERGENCE


def summary(known_failures):
    """The counts of the categories, and the tests to fix or verify, in Markdown."""
    categories = {test_id: category(reason) for test_id, reason in known_failures.items()}
    counts = Counter(categories.values())
    lines = [
        f"### ceph/s3-tests known failures: {len(known_failures)}",
        "",
        "| Category | Tests |",
        "|---|---|",
    ]
    lines += [f"| {name} | {counts[name]} |" for name in CATEGORIES]
    for name, title in ((DIVERGENCE, "To fix (divergence)"), (UNVERIFIED, "To verify against Amazon S3")):
        tests = sorted(test_id for test_id, test_category in categories.items() if test_category == name)
        if tests:
            lines += ["", f"{title}:", ""] + [f"+ `{test_id}`" for test_id in tests]
    return "\n".join(lines)


if __name__ == "__main__":
    print(summary(read()))
