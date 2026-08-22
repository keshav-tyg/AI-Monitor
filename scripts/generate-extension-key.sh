#!/bin/sh
# Generate the RSA identity for a production Chrome Web Store submission.
#
# The private key stays on your machine. Treat it like an ssh key: never commit
# it, never paste it into a service, keep an offline backup somewhere your
# password manager or backup drive covers.
#
# The manifest reads LFC_EXTENSION_PUBLIC_KEY at build time and derives Chrome's
# stable extension ID from it. Using the same private key for every release
# keeps the same ID across versions, which is what the store expects.
#
# Usage: scripts/generate-extension-key.sh <output-directory>
#   e.g. scripts/generate-extension-key.sh ~/.local/share/local-focus-coach
#
# The directory must not exist yet; the script refuses to overwrite in place so
# a prior key is never silently replaced.

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
echo "  $private_pem   (keep this secret)"
echo "  $public_der"
echo "  $public_b64    (paste this into LFC_EXTENSION_PUBLIC_KEY)"
echo
echo "To build for release:"
echo "  export LFC_EXTENSION_PUBLIC_KEY=\"\$(cat \"$public_b64\")\""
echo "  npm run build:production"
echo
echo "Verify the derived extension ID by loading dist/ unpacked and comparing"
echo "the ID Chrome shows against the one you register with the Chrome Web"
echo "Store. Every future release must build with the same key or the ID"
echo "changes and users lose their local state."
