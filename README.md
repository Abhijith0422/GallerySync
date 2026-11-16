# 📸 GallerySync

<p align="center">
  <h1>GallerySync</h1>
  <h3>Your Gallery. Your Telegram. Your Backup.</h3>
  <p><strong>A native Android application for backing up photos and videos to your private Telegram channel.</strong></p>
  <p>🔒 Privacy-conscious · 📡 Wi-Fi-only automatic backup · ☁️ Telegram-backed storage</p>

  <img src="https://img.shields.io/badge/Platform-Android-green?style=for-the-badge&logo=android" alt="Android">
  <img src="https://img.shields.io/badge/Language-Kotlin-purple?style=for-the-badge&logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-blue?style=for-the-badge" alt="Jetpack Compose">
  <img src="https://img.shields.io/badge/Minimum%20SDK-35-brightgreen?style=for-the-badge" alt="Minimum SDK 35">
</p>

---

## 🌟 What is GallerySync?

**GallerySync** is a native Android application that uses a private Telegram channel as a personal photo and video backup destination.

The application discovers accessible media on your device, indexes it locally, and places eligible items in a persistent upload queue. Background work transfers media to a Telegram channel using your own Telegram bot.

GallerySync is built with Kotlin and Jetpack Compose. Its design focuses on persistent synchronization, resumable chunk uploads, catalog recovery, and verified video restoration.

> Your device. Your Telegram bot. Your backup destination.

## ✨ Features

### 📸 Photo and video backup

- Supports gallery images and videos.
- Scans accessible media through Android MediaStore.
- Detects newly added media and reconciles gallery changes.
- Records media metadata, including dimensions and video duration.
- Displays video thumbnails and duration in the gallery.
- Keeps local media files intact during backup.

### 🔄 Automatic synchronization

- Uses Android WorkManager for persistent background processing.
- Maintains a Room-backed upload queue.
- Recovers eligible work after process termination and device restart.
- Retries recoverable failures and respects Telegram rate-limit responses.
- Avoids re-uploading completed media.
- Uses media identity and content fingerprints for duplicate prevention.

### 📦 Large-video chunked backup

Videos larger than **16 MiB** are uploaded using the chunked backup pipeline.

- Default chunk size: **16 MiB (16,777,216 bytes)**.
- Each chunk is uploaded as a separate Telegram document.
- Chunk offsets and byte lengths are recorded.
- SHA-256 checksums are calculated for chunks and the complete source file.
- Upload progress and chunk state are persisted.
- Interrupted work can resume from confirmed chunks.
- A manifest records the chunk references and integrity information.

### 🗂️ Backup catalog and recovery

GallerySync maintains a catalog of uploaded media in Telegram.

- Versioned backup manifests describe chunked videos.
- A master index references the backup catalog.
- The current master index is pinned in the configured Telegram channel.
- Catalog publication is confirmed before a chunked backup is marked complete.
- A fresh local database can reconstruct catalog metadata from the pinned index and its referenced documents.
- Recovery does not require the original Room database.

Catalog recovery can restore metadata and Telegram references; it does not recreate local media that no longer exists unless the corresponding backup is downloaded.

### ♻️ Video restoration

- Restores directly uploaded videos when they are within the hosted Bot API download limit.
- Downloads chunked videos using their recovered manifest.
- Verifies individual chunks and the reconstructed file with SHA-256.
- Uses streaming buffers rather than loading an entire video into memory.
- Supports exporting restored videos through Android's Storage Access Framework (SAF).
- Provides restoration progress, cancellation and retry controls.

### 📶 Network policy

Automatic synchronization is restricted by the configured network policy.

- Unmetered Wi-Fi automatic backup is enabled by default.
- Metered Wi-Fi can be allowed explicitly.
- Cellular data is not used for automatic backup by default.
- Manual mobile-data uploads can be enabled separately.
- Pending uploads remain queued when network conditions are unsuitable.

### 📋 Persistent upload queue

The Room database-backed queue tracks:

- Pending uploads.
- Active uploads.
- Completed uploads.
- Failed uploads.
- Scheduled retries.
- Chunked backup progress.
- Missing or inaccessible source media.
- Manual retry and cancellation.
- Recovery of stale or interrupted work.

### 🤖 Telegram integration

- Connect your own Telegram bot.
- Configure a private Telegram channel as the destination.
- Verify bot identity and channel permissions.
- Send a test message.
- Upload photos, videos and chunk documents.
- Handle Telegram API errors and rate limits.
- Support channel topics where configured.

### 🔐 Credential protection

- Bot credentials and channel configuration use Android Keystore-backed encrypted storage.
- Token-bearing request paths are redacted from classified upload errors.
- Credentials should never be committed to source control or shared in screenshots and logs.
- No separate GallerySync account or GallerySync media server is required.

