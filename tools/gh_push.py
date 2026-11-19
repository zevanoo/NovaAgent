#!/usr/bin/env python3
"""Push local novaagent commits to GitHub via the Git Data API.

Termux git ships no remote-http helper, so `git push` over https is impossible
here. This rebuilds the same commit chain server-side (blobs -> trees ->
commits -> ref) from the local objects, one local commit at a time.
"""
import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

REPO = "zevanoo/NovaAgent"
TOKEN = os.environ["GH_TOKEN"]
WORKDIR = os.path.expanduser("~/novaagent")


def api(method, path, payload=None, attempts=3):
    body = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(
        "https://api.github.com" + path,
        data=body,
        method=method,
        headers={
            "Authorization": "Bearer " + TOKEN,
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
        except Exception as e:  # socket timeouts and DNS blips on mobile networks
            last = e
            time.sleep(3 * (attempt + 1))
    raise SystemExit("api %s %s failed after %d attempts: %s" % (method, path, attempts, last))


def git(*args, binary=False):
    r = subprocess.run(("git",) + args, cwd=WORKDIR, capture_output=True)
    if r.returncode:
        sys.exit("git %s failed: %s" % (" ".join(args), r.stderr.decode()[:400]))
    return r.stdout if binary else r.stdout.decode()


_blobs = {}


def blob_sha(local_sha):
    if local_sha not in _blobs:
        content = git("cat-file", "blob", local_sha, binary=True)
        st, res = api("POST", "/repos/%s/git/blobs" % REPO,
                      {"content": base64.b64encode(content).decode(), "encoding": "base64"})
        if st != 201:
            sys.exit("blob %s failed: %s %s" % (local_sha[:7], st, res))
        _blobs[local_sha] = res["sha"]
    return _blobs[local_sha]


def build_tree(commit_sha):
    """Recursively create server-side trees for one local commit. Returns root tree sha."""
    entries = []  # (path, mode, blob sha)
    for line in git("ls-tree", "-r", commit_sha).splitlines():
        if not line.strip():
            continue
        meta, path = line.split("\t")
        mode, _typ, obj = meta.split()
        entries.append((path, mode, obj))

    # nested dict: {dirname: {...}} / {filename: (mode, blob)}
    root = {}
    for path, mode, obj in entries:
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
        st, res = api("POST", "/repos/%s/git/trees" % REPO, {"tree": tree})
        if st != 201:
            sys.exit("tree failed: %s %s" % (st, res))
        return res["sha"]

    return make(root)


def main():
    # GitHub refuses blob/tree/commit calls on a repo with zero commits (409).
    # Seed one throwaway commit, then force main onto the real first commit.
    st, ref = api("GET", "/repos/%s/git/ref/heads/main" % REPO)
    if st != 200:
        st2, res = api("PUT", "/repos/%s/contents/.seed" % REPO,
                       {"message": "seed empty repo", "content": base64.b64encode(b"seed\n").decode()})
        if st2 not in (200, 201):
            sys.exit("seed failed: %s %s" % (st2, res))
        print("seeded empty repo (throwaway commit, will be orphaned)")

    out = subprocess.run(("git", "log", "--reverse", "--format=%H%x00%P%x00%an%x00%ae%x00%aI%x00%s"),
                         cwd=WORKDIR, capture_output=True, text=True, check=True).stdout
    commits = []
    for line in out.splitlines():
        sha, parents, an, ae, ad, subject = line.split("\x00")
        commits.append(dict(sha=sha, parents=parents.split(), an=an, ae=ae, ad=ad, subject=subject))

    for c in commits:
        tree = build_tree(c["sha"])
        parents = []
        # map local parent -> already-pushed server sha
        for p in c["parents"]:
            if p not in pushed:
                sys.exit("parent %s not yet pushed" % p[:7])
            parents.append(pushed[p])
        payload = {
            "message": c["subject"],
            "tree": tree,
            "author": {"name": c["an"], "email": c["ae"], "date": c["ad"]},
            "committer": {"name": c["an"], "email": c["ae"], "date": c["ad"]},
            "parents": parents,
        }
        st, res = api("POST", "/repos/%s/git/commits" % REPO, payload)
        if st != 201:
            sys.exit("commit failed: %s %s" % (st, res))
        pushed[c["sha"]] = res["sha"]
        print("commit %s -> %s %s" % (c["sha"][:7], res["sha"][:7], c["subject"]))

    head = pushed[commits[-1]["sha"]]
    st, _ = api("GET", "/repos/%s/git/ref/heads/main" % REPO)
    if st == 200:
        st, res = api("PATCH", "/repos/%s/git/refs/heads/main" % REPO, {"sha": head, "force": True})
    else:
        st, res = api("POST", "/repos/%s/git/refs" % REPO, {"ref": "refs/heads/main", "sha": head})
    print("ref update:", st)
    st, ref = api("GET", "/repos/%s/git/ref/heads/main" % REPO)
    print("remote main =", ref.get("object", {}).get("sha"))
    print("local  HEAD =", subprocess.run(("git", "rev-parse", "HEAD"), cwd=WORKDIR,
                                          capture_output=True, text=True).stdout.strip())


pushed = {}
main()
