
# GallerySync – FAQ & Privacy Architecture

> **Security & Privacy Disclosure:**  
> GallerySync uploads your photos and videos **directly to your user-configured Telegram bot and private channel** without intermediary servers or third-party tracking.  
> However, ordinary Telegram Bot API channel uploads are **NOT end-to-end encrypted**. Telegram processes and stores this media on their servers, and media is accessible to anyone who has access to your private channel.  
> Please review this document and Telegram's official privacy terms to understand how your data is handled.

---

## 📌 Table of Contents

22. [Are uploads end-to-end encrypted?](#q1-are-uploads-end-to-end-encrypted)
23. [How is media transmitted and stored?](#q2-how-is-media-transmitted-and-stored)
24. [Who has access to my uploaded media?](#q3-who-has-access-to-my-uploaded-media)
25. [Does GallerySync operate any servers or telemetry?](#q4-does-gallerysync-operate-any-servers-or-telemetry)
26. [How are bot tokens and credentials protected?](#q5-how-are-bot-tokens-and-credentials-protected)
27. [Is on-device encryption supported?](#q6-is-on-device-encryption-supported)
28. [What happens if I uninstall or switch devices?](#q7-what-happens-if-i-uninstall-or-switch-devices)
29. [What if my bot token is leaked or compromised?](#q8-what-if-my-bot-token-is-leaked-or-compromised)
30. [Does GallerySync compress or alter photos/videos?](#q9-does-gallerysync-compress-or-alter-photosvideos)
31. [Does GallerySync work offline?](#q10-does-gallerysync-work-offline)
32. [How does GallerySync compare with commercial cloud storage?](#q11-how-does-gallerysync-compare-with-commercial-cloud-storage)
33. [Support & Future Features](#support--future-features)

---


## 🔹 Part 1: Privacy, Security & Data Handling


### Q1: Are uploads end-to-end encrypted?

➡️ **No.** Ordinary Telegram Bot API channel uploads are **not end-to-end encrypted (E2EE)**.

- Media is encrypted in transit via TLS/HTTPS to Telegram's official Bot API servers (`api.telegram.org`).
- Media is stored securely at rest on Telegram's cloud storage infrastructure.
- Because standard Telegram channel messages are cloud-based, Telegram's servers possess the technical ability to decrypt and serve the media.

---


### Q2: How is media transmitted and stored?

➡️ Media is transmitted via your own configured bot directly from your Android device to your private Telegram channel.

- Telegram processes and stores this media on their distributed server infrastructure.
- No intermediate storage or proxy servers are involved at any point.

---


### Q3: Who has access to my uploaded media?

➡️ **Only you, anyone with access to your private channel, and Telegram's servers.**

- Media in a Telegram channel is accessible to anyone with access to that channel (channel admins, members, or invitees). Keep your backup channel strictly private!
- Neither the GallerySync project developers nor any third parties have access to your channel, credentials, or files.

---


### Q4: Does GallerySync operate any servers or telemetry?

➡️ **No. Absolutely none.**

- GallerySync operates **zero storage servers, zero telemetry, zero crash analytics, and zero proxy infrastructure**.
- All network traffic is strictly between your device and Telegram's official endpoints.

---


### Q5: How are bot tokens and credentials protected?

➡️ All credentials are encrypted locally on your Android device using **Android Keystore-backed AES-256 GCM encryption** via `EncryptedSharedPreferences`.

- They are never written to unencrypted storage in production builds.
- They are never printed to system logs, transmitted to third parties, or embedded in repository source files.

---


### Q6: Is on-device encryption supported?

➡️ **Not at this time. Client-side on-device encryption is a planned future enhancement, not a present capability.**

- Photos and videos are currently uploaded in their original format directly to Telegram.
- Pre-upload client-side encryption is planned for future releases.

---

### Q7: What happens if I uninstall or switch devices?

➡️ Your media remains preserved in your Telegram private channel.

- GallerySync maintains a local Room SQLite database (`wh_database`) tracking local and cloud photo mappings.
- To facilitate library recovery when reinstalling or changing devices, an export/sync of your library metadata index can be backed up to your channel and re-imported.

---

### Q8: What if my bot token is leaked or compromised?

➡️ If your bot token is compromised:

1. Open `@BotFather` in Telegram immediately and revoke the token with `/revoke`.
2. Update the new token in GallerySync's Settings screen.
3. A bot token alone cannot read channel history without the chat ID, but revoking immediately prevents unauthorized uploads or access.

---

### Q9: Does GallerySync compress or alter photos/videos?

➡️ **No.**

- Media is uploaded in its original format and resolution without artificial compression or modification.

---

### Q10: Does GallerySync work offline?

➡️ Local gallery browsing, indexing, album viewing, and queueing work completely offline.

- Uploading requires an active network connection (by default restricted to Wi-Fi unless configured otherwise).

---

## 🔹 Part 2: Comparison & Features

### Q11: How does GallerySync compare with commercial cloud storage?

- **Control**: You manage your own bot and private channel.
- **Zero Third-Party Telemetry**: No ad tracking, no profiling, no third-party cloud analytics.
- **Full Quality**: Original image and video files without forced compression tiers.
- **Open Source**: Full codebase is auditable on GitHub.

---

## 💖 Support & Future Features

Planned enhancements include:

1. **On-device pre-upload encryption layer** (planned future feature)
2. Batch media operations and multi-bot configurations
3. In-app OCR search for media text
4. Selective folder backup filters
