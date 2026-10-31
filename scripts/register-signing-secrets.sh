#!/usr/bin/env bash
# Run with a local GitHub CLI login allowed to manage this repository's secrets.
set -euo pipefail
signing_dir="${1:?Usage: register-signing-secrets.sh /path/to/signing-directory}"
for name in ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD; do
  [[ -s "$signing_dir/$name" ]] || { echo "Missing secret file: $name" >&2; exit 1; }
done
for name in ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD; do
  gh secret set "$name" --repo sasakiy84/podcast-android < "$signing_dir/$name"
done
