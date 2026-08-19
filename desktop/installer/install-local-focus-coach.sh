#!/bin/sh

set -eu
umask 077

usage() {
    echo "Usage: $0 --app-image <absolute-path> --extension-id <32-letter-id>" >&2
    exit 2
}

app_image_set=false
extension_id_set=false
while [ "$#" -gt 0 ]; do
    case $1 in
        --app-image)
            [ "$app_image_set" = false ] || usage
            [ "$#" -ge 2 ] || usage
            app_image=$2
            app_image_set=true
            shift 2
            ;;
        --extension-id)
            [ "$extension_id_set" = false ] || usage
            [ "$#" -ge 2 ] || usage
            extension_id=$2
            extension_id_set=true
            shift 2
            ;;
        *)
            usage
            ;;
    esac
done

[ "$app_image_set" = true ] || usage
[ "$extension_id_set" = true ] || usage
case $app_image in
    /*) ;;
    *)
        echo "App image path must be absolute" >&2
        exit 2
        ;;
esac

installer_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
"$installer_directory/validate-extension-id.sh" "$extension_id"

if [ ! -d "$app_image" ]; then
    echo "App image does not exist: $app_image" >&2
    exit 2
fi
app_image=$(CDPATH= cd -- "$app_image" && pwd -P)

service_executable="$app_image/Contents/MacOS/Local Focus Coach Service"
relay_executable="$app_image/Contents/MacOS/Local Focus Coach Relay"
if [ ! -x "$service_executable" ] || [ ! -x "$relay_executable" ]; then
    echo "App image is missing the packaged service or relay launcher" >&2
    exit 2
fi

launch_agents_directory="$HOME/Library/LaunchAgents"
native_hosts_directory="$HOME/Library/Application Support/Google/Chrome/NativeMessagingHosts"
app_support_directory="$HOME/Library/Application Support/Local Focus Coach"
logs_directory="$app_support_directory/logs"
plist="$launch_agents_directory/com.localfocuscoach.strict-service.plist"
manifest="$native_hosts_directory/com.localfocuscoach.strict_mode.json"
receipt="$app_support_directory/.installer-registration-receipt"

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

managed_install=false
if [ -e "$receipt" ] || [ -L "$receipt" ]; then
    if [ ! -f "$receipt" ] || [ -L "$receipt" ]; then
        echo "Refusing to replace an invalid installer receipt: $receipt" >&2
        exit 1
    fi
    old_plist_hash=$(receipt_value plist_sha256)
    old_manifest_hash=$(receipt_value manifest_sha256)
    if ! valid_hash "$old_plist_hash" || ! valid_hash "$old_manifest_hash" \
        || [ ! -f "$plist" ] || [ -L "$plist" ] \
        || [ ! -f "$manifest" ] || [ -L "$manifest" ] \
        || [ "$(file_hash "$plist")" != "$old_plist_hash" ] \
        || [ "$(file_hash "$manifest")" != "$old_manifest_hash" ]; then
        echo "Refusing to replace registrations not owned by this installer" >&2
        exit 1
    fi
    managed_install=true
elif [ -e "$plist" ] || [ -L "$plist" ] || [ -e "$manifest" ] || [ -L "$manifest" ]; then
    echo "Refusing to overwrite an existing LaunchAgent or native host registration" >&2
    exit 1
fi

mkdir -p "$launch_agents_directory" "$native_hosts_directory" "$logs_directory"
chmod 700 "$app_support_directory" "$logs_directory"

temporary_plist=$(mktemp "$launch_agents_directory/.com.localfocuscoach.strict-service.XXXXXX")
temporary_manifest=$(mktemp "$native_hosts_directory/.com.localfocuscoach.strict_mode.XXXXXX")
temporary_receipt=$(mktemp "$app_support_directory/.installer-registration-receipt.XXXXXX")
backup_plist=
backup_manifest=
backup_receipt=
cleanup_temporaries() {
    rm -f "$temporary_plist" "$temporary_manifest" "$temporary_receipt"
    if [ -n "$backup_plist" ]; then
        rm -f "$backup_plist"
    fi
    if [ -n "$backup_manifest" ]; then
        rm -f "$backup_manifest"
    fi
    if [ -n "$backup_receipt" ]; then
        rm -f "$backup_receipt"
    fi
}
trap cleanup_temporaries EXIT HUP INT TERM

cp "$installer_directory/com.localfocuscoach.strict-service.plist.template" "$temporary_plist"
plutil -replace ProgramArguments.0 -string "$service_executable" "$temporary_plist"
plutil -replace StandardOutPath -string "$logs_directory/strict-service.stdout.log" "$temporary_plist"
plutil -replace StandardErrorPath -string "$logs_directory/strict-service.stderr.log" "$temporary_plist"
plutil -lint "$temporary_plist" >/dev/null

json_string() {
    osascript -l JavaScript \
        -e 'function run(args) { return JSON.stringify(args[0]); }' \
        -- "$1"
}

sed_replacement() {
    sed 's/[\\&|]/\\&/g'
}

relay_json=$(json_string "$relay_executable" | sed_replacement)
origin_json=$(json_string "chrome-extension://$extension_id/" | sed_replacement)
sed \
    -e "s|\"__RELAY_EXECUTABLE__\"|$relay_json|" \
    -e "s|\"__ALLOWED_ORIGIN__\"|$origin_json|" \
    "$installer_directory/com.localfocuscoach.strict_mode.json.template" \
    >"$temporary_manifest"

chmod 600 "$temporary_plist" "$temporary_manifest"
plist_hash=$(file_hash "$temporary_plist")
manifest_hash=$(file_hash "$temporary_manifest")
printf '%s\n' \
    'version=1' \
    "plist_sha256=$plist_hash" \
    "manifest_sha256=$manifest_hash" \
    >"$temporary_receipt"
chmod 600 "$temporary_receipt"

user_domain="gui/$(id -u)"
if [ "$managed_install" = true ]; then
    backup_plist=$(mktemp "$launch_agents_directory/.com.localfocuscoach.strict-service.backup.XXXXXX")
    backup_manifest=$(mktemp "$native_hosts_directory/.com.localfocuscoach.strict_mode.backup.XXXXXX")
    backup_receipt=$(mktemp "$app_support_directory/.installer-registration-receipt.backup.XXXXXX")
    cp -p "$plist" "$backup_plist"
    cp -p "$manifest" "$backup_manifest"
    cp -p "$receipt" "$backup_receipt"
    launchctl bootout "$user_domain" "$plist" >/dev/null 2>&1 || true
fi
mv -f "$temporary_plist" "$plist"
mv -f "$temporary_manifest" "$manifest"
mv -f "$temporary_receipt" "$receipt"
if ! launchctl bootstrap "$user_domain" "$plist"; then
    if [ "$managed_install" = true ]; then
        rollback_ok=true
        mv -f "$backup_plist" "$plist" || rollback_ok=false
        mv -f "$backup_manifest" "$manifest" || rollback_ok=false
        mv -f "$backup_receipt" "$receipt" || rollback_ok=false
        if [ "$rollback_ok" = true ]; then
            launchctl bootstrap "$user_domain" "$plist" || rollback_ok=false
        fi
        if [ "$rollback_ok" = false ]; then
            echo "Unable to restore the previous Local Focus Coach LaunchAgent" >&2
        fi
    else
        rm -f "$plist" "$manifest" "$receipt"
    fi
    echo "Unable to bootstrap the Local Focus Coach LaunchAgent" >&2
    exit 1
fi

cleanup_temporaries
trap - EXIT HUP INT TERM
echo "Installed Local Focus Coach registrations for extension $extension_id"
