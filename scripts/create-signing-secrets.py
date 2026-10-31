#!/usr/bin/env python3
"""Create signing material outside the checkout; never print secret values."""
import argparse
import base64
import os
from pathlib import Path
import secrets
import subprocess


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path.home() / ".local/share/podcast-android/signing")
    args = parser.parse_args()
    output = args.output.expanduser().resolve()
    checkout = Path(__file__).resolve().parents[1]
    if output == checkout or checkout in output.parents:
        parser.error("Choose a directory outside the Git checkout.")
    if output.exists() and any(output.iterdir()):
        parser.error("Output directory is not empty; preserve the existing signing key.")
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    output.chmod(0o700)
    os.umask(0o077)
    password = secrets.token_urlsafe(32)
    signing_env = os.environ | {"NEW_KEYSTORE_PASSWORD": password}
    subprocess.run([
        "keytool", "-genkeypair", "-noprompt", "-storetype", "PKCS12",
        "-keystore", str(output / "release.jks"), "-alias", "podcast-release",
        "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
        "-dname", "CN=Podcast Android Release",
        "-storepass:env", "NEW_KEYSTORE_PASSWORD",
        "-keypass:env", "NEW_KEYSTORE_PASSWORD",
    ], env=signing_env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    values = {
        "ANDROID_KEYSTORE_BASE64": base64.b64encode((output / "release.jks").read_bytes()).decode("ascii"),
        "ANDROID_KEYSTORE_PASSWORD": password,
    }
    for name, value in values.items():
        (output / name).write_text(value, encoding="utf-8")
    print(f"Created release.jks and two Actions-secret value files in {output}")
    print("Keep an encrypted backup of this directory. Do not commit or paste its contents into chat.")


if __name__ == "__main__":
    main()
