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

1. Build the extension for production. Set `LFC_EXTENSION_PUBLIC_KEY` to the
   canonical base64 of your production DER SubjectPublicKeyInfo (see
   `scripts/generate-extension-key.sh`). Then:

   ```sh
   npm run build:production
   ```

   `dist/production-extension-identity.json` is written alongside the build.
   Keep it — the installer needs it to lock the native-host manifest to your
   exact production extension ID.

2. Build the `.app`:

   ```sh
   cd desktop && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew jpackage
   ```

   The output is `desktop/build/jpackage/Local Focus Coach.app`.

3. Copy the identity file into the `.app` so users don't have to hunt for it:

   ```sh
   mkdir -p "desktop/build/jpackage/Local Focus Coach.app/Contents/Resources"
   cp dist/production-extension-identity.json \
      "desktop/build/jpackage/Local Focus Coach.app/Contents/Resources/production-extension-identity.json"
   ```

4. Zip the `.app` for distribution — Finder → File → Compress. The DMG route
   is optional and adds no security you get from the zip.

5. Publish the `.zip`, this document, and `docs/privacy-policy.md` at a
   stable URL. Link both from the Chrome Web Store listing.

## User-facing install instructions

Paste this section, verbatim, onto the download page.

---

### Install Local Focus Coach for macOS

1. Download `Local Focus Coach.zip` and unarchive it. Drag the resulting
   `Local Focus Coach.app` into your `Applications` folder.

2. Open Terminal and run this command. It clears the download quarantine and
   registers the native-messaging host so Chrome can talk to the app:

   ```sh
   xattr -cr "/Applications/Local Focus Coach.app" \
     && "/Applications/Local Focus Coach.app/Contents/Resources/installer/install-local-focus-coach.sh" \
          --app-image "/Applications/Local Focus Coach.app" \
          --production-identity-file "/Applications/Local Focus Coach.app/Contents/Resources/production-extension-identity.json"
   ```

3. Right-click `Local Focus Coach.app` in Finder → Open → Open. macOS will
   ask once; after that it launches normally from Spotlight or the Dock.

4. Install the Local Focus Coach extension from the Chrome Web Store.

5. Open the app to set your rules. That is all — Chrome will pick them up on
   the next feed visit.

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
- **A first-launch bootstrap that runs the installer automatically.** The
  install script does the same work as the future bootstrap would, and
  shell is easier to reason about than early-startup Java that has to
  refuse to break the app if it fails. Track that as a follow-up.