---

## 🏗️ Technology stack

| Component               | Technology                                    |
| ----------------------- | --------------------------------------------- |
| Programming language    | Kotlin                                        |
| UI framework            | Jetpack Compose                               |
| Design system           | Material 3                                    |
| Local database          | Room / SQLite                                 |
| Background processing   | Android WorkManager                           |
| Media discovery         | Android MediaStore                            |
| Network communication   | Telegram Bot API                              |
| Image loading           | Coil                                          |
| Video playback          | AndroidX Media3                               |
| Credential storage      | Android Keystore / EncryptedSharedPreferences |
| Minimum Android version | Android 15 (API 35)                           |
| Target Android version  | API 36                                        |
| Java / Kotlin target    | 17                                            |

**Application ID:** `com.gallerysync.app`  
**Current database schema:** Room v15

---

## 🔄 How it works

```text
Android Gallery
      |
      v
MediaStore Scanner
      |
      v
Local Room Database
      |
      v
Persistent Upload Queue
      |
      v
Network Eligibility Policy
      |
      v
Queue Processing Worker
      |
      v
Telegram Upload Dispatcher
      |
      +---------------------------+
      |                           |
      v                           v
Direct Upload                Chunked Upload
(through 16 MiB)             (16 MiB slices)
      |                           |
      +-------------+-------------+
                    |
                    v
             Telegram Bot API
                    |
                    v
          Private Telegram Channel
                    |
                    v
          Manifest and Master Index
                    |
                    v
             Pinned Catalog
```

### Upload process

1. GallerySync scans media accessible through Android MediaStore.
2. Media metadata is registered in the local Room database.
3. Eligible media is added to the persistent upload queue.
4. The network policy checks whether automatic or manual synchronization is allowed.
5. A worker claims an item from the queue.
6. The upload dispatcher selects direct or chunked upload.
7. Telegram receives the media or individual chunks.
8. GallerySync records Telegram identifiers and upload state.
9. For chunked videos, GallerySync publishes a manifest and updates the pinned master index.
10. The backup is marked complete only after the required catalog confirmation.

If an upload is interrupted, persisted queue and chunk state allow eligible work to resume.

### Restoration process

1. GallerySync reads the relevant direct media record or chunk manifest.
2. It downloads the Telegram file or individual chunks.
3. Downloaded data is checked against the stored integrity information.
4. Chunked media is reconstructed in the correct order.
5. The complete file is checked against its full-file SHA-256.
6. The verified result can be exported using SAF.
7. The restored video can be opened using the local playback flow.

---

## 🚀 Getting started

### Requirements

- Android device running Android 15 or newer.
- Telegram account.
- Telegram bot created using BotFather.
- Private Telegram channel.
- Internet connection for initial setup and uploads.

### Step 1: Create a Telegram bot

