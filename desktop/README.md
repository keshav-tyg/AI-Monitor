# Local Focus Coach desktop workspace

This directory contains the Java workspace for the macOS companion app. It is
split into five Gradle modules:

- `strict-core`: shared strict-mode domain code.
- `strict-store`: persistent storage for strict-mode data.
- `strict-service`: background service coordination.
- `strict-relay`: local relay integration.
- `strict-dashboard`: desktop dashboard presentation.

## Local setup

Install a Java 21 JDK before running Gradle. This is a required local
prerequisite; the workspace does not install a JDK or modify system software.
On 2026-08-18, this Mac's prerequisite check reported that no Java runtime was
installed.
Confirm it is available with:

```sh
java -version
```

Then run the complete test suite from this directory:

```sh
./gradlew test
```

To run the initial workspace smoke test and build every module:

```sh
./gradlew :strict-core:test build
```

The Gradle toolchain requests Java 21 for compilation. A later packaging step
uses `jpackage` to bundle a private Java 21 runtime in the macOS app image, so
end users do not need a separate Java installation.

## Build the macOS app image

With Homebrew Java 21 installed, create the local app image from this directory:

```sh
JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
  ./gradlew jpackage
```

The task writes `build/jpackage/Local Focus Coach.app`. It includes the JavaFX
dashboard, background service, Chrome Native Messaging relay, their runtime
dependencies, and a private Java 21 runtime with the desktop modules. The image
is a local, ad-hoc-signed development build; it is not Developer ID signed or
notarized, and this version does not provide automatic updates.

## Register the per-user companion

Build or copy the app image to its permanent location before registering it.
Then pass its absolute path and the production identity metadata emitted by
`npm run build:production`:

```sh
./installer/install-local-focus-coach.sh \
  --app-image "$PWD/build/jpackage/Local Focus Coach.app" \
  --production-identity-file ../dist/production-extension-identity.json
```

The production extension build requires `LFC_EXTENSION_PUBLIC_KEY`, containing
the canonical base64 DER SubjectPublicKeyInfo for the release identity. Its
matching private key remains outside the repository in protected release
infrastructure. The build embeds only the public key, derives Chrome's stable
ID, and emits the metadata file consumed above. No production key is included
in this source tree.

A plain `npm run build` is a development build with no manifest key. Its
unpacked ID is not stable across machines. Register that copied local ID only
with the explicitly separate development host:

```sh
./installer/install-local-focus-coach.sh \
  --app-image "$PWD/build/jpackage/Local Focus Coach.app" \
  --development-extension-id abcdefghijklmnopabcdefghijklmnop
```

The installer requires no administrator access. It creates only a user
LaunchAgent at
`~/Library/LaunchAgents/com.localfocuscoach.strict-service.plist` and Chrome's
user Native Messaging host manifest at
`~/Library/Application Support/Google/Chrome/NativeMessagingHosts/com.localfocuscoach.strict_mode.json`.
The LaunchAgent runs the packaged service at login and restarts it after an
unexpected exit. The production host is `com.localfocuscoach.strict_mode`; the
development host is `com.localfocuscoach.strict_mode_dev`. Each manifest
permits exactly its own extension ID, and the installer does not accept a raw
ID for production.

The app image path is recorded as an absolute path in both registrations, so
do not move the app image afterward. To remove only the registrations created
by this installer, run:

```sh
./installer/uninstall-local-focus-coach.sh
```

Uninstall does not delete the app image, session database, install secret,
logs, or unrelated LaunchAgent/native-host files. A registration changed after
installation is preserved rather than assumed to be installer-owned.
