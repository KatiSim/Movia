#!/data/data/com.termux/files/usr/bin/bash
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$script_dir/create-baseline.sh" "$@"
