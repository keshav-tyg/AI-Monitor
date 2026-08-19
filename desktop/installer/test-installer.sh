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
    >"$mock_bin/launchctl"
chmod +x "$mock_bin/launchctl"

run_with_test_home() {
    HOME="$test_home" \
        LFC_LAUNCHCTL_LOG="$launchctl_log" \
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
    --extension-id aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
expect_equal "" "$(cat "$launchctl_log")" "Invalid input must not call launchctl"
expect_status 2 run_with_test_home "$install_script" \
    --app-image "$app_image" \
    --extension-id qaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
expect_equal "" "$(cat "$launchctl_log")" "Invalid extension ID must not call launchctl"

extension_id=abcdefghijklmnopabcdefghijklmnop
run_with_test_home "$install_script" \
    --app-image "$app_image" \
    --extension-id "$extension_id"

plist="$test_home/Library/LaunchAgents/com.localfocuscoach.strict-service.plist"
manifest="$test_home/Library/Application Support/Google/Chrome/NativeMessagingHosts/com.localfocuscoach.strict_mode.json"
receipt="$test_home/Library/Application Support/Local Focus Coach/.installer-registration-receipt"

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
expect_equal "com.localfocuscoach.strict_mode" \
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
    --extension-id "$extension_id"
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
    --extension-id "$extension_id"
expect_equal "$launchctl_calls" \
    "$(wc -l <"$launchctl_log" | tr -d ' ')" \
    "Refusing a foreign registration must not call launchctl"
expect_equal '{"name":"foreign.registration"}' \
    "$(cat "$manifest")" \
    "Installer must not overwrite a foreign registration"
run_with_test_home "$uninstall_script"
test -e "$manifest"

echo "Installer tests passed"
