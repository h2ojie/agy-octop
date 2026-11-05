# Diagnostic archive verification

Android upload success means the server accepted the ZIP. It does **not** confirm Mac/NAS archiving. The separate sync script copies files into local/cloud/NAS filesystem directories; cloud provider replication is not verified.

## Offline regression checks

Run from the repository root:

```sh
bash -n scripts/sync_diagnostic_logs.sh
python3 scripts/test_sync_diagnostic_logs.py -v
JAVA_HOME="$HOME/.jdks/corretto-17/Contents/Home" ./gradlew :app:testDebugUnitTest --console=plain
```

The Python tests create temporary directories and dummy diagnostic files, override **all** archive/source roots, and inject a failing `cp`. They do not access production logs, mounted drives or a real NAS. JVM unit tests exercise the same history-answer selector used by ChatViewModel; they do not build/install an APK or call a server.

## Archive behavior

- Matches `octop_logs_*.zip` and server-prefixed `*_octop_logs_*.zip`, including timestamp prefixes. Spaces/newlines in paths are supported.
- Default sources are only `$HOME/.octop/agents/*/inbound` and `$HOME/.octop/workspaces/*/inbound`, recursively within these upload directories. The script never traverses the complete `.octop` tree, portable/runtime, backups, or workspace repositories. No inbound directories means zero files, with no broad fallback.
- Default destinations: local `$HOME/octop-logs`, cloud `$HOME/Library/CloudStorage/GoogleDrive-jieiceone@gmail.com/我的云端硬盘/octop-logs`, NAS `/Volumes/tools/octop-logs`.
- Overrides: a nonempty `OCTOP_LOG_SOURCE_DIR` explicitly selects one recursive source (for tests/custom deployments); `OCTOP_LOG_LOCAL_DIR`, `OCTOP_LOG_GDRIVE_ROOT`, `OCTOP_LOG_NAS_ROOT` select destinations. Cloud/NAS roots receive an `octop-logs` child. Default-source regression tests replace HOME with a temporary fixture.
- Missing cloud/NAS roots are explicitly `SKIPPED`; the script does not create those roots or pretend they were synced. Missing explicit source, scan errors, directory errors and copy errors exit nonzero.
- Existing archives are compared with the source. Identical archives are skipped; different same-name files produce an error rather than silently overwrite data.
- Copies use private temporary files and only rename after successful copying. Failed copies remove their temporary files, permitting retries. New directories/files use `umask 077`; existing directory permissions are not changed.
- Summary includes found/copied/existing/skipped-destination/failure counts. Exit zero with skipped destinations means available destinations succeeded, not that all archives exist remotely.
- The script checks root directory availability; a directory alone is not proof that a remote filesystem is healthy. The scheduler should not overlap executions and should retain stdout/stderr for failures.

## Optional SSH NAS transport

Set `OCTOP_LOG_NAS_SSH_TARGET=nas` and `OCTOP_LOG_NAS_SSH_DIR=/volume2/tools/octop-logs` in the scheduler environment to opt in. Without the target, the original mounted-NAS mode remains active. SSH mode never accesses the NAS mount. It uses the independent `archive_diagnostic_ssh.py` helper and requires Python 3 locally plus POSIX sh, mktemp, cmp, ln and sha256sum (or shasum) on the NAS.

SSH uses BatchMode, a 10-second connection timeout, keepalives, and a hard per-file process timeout (`OCTOP_LOG_NAS_SSH_TIMEOUT`, default 120 seconds). Existing SSH config/host-key checks are preserved; no security settings are changed. Ensure the scheduler PATH resolves Python 3 and ssh.

Files stream into private remote temporary files. A local/remote SHA-256 comparison must pass before publication. An atomic hard link publishes the complete file without overwriting any existing archive; identical existing files pass cmp and count as existing, while conflicts fail and preserve the original. This avoids an scp dependency. The temporary name is removed on normal exit/signals; a remote process forcibly killed during host failure may leave a private `.octop-ssh.*` file, never a partial final archive. Remote filesystem must support hard links.

Transport errors, timeouts, checksum mismatches, permission errors and conflicts increment failures and produce nonzero exit status; none claim NAS success. SSH regression tests replace ssh with local stubs and execute the remote shell only inside temporary fixtures, covering copy/idempotence, quoting, conflicts, connection failures, checksum failure and timeout.

## History-answer behavior

Only `assistant`/`ai` records with nonblank parsed text can be the final answer. Tool records remain in the turn for tool counting but cannot replace the final answer. A tool-only turn has no answer candidate. Ordinary assistant JSON (including memory-like keys) remains intact: this is a role-based provenance fix, not a content blacklist.
