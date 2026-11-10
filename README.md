# Calculator Vault

A working calculator that hides an encrypted vault for photos, videos and files.

- Type your secret code (4–8 digits), then press **+** to open the vault.
- Long-press **=** to reset a forgotten code with the recovery question.
- Files are encrypted with AES-256 (key protected by the Android Keystore). No internet permission.

## Building
Every push to `main` builds a signed `.aab` (for Google Play) and `.apk` (for testing) with GitHub Actions.
Download them from the **Actions** tab → latest run → **Artifacts**.

Required repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

Privacy policy: `docs/privacy.html` (served with GitHub Pages).
