#!/bin/sh

set -eu

installer_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
validation_script="$installer_directory/validate-extension-id.sh"
install_script="$installer_directory/install-local-focus-coach.sh"
uninstall_script="$installer_directory/uninstall-local-focus-coach.sh"

expect_status() {
    expected_status=$1
    shift

    set +e
    "$@" >/dev/null 2>&1
    actual_status=$?
    set -e

    if [ "$actual_status" -ne "$expected_status" ]; then
        echo "Expected exit status $expected_status, got $actual_status: $*" >&2
        exit 1
    fi
}

expect_status 2 "$validation_script" abc
expect_status 0 "$validation_script" aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
expect_status 0 "$validation_script" pppppppppppppppppppppppppppppppp
expect_status 2 "$validation_script"
expect_status 2 "$validation_script" aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa extra
expect_status 2 "$validation_script" aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
expect_status 2 "$validation_script" aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
expect_status 2 "$validation_script" qaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
expect_status 2 "$validation_script" Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
expect_status 2 "$validation_script" 0aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

test_root=$(mktemp -d "${TMPDIR:-/tmp}/local-focus-coach-installer-test.XXXXXX")
trap 'rm -rf "$test_root"' EXIT HUP INT TERM

test_home="$test_root/home"
mock_bin="$test_root/bin"
launchctl_log="$test_root/launchctl.log"
fail_next_bootstrap="$test_root/fail-next-bootstrap"
app_image="$test_root/Local & \"Focus\" Coach.app"
mkdir -p "$test_home" "$mock_bin" "$app_image/Contents/MacOS"
touch "$launchctl_log"
touch "$app_image/Contents/MacOS/Local Focus Coach Service"
touch "$app_image/Contents/MacOS/Local Focus Coach Relay"
chmod +x "$app_image/Contents/MacOS/Local Focus Coach Service"
chmod +x "$app_image/Contents/MacOS/Local Focus Coach Relay"
canonical_app_image=$(CDPATH= cd -- "$app_image" && pwd -P)

printf '%s\n' \
    '#!/bin/sh' \
    'printf "%s\\n" "$*" >> "$LFC_LAUNCHCTL_LOG"' \
    'if [ "$1" = bootstrap ] && [ -f "$LFC_FAIL_NEXT_BOOTSTRAP" ]; then' \
    '    rm -f "$LFC_FAIL_NEXT_BOOTSTRAP"' \
    '    exit 1' \
    'fi' \
    >"$mock_bin/launchctl"
chmod +x "$mock_bin/launchctl"

run_with_test_home() {
    HOME="$test_home" \
        LFC_LAUNCHCTL_LOG="$launchctl_log" \
        LFC_FAIL_NEXT_BOOTSTRAP="$fail_next_bootstrap" \
        PATH="$mock_bin:$PATH" \
        "$@"
}

expect_equal() {
    expected=$1
    actual=$2
    message=$3
    if [ "$actual" != "$expected" ]; then
        echo "$message: expected '$expected', got '$actual'" >&2
        exit 1
    fi
}

expect_status 2 run_with_test_home "$install_script" \
    --app-image "relative.app" \
    --development-extension-id aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
expect_equal "" "$(cat "$launchctl_log")" "Invalid input must not call launchctl"
expect_status 2 run_with_test_home "$install_script" \
    --app-image "$app_image" \
    --development-extension-id qaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
expect_equal "" "$(cat "$launchctl_log")" "Invalid extension ID must not call launchctl"
expect_status 2 run_with_test_home "$install_script" \
    --app-image "$app_image" \
    --extension-id aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
expect_equal "" "$(cat "$launchctl_log")" "Legacy ambiguous identity input must not call launchctl"

extension_id=abcdefghijklmnopabcdefghijklmnop
touch "$fail_next_bootstrap"
expect_status 1 run_with_test_home "$install_script" \
    --app-image "$app_image" \
    --development-extension-id "$extension_id"

plist="$test_home/Library/LaunchAgents/com.localfocuscoach.strict-service.plist"
manifest="$test_home/Library/Application Support/Google/Chrome/NativeMessagingHosts/com.localfocuscoach.strict_mode_dev.json"
receipt="$test_home/Library/Application Support/Local Focus Coach/.installer-registration-receipt"
test ! -e "$plist"
test ! -e "$manifest"
test ! -e "$receipt"

run_with_test_home "$install_script" \
    --app-image "$app_image" \
    --development-extension-id "$extension_id"

test -f "$plist"
test -f "$manifest"
test -f "$receipt"
expect_equal "600" "$(stat -f '%Lp' "$plist")" "LaunchAgent permissions"
expect_equal "600" "$(stat -f '%Lp' "$manifest")" "Native host permissions"
expect_equal "600" "$(stat -f '%Lp' "$receipt")" "Installer receipt permissions"
expect_equal "com.localfocuscoach.strict-service" \
    "$(plutil -extract Label raw -o - "$plist")" \
    "LaunchAgent label"
expect_equal "$canonical_app_image/Contents/MacOS/Local Focus Coach Service" \
    "$(plutil -extract ProgramArguments.0 raw -o - "$plist")" \
    "LaunchAgent executable"
expect_equal "true" \
    "$(plutil -extract RunAtLoad raw -o - "$plist")" \
    "LaunchAgent RunAtLoad"