1. Open Telegram.
2. Search for [@BotFather](https://t.me/BotFather).
3. Send `/newbot`.
4. Follow the instructions to create your bot.
5. Store the generated bot token securely.

**Important:** Never share your bot token publicly or commit it to GitHub.

### Step 2: Create a private Telegram channel

1. Create a new Telegram channel.
2. Set its visibility to Private.
3. Open the channel's administrator settings.
4. Add your bot as an administrator.
5. Enable permission to post messages.

GallerySync needs the appropriate administrator and posting permissions to upload media to the channel.

### Step 3: Obtain the channel Chat ID

The Telegram channel Chat ID is different from a group Chat ID.

A typical way to obtain it is:

1. Add your bot as an administrator to the private channel.
2. Publish a new message in the channel.
3. Use the Telegram Bot API `getUpdates` method to retrieve the channel update, if the bot is using polling.
4. Find the `channel_post.chat.id` field.
5. Copy the numeric Chat ID, preserving the minus sign.

Example response structure:

```json
{
    "channel_post": {
        "chat": {
            "id": -1001234567890,
            "title": "My Private Gallery",
            "type": "channel"
        }
    }
}
```

The example ID is illustrative only.

**Note:** `getUpdates` cannot be used while an outgoing webhook is configured. Another application polling the same bot may also interfere with update retrieval.

### Step 4: Connect GallerySync

1. Install and open GallerySync.
2. Grant the required photo and video permissions.
3. Open Telegram Connection settings.
4. Enter your bot token.
5. Enter your private channel Chat ID.
6. Verify the connection.
7. Confirm that the bot has administrator and posting permissions.
8. Send a test message.

### Step 5: Configure automatic backup

Open backup settings and review:

- Wi-Fi-only automatic backup.
- Metered Wi-Fi preference.
- Manual mobile-data upload permission.
- Automatic synchronization preferences.

The default configuration prevents automatic uploads over cellular data and metered Wi-Fi.

---

## 📦 Telegram upload and download limitations

GallerySync uses Telegram's **hosted Bot API**. Telegram applies different limits to uploading and downloading files.

| Operation                       | Hosted Bot API limit / GallerySync policy |
| ------------------------------- | ----------------------------------------: |
| `sendPhoto`                     |              Telegram upload limit: 10 MB |
| `sendVideo`                     |              Telegram upload limit: 50 MB |
| `sendDocument`                  |              Telegram upload limit: 50 MB |
| `getFile` download              | Telegram download limit: 20,000,000 bytes |
| GallerySync direct video upload |                            Maximum 16 MiB |
| GallerySync chunk size          |                          16 MiB per chunk |

### Why GallerySync uses 16 MiB chunks

Telegram's hosted Bot API upload and download limits are not identical. A video uploaded as one direct file between 20,000,001 bytes and 50 MB may be accepted for upload but cannot be downloaded through the hosted `getFile` method.

To avoid creating this problem for new video backups, GallerySync applies this policy:

- Videos up to and including **16 MiB** use direct upload.
- Videos larger than **16 MiB** use chunked upload.
- Every chunk is at most **16 MiB**, below the hosted `getFile` download ceiling.

The 16 MiB threshold is a GallerySync compatibility policy, not a Telegram-imposed upload limit.

### Legacy backups

Videos uploaded by older versions as single files larger than 20,000,000 bytes remain in backup history. They are not automatically deleted or re-uploaded. However, they cannot be downloaded through the hosted Bot API and GallerySync reports this limitation when restoration is attempted.

Telegram's limits and service availability are outside GallerySync's control.

---

## 🔒 Privacy, security and data handling

GallerySync is designed to minimize external infrastructure and give users control over their backup destination.

### Direct Telegram transmission

Media is transmitted from the Android device to Telegram's official Bot API over HTTPS. GallerySync does not operate its own media storage server.

### Telegram encryption model

**Telegram Bot API uploads are not end-to-end encrypted.**

Telegram processes and stores uploaded media on its cloud infrastructure. Telegram's infrastructure can access the content necessary to operate its services.

GallerySync should therefore not be considered a zero-knowledge or end-to-end encrypted cloud storage solution.

### Channel access

Media uploaded to a private channel can be accessed by people who have permission to view that channel. Keep the channel private and restrict membership to trusted users.

### Local credential protection

Bot tokens and channel configuration are stored using Android Keystore-backed encryption. Credentials should never be included in screenshots, logs, source code or public issue reports.

### Local database and application data

GallerySync uses Room/SQLite to store:

- Indexed media metadata.
- Upload queue records and status.
- Content fingerprints and integrity metadata.
- Telegram remote message identifiers.
- Chunk and manifest metadata.
- Catalog and restoration state.

The database and application credentials are stored locally on the device. Uninstalling the application can remove this local state and its Keystore-backed credentials. The Telegram catalog is intended to support rebuilding backup metadata after reinstalling and reconnecting the bot and channel.

### Analytics and tracking

The Phase 10.7 audit reported no third-party advertising or user-tracking SDKs in the codebase. Review the actual dependency and network configuration for the specific build you use.

---

## 🧪 Development and build

### Prerequisites

- Android Studio.
- JDK 17.
- Android SDK 35 or newer.
- Gradle wrapper included in the repository.

### Clone the repository

```bash
git clone YOUR_GALLERYSYNC_REPOSITORY_URL
cd GallerySync
```

### Build debug APK

**Windows:**

```powershell
.\gradlew assembleDebug
```

**Linux/macOS:**

```bash
./gradlew assembleDebug
```

### Run unit tests

**Windows:**

```powershell
.\gradlew testDebugUnitTest
```

**Linux/macOS:**

```bash
./gradlew testDebugUnitTest
```

For the release unit-test variant:

```bash
./gradlew testReleaseUnitTest
```

### Clean build

```bash
./gradlew clean
./gradlew compileDebugKotlin assembleDebug testDebugUnitTest
```

The generated debug APK is located at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## ✅ Validation status

The Phase 10.7 final reliability audit was completed on **November 16, 2026**.

| Validation item              | Reported result        |
| ---------------------------- | ---------------------- |
| Automated test suites        | 43                     |
| Automated unit tests         | 374 passed             |
| Test failures / errors       | 0                      |
| Room database                | v15                    |
| Physical test device         | Motorola Moto G45 5G   |
| Android version              | Android 15 (API 35)    |
| Direct video restoration     | SHA-256 verified       |
| Chunked video restoration    | SHA-256 verified       |
| Reconstructed video playback | Verified on device     |
| SAF export integrity         | Verified in audit      |
| HOLDING media preservation   | 9,958 before and after |
| Final audit verdict          | PASS                   |

The audit also reported fixes for bot-token redaction in upload errors and access protection for diagnostic receivers.

These results describe the reported Phase 10.7 validation. Device behaviour, Telegram API restrictions and Android background execution can vary with future app, OS and service changes.

---

## 🛣️ Future possibilities

The core backup, chunking, catalog recovery and video restoration workflows have been implemented and audited. Potential enhancements, not required for the current personal-use workflow, include:

- [ ] Optional on-device encryption before upload.
- [ ] Advanced folder-specific backup controls.
- [ ] More detailed backup scheduling.
- [ ] Expanded metadata restoration and migration tools.
- [ ] Additional media organization features.
- [ ] Further release and distribution improvements.

---

## ⚠️ Important notes

- GallerySync requires permission to access the media you want to back up.
- Android permission restrictions determine which media can be indexed.
- Automatic backup requires an eligible network connection.
- The 16 MiB direct-video threshold and chunk size are GallerySync policies designed around Telegram's hosted download limit.
- Telegram Bot API channel uploads are not end-to-end encrypted.
- Older single-file videos larger than 20,000,000 bytes may not be restorable through the hosted Bot API.
- Uninstalling the application can remove its local database and credentials.
- A new installation may require reconnecting your bot and channel and rebuilding the local media index.
- Catalog recovery restores backup metadata and remote references; it does not guarantee that the original local file still exists.
- Keep important originals or an additional backup until you have verified restoration.
- Telegram is an external service; its availability and API restrictions are outside GallerySync's control.

---

## 🙏 Credits and acknowledgements

GallerySync is built upon the work of developers who contributed to the original open-source projects from which it evolved.

We sincerely acknowledge the original authors and maintainers.

### 1. Whitehole – Deep Bera

**Original author:** [Deep Bera (@beradeep)](https://github.com/beradeep)  
**Original project:** [Whitehole](https://github.com/beradeep/whitehole)

Whitehole provided foundational ideas and implementation work for transforming Telegram into an Android media backup solution.

We acknowledge Deep Bera for the original project and the architectural groundwork shared with the open-source community.

### 2. Chitralaya CloudGallery – AKS-Labs

**Original project:** [Chitralaya CloudGallery – AKS-Labs](https://github.com/AKS-Labs/CloudGallery)  
**Original maintainer:** Aditya / AKS-Labs

GallerySync is derived from and extends the Chitralaya CloudGallery codebase.

We acknowledge Aditya and the AKS-Labs contributors for developing, maintaining and sharing Chitralaya CloudGallery, including its Android application architecture and Telegram integration.

GallerySync builds upon that foundation with substantial changes and additions, including:

- GallerySync project identity and package migration.
- Expanded image and video support.
- Android 15 media permission handling.
- Persistent upload queue architecture.
- Queue recovery and retry scheduling.
- Unified network eligibility policy.
- Wi-Fi-only automatic synchronization.
- 16 MiB chunked video backup.
- Backup manifests and pinned master index catalog.
- Fresh-install catalog recovery.
- Video restoration and SHA-256 verification.
- Additional testing, security and reliability improvements.

### 3. Other open-source technologies

We also acknowledge the developers and maintainers of the technologies used by this project:

- [Android Jetpack](https://developer.android.com/jetpack)
- [Jetpack Compose](https://developer.android.com/compose)
- [Kotlin](https://kotlinlang.org/)
- [Room](https://developer.android.com/training/data-storage/room)
- [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
- [Coil](https://coil-kt.github.io/coil/)
- [AndroidX Media3](https://developer.android.com/media/media3)
- [Telegram Bot API](https://core.telegram.org/bots/api)

**Please retain all applicable upstream copyright notices, license files and attribution requirements when redistributing this project.**

---

## 🤝 Contributing

Contributions, bug reports and suggestions are welcome.

1. Fork the repository.
2. Create a feature branch.
3. Make your changes.
4. Test your implementation.
5. Submit a pull request with a clear description.

For bugs and feature requests, use the repository's GitHub Issues section.

---

## ❤️ A final note

GallerySync continues the work of an open-source project built by developers who wanted a different approach to personal media backup.

We believe users should have more control over where their memories are stored and how their data is transferred.

This project stands on the work of its predecessors and the open-source community.

<p align="center">
  <h3>📸 GallerySync</h3>
  <em>Built on open-source foundations. Extended for reliable media backup.</em>
  <br><br>
  <strong>Your Gallery. Your Telegram. Your Backup.</strong>
</p>
