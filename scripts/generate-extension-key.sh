#!/bin/sh
# Generate a local RSA keypair for advanced development scenarios.
#
# You almost certainly do NOT need this to publish. The Chrome Web Store
# assigns the extension's public key and 32-character ID on the first upload
# of a new listing — a manifest that ships its own `key` field is rejected.
# The RELEASE.md runbook uses the CWS-assigned key and never generates one.
#
# This script exists for two edge cases:
#
# 1. **Self-hosted distribution.** If you ever want to ship the extension as
#    an unlisted `.crx` outside CWS, the CRX signature has to come from a key
#    you control. This script produces one.
#
# 2. **Reproducing an as-yet-unknown extension ID before the first CWS
#    upload.** Load an unpacked extension with this key baked into the
#    manifest and Chrome derives a stable ID from it. That ID has no
#    relationship to what CWS will eventually assign — the store picks a new
#    key on your first upload — so this is really only useful when you plan
#    to skip CWS.
#
# For the normal CWS release flow, `~/.local/share/local-focus-coach/cws/`
# holds the store-assigned public key and ID, populated per RELEASE.md.
#
# Usage: scripts/generate-extension-key.sh <output-directory>
#   e.g. scripts/generate-extension-key.sh ~/.local/share/local-focus-coach/self-hosted
#
# The directory must not exist yet; the script refuses to overwrite in place
# so a prior key is never silently replaced.

set -eu
umask 077

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <output-directory>" >&2
    exit 2
fi

output_dir=$1
if [ -e "$output_dir" ]; then
    echo "Refusing to write into an existing path: $output_dir" >&2
    echo "Move or delete it first, or pass a new directory." >&2
    exit 1
fi

mkdir -p "$output_dir"
chmod 700 "$output_dir"

private_pem="$output_dir/extension-private-key.pem"
public_der="$output_dir/extension-public-key.der"
public_b64="$output_dir/extension-public-key.b64"

# 2048-bit RSA. Chrome accepts 1024–3072 for extension identity; 2048 is the
# common recommendation and matches modern practice.
openssl genpkey \
    -algorithm RSA \
    -pkeyopt rsa_keygen_bits:2048 \
    -out "$private_pem"
chmod 600 "$private_pem"

# The manifest wants the SubjectPublicKeyInfo in DER, base64-encoded, on a
# single line. Vite reads the env var and validates canonical encoding.
openssl rsa -in "$private_pem" -pubout -outform DER -out "$public_der" 2>/dev/null
base64 < "$public_der" | tr -d '\n' > "$public_b64"
printf '\n' >> "$public_b64"

echo "Wrote:"
echo "  $private_pem   (keep this secret; needed only for self-hosting)"
echo "  $public_der"
echo "  $public_b64    (set as LFC_EXTENSION_PUBLIC_KEY for a local keyed build)"
echo
echo "To load an unpacked extension pinned to the ID this key derives:"
echo "  export LFC_EXTENSION_PUBLIC_KEY=\"\$(cat \"$public_b64\")\""
echo "  LFC_EXTENSION_CHANNEL=production npm run build:production"
echo
echo "That build is for local iteration only. Uploading it to the Chrome Web"
echo "Store is rejected — CWS demands a keyless manifest for a new listing."
