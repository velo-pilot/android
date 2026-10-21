#!/usr/bin/env python3
"""Mirrors local folders of the F-Droid repository to a Bunny Storage zone and purges the CDN cache.

    bunny_sync.py [--dry-run] LOCAL_ROOT REMOTE_PREFIX FOLDER...

Uploads LOCAL_ROOT/FOLDER/** to REMOTE_PREFIX/FOLDER/** in the storage zone, skipping files whose SHA-256
already matches, and deletes remote files that no longer exist locally. APKs and icons go first and the
index files last, so a client never reads an index that points at a file that is not there yet.

Environment:
    BUNNY_STORAGE_ZONE      storage zone name
    BUNNY_STORAGE_PASSWORD  storage zone password (FTP & API access)
    BUNNY_STORAGE_HOST      storage endpoint of the zone's main region (default storage.bunnycdn.com)
    BUNNY_API_KEY           account API key; when set, purges CDN_URL/REMOTE_PREFIX/* after a change
    CDN_URL                 public URL of the pull zone, e.g. https://cdn.osmpedelec.be
"""
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


def env(name, default=None):
    value = os.environ.get(name, default)
    if value is None:
        sys.exit(f"{name} is not set")
    return value


ZONE = env("BUNNY_STORAGE_ZONE")
PASSWORD = env("BUNNY_STORAGE_PASSWORD")
HOST = env("BUNNY_STORAGE_HOST", "storage.bunnycdn.com")


def storage_url(path):
    return f"https://{HOST}/{urllib.parse.quote(ZONE)}/{urllib.parse.quote(path)}"


def request(method, url, key, data=None, headers=None):
    req = urllib.request.Request(url, data=data, method=method, headers={"AccessKey": key, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=600) as response:
            return response.read()
    except urllib.error.HTTPError as e:
        if e.code == 401:
            sys.exit(f"{method} {url}: 401 Unauthorized; check the zone, password and host, or the API key")
        raise


def list_remote(prefix):
    """Returns {path: sha256} for all files below prefix."""
    files = {}
    try:
        entries = json.loads(request("GET", storage_url(prefix.rstrip("/") + "/"), PASSWORD))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return files
        raise
    for entry in entries:
        path = f"{prefix.rstrip('/')}/{entry['ObjectName']}"
        if entry["IsDirectory"]:
            files.update(list_remote(path))
        else:
            files[path] = (entry.get("Checksum") or "").lower()
    return files


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def upload_order(path):
    name = os.path.basename(path)
    if name.startswith("entry."):
        return 2
    if name.startswith("index") and path.count("/") <= 2:
        return 1
    return 0


def main():
    args = sys.argv[1:]
    dry_run = "--dry-run" in args
    args = [a for a in args if a != "--dry-run"]
    if len(args) < 3:
        sys.exit(__doc__)
    local_root, prefix, folders = args[0], args[1].strip("/"), args[2:]

    local = {}
    for folder in folders:
        base = os.path.join(local_root, folder)
        for dirpath, _, filenames in os.walk(base):
            for filename in filenames:
                full = os.path.join(dirpath, filename)
                local[f"{prefix}/{os.path.relpath(full, local_root)}"] = full

    remote = {}
    for folder in folders:
        remote.update(list_remote(f"{prefix}/{folder}"))

    uploads = sorted((p for p in local if remote.get(p) != sha256(local[p])), key=lambda p: (upload_order(p), p))
    deletes = sorted(p for p in remote if p not in local)

    for path in uploads:
        print(f"upload {path}")
        if not dry_run:
            with open(local[path], "rb") as f:
                request("PUT", storage_url(path), PASSWORD, data=f, headers={
                    "Content-Type": "application/octet-stream",
                    "Content-Length": str(os.path.getsize(local[path])),
                    "Checksum": sha256(local[path]).upper(),
                })
    for path in deletes:
        print(f"delete {path}")
        if not dry_run:
            request("DELETE", storage_url(path), PASSWORD)

    if not uploads and not deletes:
        print("Storage zone is up to date")
        return
    api_key = os.environ.get("BUNNY_API_KEY")
    if not api_key:
        print("BUNNY_API_KEY is not set: the CDN serves cached index files until they expire")
        return
    purge = f"{env('CDN_URL').rstrip('/')}/{prefix}/*"
    print(f"purge {purge}")
    if not dry_run:
        request("POST", "https://api.bunny.net/purge?" + urllib.parse.urlencode({"url": purge}), api_key)


if __name__ == "__main__":
    main()
