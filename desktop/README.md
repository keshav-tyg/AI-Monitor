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
will use `jpackage` to bundle a runtime in the macOS app image, so end users
will not need a separate Java installation.
