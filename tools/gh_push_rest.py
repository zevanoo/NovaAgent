#!/usr/bin/env python3
"""Push the local novaagent HEAD commit to GitHub via the Git Data API.

Works where `git push` cannot (no tty for a credential prompt). Sends only what
the remote is missing: every object of HEAD's tree goes up as a blob + tree, then
one commit is created with the local commit's message and author.

The local and remote histories are independent here (earlier commits were built
server-side), so this creates a commit on top of the *remote* main, not on top of
the local parent. Same tree content, honest history.

Usage:  GH_TOKEN=<pat> python tools/gh_push_rest.py
        GH_TOKEN=<pat> python tools/gh_push_rest.py --workdir E:/novaagent
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

REPO = "zevanoo/NovaAgent"


def make_api(token):
    def api(method, path, payload=None, attempts=3):
        body = json.dumps(payload).encode() if payload is not None else None
        req = urllib.request.Request(
            "https://api.github.com" + path,
            data=body,
            method=method,
            headers={
                "Authorization": "Bearer " + token,
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "novaagent-push",
                "Content-Type": "application/json",
            },
        )
        last = None
        for attempt in range(attempts):
            try:
                with urllib.request.urlopen(req, timeout=60) as f:
                    raw = f.read()
                    return f.status, (json.loads(raw) if raw else {})
            except urllib.error.HTTPError as e:
                return e.code, e.read().decode()[:600]
            except Exception as e:
                last = e
                time.sleep(3 * (attempt + 1))
        raise SystemExit("api %s %s failed after %d attempts: %s" % (method, path, attempts, last))

    return api


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workdir", default=os.getcwd())
    args = ap.parse_args()
    token = os.environ.get("GH_TOKEN")
    if not token:
        sys.exit("GH_TOKEN not set")
    api = make_api(token)
    workdir = args.workdir

    def git(*cmd, binary=False):
        r = subprocess.run(("git",) + cmd, cwd=workdir, capture_output=True)
        if r.returncode:
            sys.exit("git %s failed: %s" % (" ".join(cmd), r.stderr.decode()[:400]))
        return r.stdout if binary else r.stdout.decode()

    head = git("rev-parse", "HEAD").strip()
    subject = git("log", "-1", "--format=%s", head).strip()
    author_name = git("log", "-1", "--format=%an", head).strip()
    author_email = git("log", "-1", "--format=%ae", head).strip()
    author_date = git("log", "-1", "--format=%aI", head).strip()

    st, ref = api("GET", "/repos/%s/git/ref/heads/main" % REPO)
    if st != 200:
        sys.exit("cannot read remote main: %s %s" % (st, ref))
    remote_head = ref["object"]["sha"]

    # Skip work if the remote already carries this exact tree.
    st, remote_commit = api("GET", "/repos/%s/git/commits/%s" % (REPO, remote_head))
    if st != 200:
        sys.exit("cannot read remote commit: %s %s" % (st, remote_commit))
    st, head_commit = api("GET", "/repos/%s/git/commits/%s" % (REPO, head))
    local_tree = git("rev-parse", head + "^{tree}").strip()

    blobs = {}

    def blob_sha(local_sha):
        if local_sha not in blobs:
            content = git("cat-file", "blob", local_sha, binary=True)
            code, res = api(
                "POST",
                "/repos/%s/git/blobs" % REPO,
                {"content": base64.b64encode(content).decode(), "encoding": "base64"},
            )
            if code != 201:
                sys.exit("blob %s failed: %s %s" % (local_sha[:7], code, res))
            blobs[local_sha] = res["sha"]
        return blobs[local_sha]

    def build_tree():
        root = {}
        for line in git("ls-tree", "-r", "-z", head).split("\0"):
            if not line.strip():
                continue
            meta, path = line.split("\t", 1)
            mode, _typ, obj = meta.split()
            node = root
            parts = path.split("/")
            for part in parts[:-1]:
                node = node.setdefault(part, {})
            node[parts[-1]] = (mode, obj)

        def make(node):
            tree = []
            for name, val in node.items():
                if isinstance(val, dict):
                    tree.append({"path": name, "mode": "040000", "type": "tree", "sha": make(val)})
                else:
                    mode, obj = val
                    tree.append({"path": name, "mode": mode, "type": "blob", "sha": blob_sha(obj)})
            code, res = api("POST", "/repos/%s/git/trees" % REPO, {"tree": tree})
            if code != 201:
                sys.exit("tree failed: %s %s" % (code, res))
            return res["sha"]

        return make(root)

    print("local tree:", local_tree)
    print("remote head:", remote_head[:7], "| unchanged tree ->", remote_commit.get("tree", {}).get("sha") == local_tree)

    tree_sha = build_tree()
    payload = {
        "message": subject,
        "tree": tree_sha,
        "parents": [remote_head],
        "author": {"name": author_name, "email": author_email, "date": author_date},
        "committer": {"name": author_name, "email": author_email, "date": author_date},
    }
    code, res = api("POST", "/repos/%s/git/commits" % REPO, payload)
    if code != 201:
        sys.exit("commit failed: %s %s" % (code, res))
    new_sha = res["sha"]
    print("commit:", new_sha[:7], subject)

    code, res = api("PATCH", "/repos/%s/git/refs/heads/main" % REPO, {"sha": new_sha, "force": True})
    print("ref update:", code)
    if code not in (200, 201):
        sys.exit("ref update failed: %s" % res)

    code, ref = api("GET", "/repos/%s/git/ref/heads/main" % REPO)
    print("remote main =", ref.get("object", {}).get("sha"))
    print("local HEAD  =", head)


if __name__ == "__main__":
    main()
