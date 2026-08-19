#!/bin/sh

set -eu
LC_ALL=C
export LC_ALL

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <32-letter Chrome extension ID>" >&2
    exit 2
fi

extension_id=$1
if [ "${#extension_id}" -ne 32 ]; then
    echo "Extension ID must contain exactly 32 lowercase letters from a through p" >&2
    exit 2
fi

case $extension_id in
    *[!a-p]*)
        echo "Extension ID must contain exactly 32 lowercase letters from a through p" >&2
        exit 2
        ;;
esac
