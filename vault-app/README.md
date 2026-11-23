# Vault Android project

This directory contains the extracted Android application source. The original source archive is retained at the repository root as `Vault-app.zip`.

## Build

The GitHub Actions workflow at `.github/workflows/build-apk.yml` builds this project and publishes a preview APK when no private release signing key is configured. A stable release requires the `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD` repository Actions secrets.

Package name: `com.anand.vault`
