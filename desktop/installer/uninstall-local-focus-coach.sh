#!/bin/sh

set -eu

launch_agents_directory="$HOME/Library/LaunchAgents"
native_hosts_directory="$HOME/Library/Application Support/Google/Chrome/NativeMessagingHosts"
app_support_directory="$HOME/Library/Application Support/Local Focus Coach"
plist="$launch_agents_directory/com.localfocuscoach.strict-service.plist"
receipt="$app_support_directory/.installer-registration-receipt"

if [ ! -e "$receipt" ] && [ ! -L "$receipt" ]; then
    echo "No Local Focus Coach installer registrations found"
    exit 0
fi
if [ ! -f "$receipt" ] || [ -L "$receipt" ]; then
    echo "Refusing to use an invalid installer receipt: $receipt" >&2
    exit 1
fi

file_hash() {
    shasum -a 256 "$1" | awk '{print $1}'
}

receipt_value() {
    key=$1
    awk -F= -v wanted="$key" '$1 == wanted { print substr($0, length($1) + 2); exit }' "$receipt"
}

valid_hash() {
    [ "${#1}" -eq 64 ] || return 1
    case $1 in
        *[!0-9a-f]*) return 1 ;;
    esac
}

plist_hash=$(receipt_value plist_sha256)
manifest_hash=$(receipt_value manifest_sha256)
native_host_name=$(receipt_value native_host_name)
case $native_host_name in
    com.localfocuscoach.strict_mode|com.localfocuscoach.strict_mode_dev) ;;
    *)
        echo "Refusing to use a malformed installer receipt: $receipt" >&2
        exit 1
        ;;
esac
manifest="$native_hosts_directory/$native_host_name.json"
if ! valid_hash "$plist_hash" || ! valid_hash "$manifest_hash"; then
    echo "Refusing to use a malformed installer receipt: $receipt" >&2
    exit 1
fi

if [ -f "$plist" ] && [ ! -L "$plist" ] && [ "$(file_hash "$plist")" = "$plist_hash" ]; then
    launchctl bootout "gui/$(id -u)" "$plist" >/dev/null 2>&1 || true
    rm -f "$plist"
elif [ -e "$plist" ] || [ -L "$plist" ]; then
    echo "Preserved changed LaunchAgent registration: $plist" >&2
fi

if [ -f "$manifest" ] && [ ! -L "$manifest" ] \
    && [ "$(file_hash "$manifest")" = "$manifest_hash" ]; then
    rm -f "$manifest"
elif [ -e "$manifest" ] || [ -L "$manifest" ]; then
    echo "Preserved changed native host registration: $manifest" >&2
fi

rm -f "$receipt"
echo "Removed Local Focus Coach registrations created by this installer"
