#!/usr/bin/env python3
"""Fetch a GitHub Actions job log and print the interesting lines.

Usage: GH_TOKEN=... python3 tools/ci_log.py <run_id> [grep_regex]
"""
import gzip
import json
import os
import re
import sys
import urllib.request

TOKEN = os.environ["GH_TOKEN"]
REPO = os.environ.get("REPO", "zevanoo/NovaAgent")
H = {"Authorization": "Bearer " + TOKEN, "Accept": "application/vnd.github+json", "User-Agent": "ci-log"}


class StripAuthOnRedirect(urllib.request.HTTPRedirectHandler):
    """Log archives redirect to blob storage, which rejects the bearer token."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new = super().redirect_request(req, fp, code, msg, headers, newurl)
        if new is not None:
            new.headers.pop("Authorization", None)
        return new


opener = urllib.request.build_opener(StripAuthOnRedirect)


def get(url):
    return opener.open(urllib.request.Request(url, headers=H), timeout=120).read()


def main():
    run_id = int(sys.argv[1])
    pattern = sys.argv[2] if len(sys.argv) > 2 else r"e: |w: |> Task .*FAILED|What went wrong|Caused by|FAILURE:|Execution failed|error:"
    jobs = json.loads(get(f"https://api.github.com/repos/{REPO}/actions/runs/{run_id}/jobs"))["jobs"]
    for job in jobs:
        if job["conclusion"] != "failure":
            continue
        raw = get(f"https://api.github.com/repos/{REPO}/actions/jobs/{job['id']}/logs")
        try:
            raw = gzip.decompress(raw)
        except OSError:
            pass
        txt = raw.decode("utf-8", "replace")
        for line in txt.splitlines():
            if re.search(pattern, line):
                print(line[:600])
    print("--- end --- (fetch full log: GH_TOKEN=... python3 tools/ci_log.py %d .)" % run_id)


main()
