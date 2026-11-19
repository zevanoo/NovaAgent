#!/usr/bin/env python3
"""Download the APK from the newest successful CI run.

Usage: GH_TOKEN=... python3 tools/fetch_apk.py [output_apk]
Prints run id, artifact size, apk bytes, sha256 and signer cert.
"""
import hashlib
import io
import json
import os
import sys
import urllib.request
import zipfile

TOKEN = os.environ["GH_TOKEN"]
REPO = os.environ.get("REPO", "zevanoo/NovaAgent")
H = {"Authorization": "Bearer " + TOKEN, "Accept": "application/vnd.github+json", "User-Agent": "fetch-apk"}


class StripAuthOnRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new = super().redirect_request(req, fp, code, msg, headers, newurl)
        if new is not None:
            new.headers.pop("Authorization", None)
        return new


opener = urllib.request.build_opener(StripAuthOnRedirect)


def get(url):
    return opener.open(urllib.request.Request(url, headers=H), timeout=180).read()


runs = json.loads(get(f"https://api.github.com/repos/{REPO}/actions/runs?per_page=20"))["workflow_runs"]
run = next((r for r in runs if r["conclusion"] == "success"), None)
if run is None:
    sys.exit("tidak ada run sukses")

arts = json.loads(get(f"https://api.github.com/repos/{REPO}/actions/runs/{run['id']}/artifacts"))["artifacts"]
if not arts:
    sys.exit("run %s sukses tapi tidak ada artifact (expired?)" % run["id"])

art = arts[0]
blob = zipfile.ZipFile(io.BytesIO(get(art["archive_download_url"])))
apk_name = next(n for n in blob.namelist() if n.endswith(".apk"))
data = blob.read(apk_name)

out = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/novaagent-debug.apk")
with open(out, "wb") as f:
    f.write(data)

inner = zipfile.ZipFile(io.BytesIO(data))
dex = [n for n in inner.namelist() if n.endswith(".dex")]
print(f"run        : {run['id']} ({run['head_sha'][:7]})")
print(f"artifact   : {art['name']} {art['size_in_bytes']} bytes")
print(f"apk        : {len(data)} bytes -> {out}")
print(f"sha256     : {hashlib.sha256(data).hexdigest()}")
print(f"dex files  : {len(dex)}")
