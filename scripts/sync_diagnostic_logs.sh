#!/bin/bash
# Copy uploaded diagnostics to local / mounted cloud / mounted NAS archives.
# Override paths for isolated tests. Never create an absent cloud or NAS mount root.
set -u
umask 077

# Production uploads live directly below agent/workspace inbound directories.
# An explicit override retains recursive scanning for isolated tests/custom sources.
SOURCE_DIR="${OCTOP_LOG_SOURCE_DIR:-}"
LOCAL_DIR="${OCTOP_LOG_LOCAL_DIR:-$HOME/octop-logs}"
GDRIVE_ROOT="${OCTOP_LOG_GDRIVE_ROOT:-$HOME/Library/CloudStorage/GoogleDrive-jieiceone@gmail.com/我的云端硬盘}"
NAS_ROOT="${OCTOP_LOG_NAS_ROOT:-/Volumes/tools}"
NAS_SSH_TARGET="${OCTOP_LOG_NAS_SSH_TARGET:-}"
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
GDRIVE_DIR="$GDRIVE_ROOT/octop-logs"
NAS_DIR="$NAS_ROOT/octop-logs"
failures=0
copied=0
existing=0
skipped=0
found=0

prepare_destination() {
    local dir="$1"
    if ! mkdir -p "$dir"; then
        echo "ERROR: Cannot create archive directory: $dir" >&2
        failures=$((failures + 1))
        return 1
    fi
}

# Copy to a temporary file first, so a failed copy cannot poison the next retry.
copy_archive() {
    local source="$1" dir="$2" label="$3" filename="$4" temp
    if [ -f "$dir/$filename" ]; then
        if cmp -s "$source" "$dir/$filename"; then
            existing=$((existing + 1))
            return
        fi
        echo "ERROR: Existing archive differs from source: $dir/$filename" >&2
        failures=$((failures + 1))
        return
    fi
    if ! temp=$(mktemp "$dir/.octop-copy.XXXXXX"); then
        echo "ERROR: Cannot prepare copy to $label: $filename" >&2
        failures=$((failures + 1))
        return
    fi
    if cp "$source" "$temp" && mv "$temp" "$dir/$filename"; then
        copied=$((copied + 1))
        echo "Copied to $label archive: $dir/$filename"
    else
        echo "ERROR: Copy to $label failed: $filename" >&2
        failures=$((failures + 1))
        # temp is the exact path returned by mktemp in this destination.
        rm -f -- "${temp:?}"
    fi
}

local_ready=false
cloud_ready=false
nas_ready=false
prepare_destination "$LOCAL_DIR" && local_ready=true
if [ -d "$GDRIVE_ROOT" ]; then
    prepare_destination "$GDRIVE_DIR" && cloud_ready=true
else
    echo "SKIPPED: Google Drive root unavailable: $GDRIVE_ROOT"
    skipped=$((skipped + 1))
fi
if [ -n "$NAS_SSH_TARGET" ]; then
    echo "NAS archive transport: SSH ($NAS_SSH_TARGET)"
elif [ -d "$NAS_ROOT" ]; then
    prepare_destination "$NAS_DIR" && nas_ready=true
else
    echo "SKIPPED: NAS root unavailable: $NAS_ROOT"
    skipped=$((skipped + 1))
fi

# Materialize the scan so find errors are not hidden in a pipeline subshell.
scan_file=$(mktemp "${TMPDIR:-/tmp}/octop-log-scan.XXXXXX") || exit 1
trap 'rm -f -- "${scan_file:?}"' EXIT
source_dirs=()
if [ -n "$SOURCE_DIR" ]; then
    source_dirs=("$SOURCE_DIR")
else
    shopt -s nullglob
    for inbound in "$HOME"/.octop/agents/*/inbound "$HOME"/.octop/workspaces/*/inbound; do
        [ -d "$inbound" ] && source_dirs+=("$inbound")
    done
    shopt -u nullglob
fi
# Never fall back to scanning all ~/.octop: portable/runtime and user repositories
# can contain millions of files unrelated to uploaded diagnostics.
if [ "${#source_dirs[@]}" -eq 0 ]; then
    echo "No inbound upload directories found; nothing to archive."
else
    if ! find "${source_dirs[@]}" -type f \( -name 'octop_logs_*.zip' -o -name '*_octop_logs_*.zip' \) -print0 > "$scan_file"; then
        echo "ERROR: Diagnostic log scan failed" >&2
        exit 1
    fi
fi
while IFS= read -r -d '' zip_path; do
    filename="${zip_path##*/}"
    found=$((found + 1))
    $local_ready && copy_archive "$zip_path" "$LOCAL_DIR" "local" "$filename"
    $cloud_ready && copy_archive "$zip_path" "$GDRIVE_DIR" "Google Drive" "$filename"
    if [ -n "$NAS_SSH_TARGET" ]; then
        if ssh_result=$(python3 "$SCRIPT_DIR/archive_diagnostic_ssh.py" "$zip_path"); then
            if [ "$ssh_result" = "EXISTING" ]; then
                existing=$((existing + 1))
            else
                copied=$((copied + 1))
                echo "Copied to NAS SSH archive: $filename"
            fi
        else
            echo "ERROR: NAS SSH archive failed: $filename" >&2
            failures=$((failures + 1))
        fi
    else
        $nas_ready && copy_archive "$zip_path" "$NAS_DIR" "NAS" "$filename"
    fi
done < "$scan_file"

echo "Scan finished: found=$found copied=$copied existing=$existing skipped_destinations=$skipped failures=$failures"
echo "Cloud/NAS copies confirm filesystem writes only, not remote replication."
[ "$failures" -eq 0 ]
