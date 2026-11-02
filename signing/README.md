# Signing

Android only installs an update if it's signed with the same key as the installed app.

- **`public-release.jks`** is committed on purpose so every GitHub build is signed with the same key and updates always install over each other. Its password is `kitchtally-public`. Because it's public, anyone could sign an app with it, so only install Kitchtally from this repository's Releases page.
- **Private key (recommended later):** add the four `KITCHTALLY_*` secrets in Settings → Secrets and variables → Actions. Builds then use the private key automatically. Switching keys means uninstalling once, so export a backup first and import it after reinstalling.
