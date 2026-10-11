"""Publish a locally signed APK using an existing Git Credential Manager login.

Standard library only. Credentials stay in memory and are never printed or saved.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", default="zhouwenp925/come_on_APP")
    parser.add_argument("--draft", action="store_true")
    args = parser.parse_args()
    repo = args.repository
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Invalid repository")
    root = Path(__file__).resolve().parent.parent
    version_text = (root / "version.properties").read_text(encoding="utf-8")
    version = re.search(r"^versionName=(\d+\.\d+\.\d+)$", version_text, re.M)
    if not version:
        raise ValueError("Invalid stable version")
    version = version[1]
    apk = root / "artifacts" / f"shouxin-{version}-release.apk"
    checksum = apk.with_name(apk.name + ".sha256")
    if not apk.is_file() or not checksum.is_file():
        raise ValueError("Run scripts/Build.ps1 -Mode Release first")
    if hashlib.sha256(apk.read_bytes()).hexdigest() != checksum.read_text().split()[0]:
        raise ValueError("APK checksum changed; rebuild before publishing")
    login = subprocess.run(["git", "credential", "fill"],
        input=f"protocol=https\nhost=github.com\npath={repo}.git\n\n",
        text=True, capture_output=True, timeout=30,
        env=dict(os.environ, GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="never"))
    if login.returncode:
        raise ValueError("No existing GitHub login. Sign in with Git Credential Manager or GitHub CLI")
    credential = dict(line.split("=", 1) for line in login.stdout.splitlines() if "=" in line)
    token = credential.get("password")
    if not token:
        raise ValueError("GitHub credential is unavailable")

    def request(path, method="GET", data=None, binary=None):
        url = path if path.startswith("https://uploads.github.com/") else f"https://api.github.com/repos/{repo}/{path}"
        body = binary if binary is not None else json.dumps(data, ensure_ascii=False).encode("utf-8") if data is not None else None
        req = Request(url, data=body, method=method, headers={
            "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "Shouxin-release",
            "Content-Type": "application/octet-stream" if binary is not None else "application/json"})
        with urlopen(req, timeout=90) as response:
            return json.load(response)

    remote = request("contents/version.properties?ref=main")
    if base64.b64decode(remote["content"]).decode("utf-8").strip() != version_text.strip():
        raise ValueError("Remote main version differs; commit and push the version first")
    tag = f"v{version}"
    releases = request("releases?per_page=50")
    release = next((r for r in releases if r["tag_name"] == tag), None)
    if release and not release["draft"]:
        raise ValueError("This version is already published; bump the version instead of replacing it")
    if not release:
        head = request("commits/main")["sha"]
        release = request("releases", "POST", {"tag_name": tag, "target_commitish": head,
            "name": f"Shouxin {version}", "body": (root / "CHANGELOG.md").read_text(encoding="utf-8"),
            "draft": True, "prerelease": False})
    assets = request(f"releases/{release['id']}/assets")
    for file in (apk, checksum):
        content = file.read_bytes()
        existing = next((a for a in assets if a["name"] == file.name), None)
        if existing:
            if existing.get("digest") != "sha256:" + hashlib.sha256(content).hexdigest():
                raise ValueError("Draft asset differs; inspect the draft before replacing it")
            continue
        request(f"https://uploads.github.com/repos/{repo}/releases/{release['id']}/assets?name={quote(file.name)}", "POST", binary=content)
    if not args.draft:
        request(f"releases/{release['id']}", "PATCH", {"draft": False})
    print("Draft ready:" if args.draft else "Published:", f"https://github.com/{repo}/releases/tag/{tag}")
    print("Release ID:", release["id"])


if __name__ == "__main__":
    try:
        main()
    except HTTPError as error:
        print(f"GitHub HTTP {error.code}; inspect repository permissions and the release page.", file=sys.stderr)
        sys.exit(1)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