expect_equal "true" \
    "$(plutil -extract KeepAlive raw -o - "$plist")" \
    "LaunchAgent KeepAlive"
expect_equal "com.localfocuscoach.strict_mode_dev" \
    "$(node -e 'const value = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")); process.stdout.write(value.name)' "$manifest")" \
    "Native host name"
expect_equal "$canonical_app_image/Contents/MacOS/Local Focus Coach Relay" \
    "$(node -e 'const value = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")); process.stdout.write(value.path)' "$manifest")" \
    "Native host executable"
expect_equal "stdio" \
    "$(node -e 'const value = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")); process.stdout.write(value.type)' "$manifest")" \
    "Native host transport"
expect_equal "[\"chrome-extension://$extension_id/\"]" \
    "$(node -e 'const value = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")); process.stdout.write(JSON.stringify(value.allowed_origins))' "$manifest")" \
    "Native host allowed origins"

user_id=$(id -u)
expect_equal "bootstrap gui/$user_id $plist" \
    "$(tail -n 1 "$launchctl_log")" \
    "LaunchAgent bootstrap"

old_plist_hash=$(shasum -a 256 "$plist" | awk '{print $1}')
old_manifest_hash=$(shasum -a 256 "$manifest" | awk '{print $1}')
old_receipt_hash=$(shasum -a 256 "$receipt" | awk '{print $1}')
touch "$fail_next_bootstrap"
expect_status 1 run_with_test_home "$install_script" \
    --app-image "$app_image" \
    --development-extension-id aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
expect_equal "$old_plist_hash" \
    "$(shasum -a 256 "$plist" | awk '{print $1}')" \
    "Failed reinstall must restore the previous LaunchAgent"
expect_equal "$old_manifest_hash" \
    "$(shasum -a 256 "$manifest" | awk '{print $1}')" \
    "Failed reinstall must restore the previous native host manifest"
expect_equal "$old_receipt_hash" \
    "$(shasum -a 256 "$receipt" | awk '{print $1}')" \
    "Failed reinstall must restore the previous ownership receipt"
expect_equal "bootout gui/$user_id $plist
bootstrap gui/$user_id $plist
bootstrap gui/$user_id $plist" \
    "$(tail -n 3 "$launchctl_log")" \
    "Failed reinstall must re-bootstrap the restored LaunchAgent"

touch "$test_home/Library/LaunchAgents/keep-me.plist"
touch "$(dirname "$manifest")/keep-me.json"
run_with_test_home "$uninstall_script"

test ! -e "$plist"
test ! -e "$manifest"
test ! -e "$receipt"
test -e "$test_home/Library/LaunchAgents/keep-me.plist"
test -e "$(dirname "$manifest")/keep-me.json"
test -d "$app_image"
expect_equal "bootout gui/$user_id $plist" \
    "$(tail -n 1 "$launchctl_log")" \
    "LaunchAgent bootout"

run_with_test_home "$install_script" \
    --app-image "$app_image" \
    --development-extension-id "$extension_id"
printf '%s\n' '{"name":"foreign.registration"}' >"$manifest"
run_with_test_home "$uninstall_script"
test ! -e "$plist"
test -e "$manifest"
test ! -e "$receipt"
expect_equal '{"name":"foreign.registration"}' \
    "$(cat "$manifest")" \
    "Uninstaller must preserve a changed registration"
launchctl_calls=$(wc -l <"$launchctl_log" | tr -d ' ')
expect_status 1 run_with_test_home "$install_script" \
    --app-image "$app_image" \
    --development-extension-id "$extension_id"
expect_equal "$launchctl_calls" \
    "$(wc -l <"$launchctl_log" | tr -d ' ')" \
    "Refusing a foreign registration must not call launchctl"
expect_equal '{"name":"foreign.registration"}' \
    "$(cat "$manifest")" \
    "Installer must not overwrite a foreign registration"
run_with_test_home "$uninstall_script"
test -e "$manifest"

rm -f "$manifest"
production_identity="$test_root/production-extension-identity.json"
printf '%s\n' \
    "{\"version\":1,\"channel\":\"production\",\"extensionId\":\"$extension_id\",\"nativeHostName\":\"com.localfocuscoach.strict_mode\"}" \
    >"$production_identity"
run_with_test_home "$install_script" \
    --app-image "$app_image" \
    --production-identity-file "$production_identity"
production_manifest="$test_home/Library/Application Support/Google/Chrome/NativeMessagingHosts/com.localfocuscoach.strict_mode.json"
test -f "$production_manifest"
expect_equal "com.localfocuscoach.strict_mode" \
    "$(node -e 'const value = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")); process.stdout.write(value.name)' "$production_manifest")" \
    "Production native host name"
expect_equal "[\"chrome-extension://$extension_id/\"]" \
    "$(node -e 'const value = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")); process.stdout.write(JSON.stringify(value.allowed_origins))' "$production_manifest")" \
    "Production native host allowed origin"
run_with_test_home "$uninstall_script"
test ! -e "$production_manifest"

printf '%s\n' \
    "{\"version\":1,\"channel\":\"production\",\"extensionId\":\"$extension_id\",\"nativeHostName\":\"com.localfocuscoach.strict_mode_dev\"}" \
    >"$production_identity"
expect_status 2 run_with_test_home "$install_script" \
    --app-image "$app_image" \
    --production-identity-file "$production_identity"

echo "Installer tests passed"
