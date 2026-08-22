# Distributing Local Focus Coach for macOS

This document is for people who publish a release build. End-user install
instructions live at the bottom, so you can paste them into a download page
without reading the packaging section.

## What ships

One artifact: `Local Focus Coach.app`, produced by

```sh
cd desktop && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew jpackage
```

The `jpackage` task now `finalizedBy("bundleInstaller")`, so the resulting
`.app` already contains the installer scripts at
`Contents/Resources/installer/`. A person who downloads the `.app` needs
nothing else from this repository.

## Signing

Skipped by choice — no Developer ID identity is used. The `.app` is
ad-hoc-signed by `jpackage`. That has three consequences you must be honest
about on the download page:

- **First launch is blocked by Gatekeeper.** The user must right-click the
  `.app` in Finder and choose Open the first time, then confirm the dialog.
  Double-clicking silently fails.
- **The quarantine attribute must be cleared** if the `.app` was downloaded
  via a browser or unarchived from a `.zip`. Otherwise macOS refuses to run
  its embedded launchers. `xattr -c "/Applications/Local Focus Coach.app"`
  clears it. The install instruction below folds this into one command.
- **No auto-updates.** Every release is a manual re-download. Explicit in the
  privacy policy and reasonable for a local-first tool.

## How to prepare a release

The full step-by-step lives in [`../RELEASE.md`](../RELEASE.md) — this section
is a quick reference for the `.app` half only. The one prerequisite is that
the Chrome Web Store listing already exists and you have saved its assigned
extension ID somewhere the shell can read; the RELEASE runbook writes it to
`~/.local/share/local-focus-coach/cws/extension-id`.

1. Emit the identity file the `.app` needs to bundle:

   ```sh
   export LFC_EXTENSION_ID="$(cat ~/.local/share/local-focus-coach/cws/extension-id)"
   LFC_EXTENSION_CHANNEL=production npm run build:production
   ```

   `dist/production-extension-identity.json` now names the store-assigned ID.
   The extension bundle in `dist/` is keyless — safe to upload to CWS as-is.

2. Build the `.app`:

   ```sh
   cd desktop && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew jpackage
   ```

   The output is `desktop/build/jpackage/Local Focus Coach.app`.

3. Copy the identity file into the `.app` so users don't have to hunt for it:

   ```sh
   cp ../dist/production-extension-identity.json \
      "build/jpackage/Local Focus Coach.app/Contents/Resources/production-extension-identity.json"
   ```

4. Zip the `.app` for distribution — Finder → File → Compress. The DMG route
   is optional and adds no security you get from the zip.

5. Publish the `.zip`, this document, and the privacy policy at a stable URL.
   Link both from the Chrome Web Store listing.

## User-facing install instructions

Paste this section, verbatim, onto the download page.

---

### Install Local Focus Coach for macOS

1. Download `Local Focus Coach.zip` and unarchive it. Drag the resulting
   `Local Focus Coach.app` into your `Applications` folder.

2. **Right-click `Local Focus Coach.app` in Finder and choose Open.** A
   Gatekeeper dialog appears once — click Open again to confirm.
   *Double-clicking silently fails* because the app is signed for local
   distribution rather than the App Store. This step only happens the first
   time.

   The first launch registers the Chrome native-messaging host for you —
   no Terminal required. From now on Local Focus Coach reopens itself when
   you log in, and you can launch it from Spotlight or the Dock.

3. Install the Local Focus Coach extension from the Chrome Web Store.

4. Open the app, enable a rule, set a session budget. Chrome will pick that
   up on the next visit to Reels / Shorts / the X timeline — you do not need
   to reload anything. Until you enable a rule, the extension enforces
   nothing; that is by design.

#### Troubleshooting: manual install (fallback)

If the extension cannot reach the app after the first launch — for example
because Gatekeeper blocked the app before it finished starting, or the
download quarantine attribute prevented the bundled installer from running —
open Terminal and run:

```sh
xattr -cr "/Applications/Local Focus Coach.app" \
  && "/Applications/Local Focus Coach.app/Contents/Resources/installer/install-local-focus-coach.sh" \
       --app-image "/Applications/Local Focus Coach.app" \
       --production-identity-file "/Applications/Local Focus Coach.app/Contents/Resources/production-extension-identity.json"
```

Then relaunch the app. The bootstrap logs its progress to
`~/Library/Application Support/Local Focus Coach/logs/first-run-bootstrap.log`
if you want to see what happened.

To uninstall the native-messaging registration but keep the app:

```sh
"/Applications/Local Focus Coach.app/Contents/Resources/installer/uninstall-local-focus-coach.sh"
```

To remove everything, delete the `.app` from `Applications` after running
the uninstall command above.

---

## What is deliberately not here

- **Sparkle / any auto-updater.** Skipping until there is a real update
  cadence to justify the code.
- **Notarization.** Skipping until we join the Apple Developer Program.
