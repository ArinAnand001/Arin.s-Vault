# Google sign-in and Android signing setup

Vault uses Google Play services `AuthorizationClient` to request the private Drive app-data folder and the user's email/profile. Google identifies this Android app by the pair **package name + signing-certificate SHA-1**.

## Vault Android app identity

- Package name / application ID: `com.anand.vault`
- OAuth client type: **Android**
- Stable release keystore alias: `vault`
- Stable release SHA-1: `52:18:5D:73:70:FD:2C:34:10:34:3B:15:82:02:69:26:C2:4E:4B:37`
- Stable release SHA-256: `25:ED:46:F3:86:0B:C0:2E:96:F1:99:BE:D7:F0:B3:B8:81:D3:06:5F:7C:00:25:56:BB:71:24:77:CB:63:A5:8B`

Preview 1.5's exact signing SHA-1 has been inspected from the APK: `5F:6A:DC:B8:23:8C:F2:69:F3:2E:1A:93:46:23:06:1B:0B:90:66:98`. Add this as a separate Android OAuth client if you need Google sign-in to work on the currently installed Preview 1.5. Use the stable SHA-1 above for future stable builds.

## 1. Register the stable Android OAuth client

1. Open [Google Cloud Console – Google Auth Platform clients](https://console.cloud.google.com/auth/clients).
2. Select the same Google Cloud project used for Vault's Drive API.
3. Choose **Create client** and select **Android**.
4. Enter package name `com.anand.vault`.
5. Enter the stable release SHA-1 shown above and create the client.
6. Make sure the Google Drive API is enabled in this same project and that the OAuth consent screen/audience is configured for the accounts testing the app.

Do not create a Web OAuth client for this on-device flow; the current app uses `AuthorizationClient` directly and does not request a server auth code.

## 2. Add the permanent signing key to GitHub Actions

The keystore and its password are delivered separately to the repository owner. Never commit them to this public repository.

In GitHub, open **Settings → Secrets and variables → Actions → New repository secret** and create:

- `KEYSTORE_BASE64`: the complete contents of the supplied `KEYSTORE_BASE64.txt` file (one long line).
- `KEYSTORE_PASSWORD`: the exact password supplied with the keystore.

The key alias is `vault`. The workflow expects the store password and key password to be the same.

## 3. Build the stable APK

After the two Actions secrets are saved, make a change under `vault-app/` or run the **Vault app** workflow with an empty notification message. The workflow will detect both secrets, sign the APK with the stable key, and publish a non-preview release. Its job summary prints the signing SHA-1 for verification.

## Important: existing Preview 1.5 installation

Preview 1.5 was built with a temporary GitHub runner debug key. The new permanent release key is different, so Android will not install the stable APK as an in-place update over Preview 1.5. Do not uninstall Preview 1.5 until any photos/videos stored only on that phone have been exported or otherwise safely preserved. Once local data is safe, uninstall the preview and install the first stable APK.

## Troubleshooting

- `ApiException: 10` / `DEVELOPER_ERROR`: package name or SHA-1 mismatch, or the Android OAuth client is in a different Cloud project.
- If sign-in opens but Drive access is denied, verify that the Drive API is enabled and the user accepted the `drive.appdata` permission.
- Keep the debug/preview and stable signing fingerprints registered as separate Android OAuth clients if both builds need to sign in.
