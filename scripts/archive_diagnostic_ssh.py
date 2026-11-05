#!/usr/bin/env python3
"""Optional NAS SSH transport; stdout is COPIED/EXISTING only on verified success."""
import hashlib
import os
from pathlib import Path
import shlex
import subprocess
import sys

REMOTE = r'''
set -eu
umask 077
dir=$1
name=$2
expected=$3
mkdir -p -- "$dir"
tmp=$(mktemp "$dir/.octop-ssh.XXXXXX")
trap 'rm -f -- "$tmp"' EXIT
trap 'exit 1' HUP INT TERM
cat > "$tmp"
if command -v sha256sum >/dev/null 2>&1; then
    actual=$(sha256sum "$tmp")
else
    actual=$(shasum -a 256 "$tmp")
fi
actual=${actual%% *}
[ "$actual" = "$expected" ] || { echo 'SSH archive checksum mismatch' >&2; exit 1; }
# Hard-link publication is atomic and cannot overwrite a concurrent/existing archive.
if ln "$tmp" "$dir/$name" 2>/dev/null; then
    echo COPIED
elif [ -f "$dir/$name" ] && cmp -s "$tmp" "$dir/$name"; then
    echo EXISTING
else
    echo 'SSH archive conflict or publication failure' >&2
    exit 1
fi
'''


def main():
    source = Path(sys.argv[1])
    target = os.environ["OCTOP_LOG_NAS_SSH_TARGET"]
    directory = os.environ.get("OCTOP_LOG_NAS_SSH_DIR", "/volume2/tools/octop-logs")
    if not target or target.startswith("-") or any(c.isspace() for c in target):
        raise ValueError("Invalid SSH target")
    if not directory.startswith("/"):
        raise ValueError("SSH archive directory must be absolute")
    timeout = int(os.environ.get("OCTOP_LOG_NAS_SSH_TIMEOUT", "120"))
    if timeout <= 0:
        raise ValueError("SSH timeout must be positive")
    digest = hashlib.sha256()
    with source.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
        stream.seek(0)
        command = "sh -c " + shlex.quote(REMOTE) + " sh " + " ".join(
            shlex.quote(arg) for arg in (directory, source.name, digest.hexdigest()))
        result = subprocess.run(
            ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
             "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=2",
             target, command], stdin=stream, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, timeout=timeout, text=True)
    if result.returncode or result.stdout.strip() not in ("COPIED", "EXISTING"):
        raise RuntimeError("SSH archive failed: " + result.stderr.strip())
    print(result.stdout.strip())


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
