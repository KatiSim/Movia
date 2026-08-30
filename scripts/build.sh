#!/data/data/com.termux/files/usr/bin/bash
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
root=$(CDPATH= cd -- "$script_dir/.." && pwd)

if [ "$#" -eq 0 ]; then
  set -- assembleDebug
fi

exec "$root/android/gradlew" -p "$root/android" "$@"
