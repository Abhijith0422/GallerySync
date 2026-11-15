# GallerySync

<div align="center">

  <h1>📸 GallerySync</h1>
  <h3>Your Gallery. Your Telegram. Your Backup.</h3>

**A native Android application for automatically backing up photos and videos to your private Telegram channel.**

  <p>
    🔒 Privacy-conscious · 📡 Wi-Fi-only automatic backup · 🚀 Open source
  </p>

  <img src="https://img.shields.io/badge/Platform-Android-green?style=for-the-badge&logo=android" />
  <img src="https://img.shields.io/badge/Language-Kotlin-purple?style=for-the-badge&logo=kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-blue?style=for-the-badge" />
  <img src="https://img.shields.io/badge/Minimum%20SDK-35-brightgreen?style=for-the-badge" />

</div>

---

## 🌟 What is GallerySync?

**GallerySync** is a native Android application that turns your private Telegram channel into a personal photo and video backup destination.

Instead of depending on another cloud storage provider, GallerySync uses the official Telegram Bot API to transfer your media directly from your Android device to a Telegram channel that you control.

The application automatically discovers accessible gallery media, maintains a persistent upload queue, and synchronizes eligible media when an allowed network connection is available.

GallerySync is built using Kotlin and Jetpack Compose, with a focus on reliability, background synchronization, and user control.

### Why GallerySync?

- 📸 Back up photos and videos from your Android gallery.
- ☁️ Use your own private Telegram channel as the destination.
- 🔄 Automatically discover newly added media.
- 📶 Restrict automatic backups to Wi-Fi.
- 📂 Maintain a persistent upload queue.
- 🔁 Recover interrupted uploads and retry failed transfers.
- 🔐 Store Telegram credentials using Android Keystore-backed encryption.
- 📴 Keep pending media queued when the network is unavailable.

**Your device. Your Telegram bot. Your backup destination.**

---

## ✨ Features

### 📸 Photo and Video Backup

- Supports gallery images and videos.
- Discovers existing media during initial backup.
- Detects newly added media through MediaStore observation.
- Extracts image and video metadata.
- Displays video thumbnails, dimensions, and duration.
- Preserves original media files during upload.

### 🔄 Automatic Synchronization

- Automatically discovers new gallery media.
- Uses Android WorkManager for background synchronization.
- Supports persistent queue processing.
- Resumes eligible work after process termination and device reboot.
- Avoids repeatedly uploading completed media.
- Uses content fingerprints for duplicate detection.

### 📶 Wi-Fi-Only Backup

Automatic synchronization is restricted to eligible network connections.

- Wi-Fi-only automatic backup is enabled by default.
- Mobile data is not used for automatic backups.
- Metered Wi-Fi can be allowed explicitly.
- Manual mobile-data uploads can be enabled separately.
- Pending uploads remain queued when network conditions are unsuitable.

### 📋 Persistent Upload Queue

GallerySync maintains a Room database-backed upload queue.

Queue capabilities include:

- Pending uploads.
- Active uploads.
- Completed uploads.
- Failed uploads.
- Scheduled retries.
- Oversized media tracking.
- Missing source tracking.
- Manual retry and cancellation.
- Recovery of interrupted uploads.

The queue is designed to survive application restarts, process termination, and device reboots.

### 🤖 Telegram Integration

GallerySync uses the official Telegram Bot API.

- Connect your own Telegram bot.
- Configure a private channel as the backup destination.
- Verify bot and channel permissions.
- Send test messages.
- Upload photos and videos.
- Support Telegram channel topics where configured.
- Handle Telegram API errors and rate limits.

### 🔐 Credential Security

- Telegram bot tokens are encrypted using Android Keystore-backed encrypted preferences.
- Credentials are not intentionally exposed in application logs.
- Production installations do not fall back to plaintext credential storage.
- No separate GallerySync account is required.

---

## 🏗️ Technology Stack

| Component               | Technology                                                   |
| ----------------------- | ------------------------------------------------------------ |
| Programming language    | Kotlin                                                       |
| UI framework            | Jetpack Compose                                              |
| Design system           | Material 3                                                   |
| Architecture            | Existing CloudGallery architecture, extended for GallerySync |
| Local database          | Room / SQLite                                                |
| Background processing   | Android WorkManager                                          |
| Media discovery         | Android MediaStore                                           |
| Network communication   | Telegram Bot API                                             |
| Image loading           | Coil                                                         |
| Credential storage      | Android Keystore / EncryptedSharedPreferences                |
| Minimum Android version | Android 15 (API 35)                                          |
| Target Android version  | API 36                                                       |

**Application ID:** `com.gallerysync.app`

---

## 🔄 How It Works

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
     Wi-Fi / Network Eligibility
               |
               v
      Queue Processing Worker
               |
               v
       Telegram Upload Dispatcher
               |
               v
       Telegram Bot API
               |
               v
      Your Private Telegram Channel
```

### Upload Process

1. GallerySync scans accessible photos and videos.
2. Media metadata is registered in the local database.
3. Eligible media is added to the persistent upload queue.
4. The network policy checks whether synchronization is allowed.
5. The queue processor claims media items atomically.
6. The Telegram dispatcher selects the appropriate upload method.
7. Telegram receives the media.
8. GallerySync records the upload result and remote message information.

If an upload is interrupted, the queue can recover the item and schedule another attempt.

---

## 🚀 Getting Started

### Requirements

- Android device running Android 15 or newer.
- Telegram account.
- Telegram bot created using BotFather.
- Private Telegram channel.
- Internet connection for initial setup and uploads.

### Step 1: Create a Telegram Bot

1. Open Telegram.
2. Search for [@BotFather](https://t.me/BotFather).
3. Send `/newbot`.
4. Follow the instructions to create your bot.
5. Copy the generated bot token.

**Important:** Never share your bot token publicly or commit it to GitHub.

### Step 2: Create a Private Telegram Channel

1. Create a new Telegram channel.
2. Set its visibility to Private.
3. Open the channel's administrator settings.
4. Add your newly created bot as an administrator.
5. Enable permission to post messages.

GallerySync needs administrator and posting permissions to upload media to the channel.

### Step 3: Obtain Your Channel Chat ID

The Telegram channel Chat ID is different from a group Chat ID.

To obtain it manually:

1. Add your bot as an administrator to the private channel.
2. Publish a new message in the channel.
3. Use the Telegram Bot API `getUpdates` method to retrieve the channel update.
4. Find the `channel_post.chat.id` field in the response.
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

**Note:** Telegram's `getUpdates` method cannot be used while an outgoing webhook is configured. If another application is polling the same bot, it may also interfere with update retrieval.

### Step 4: Connect GallerySync

1. Install and open GallerySync.
2. Grant the required photo and video permissions.
3. Open Telegram Connection settings.
4. Enter your bot token.
5. Enter your private channel Chat ID.
6. Verify the connection.
7. Confirm that the bot has administrator and posting permissions.
8. Send a test message.

Once connected, GallerySync can begin backing up eligible media.

### Step 5: Configure Automatic Backup

Open the backup settings and configure:

- Wi-Fi-only automatic backup.
- Metered Wi-Fi preference.
- Manual mobile-data upload permission.
- Automatic synchronization preferences.

The default configuration prevents automatic uploads over cellular data and metered Wi-Fi.

---

## 📦 Telegram Upload Limitations

GallerySync uses Telegram's hosted Bot API.

The hosted Bot API has file-size restrictions:

| Media operation | Hosted Bot API limit |
| --------------- | -------------------: |
| sendPhoto       |                10 MB |
| sendVideo       |                50 MB |
| sendDocument    |                50 MB |

GallerySync uses the appropriate Telegram upload method for supported media formats.

- MP4 and M4V videos use the video upload method.
- Other supported video formats can be sent as documents.
- Images use the existing image/document upload pipeline.
- Media exceeding the applicable upload limit is retained in the queue and reported to the user.

**Important:** Sending a file as a document does not bypass the hosted Bot API's 50 MB upload limit.

These restrictions are imposed by Telegram, not GallerySync.

---

## 🔒 Privacy, Security and Data Handling

GallerySync is designed to minimize external infrastructure and give users control over their backup destination.

### Direct Telegram Transmission

Media is transmitted from the Android device to Telegram's official Bot API over HTTPS.

GallerySync does not operate its own media storage server.

### Telegram Encryption Model

**Telegram Bot API uploads are not end-to-end encrypted.**

Telegram processes and stores uploaded media on its cloud infrastructure. Telegram's infrastructure can access the content necessary to operate its services.

GallerySync should therefore not be considered a zero-knowledge or end-to-end encrypted cloud storage solution.

### Channel Access

Media uploaded to your private channel can be accessed by people who have permission to view that channel.

Keep your channel private and restrict membership to trusted users.

### Local Credential Protection

Bot tokens and channel configuration are stored using Android Keystore-backed encryption.

Credentials should never be included in screenshots, logs, source code, or public issue reports.

### Local Database

GallerySync uses Room/SQLite to store:

- Indexed media metadata.
- Upload queue records.
- Upload status.
- Content fingerprints.
- Telegram remote message identifiers.
- Synchronization information.

The database is stored locally on the device.

### Analytics and Tracking

GallerySync does not intentionally integrate third-party advertising or user-tracking SDKs.

Review the application's actual dependency and network configuration before relying on this statement for a particular release.

---

## 🧪 Development and Build

### Prerequisites

- Android Studio.
- JDK 17.
- Android SDK 35 or newer.
- Gradle wrapper included in the repository.

### Clone the Repository

```bash
git clone YOUR_GALLERYSYNC_REPOSITORY_URL
cd GallerySync
```

### Build Debug APK

On Windows:

```powershell
.\gradlew assembleDebug
```

On Linux/macOS:

```bash
./gradlew assembleDebug
```

### Run Unit Tests

Windows:

```powershell
.\gradlew testDebugUnitTest
```

Linux/macOS:

```bash
./gradlew testDebugUnitTest
```

### Clean Build

```bash
.\gradlew clean
.\gradlew compileDebugKotlin assembleDebug testDebugUnitTest
```

The generated debug APK is located at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## 🛣️ Project Roadmap

Potential future improvements include:

- [ ] Automatic Telegram channel detection.
- [ ] Full-file SHA-256 duplicate verification.
- [ ] On-device encryption before upload.
- [ ] Advanced folder-specific backup controls.
- [ ] More detailed backup scheduling.
- [ ] Improved metadata restoration and migration tools.
- [ ] Additional media organization features.
- [ ] Signed production release and distribution.

Features may change as development progresses.

---

## ⚠️ Important Notes

- GallerySync requires access to the media you want to back up.
- Android permission restrictions determine which media can be indexed.
- Automatic backup requires an eligible network connection.
- Telegram's hosted Bot API imposes upload-size restrictions.
- Telegram Bot API channel uploads are not end-to-end encrypted.
- Uninstalling the application can remove its local database and credentials.
- A new installation may require reconnecting your bot and rebuilding the local media index.
- Telegram is an external service and its availability and API restrictions are outside GallerySync's control.

---

## 🙏 Credits and Acknowledgements

GallerySync is built upon the work of developers who contributed to the original open-source projects from which it evolved.

We sincerely acknowledge the original authors and maintainers.

### 1. Whitehole – Deep Bera

**Original author:** [Deep Bera (@beradeep)](https://github.com/beradeep)

**Original project:** [Whitehole](https://github.com/beradeep/whitehole)

Whitehole provided foundational ideas and implementation work for transforming Telegram into an Android media backup solution.

We acknowledge Deep Bera for the original project and the architectural groundwork shared with the open-source community.

### 2. Chitralaya CloudGallery – AKS-Labs

**Original project and maintainer:** [Chitralaya CloudGallery – AKS-Labs](https://github.com/AKS-Labs/CloudGallery)

**Original maintainer:** Aditya / AKS-Labs

GallerySync is derived from and extends the Chitralaya CloudGallery codebase.

We acknowledge Aditya and the AKS-Labs contributors for developing, maintaining, and sharing Chitralaya CloudGallery, including its Android application architecture and Telegram integration.

GallerySync builds upon that foundation with substantial changes and additions, including:

- GallerySync project identity and package migration.
- Expanded image and video support.
- Android 15 media permission handling.
- Persistent upload queue architecture.
- Queue recovery and retry scheduling.
- Unified network eligibility policy.
- Wi-Fi-only automatic synchronization.
- Additional testing and reliability improvements.

### 3. Other Open-Source Technologies

We also acknowledge the developers and maintainers of the technologies used by this project:

- [Android Jetpack](https://developer.android.com/jetpack)
- [Jetpack Compose](https://developer.android.com/compose)
- [Kotlin](https://kotlinlang.org/)
- [Room](https://developer.android.com/training/data-storage/room)
- [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
- [Coil](https://coil-kt.github.io/coil/)
- [Telegram Bot API](https://core.telegram.org/bots/api)

**Please retain all applicable upstream copyright notices, license files, and attribution requirements when redistributing this project.**

---

## 🤝 Contributing

Contributions, bug reports, and suggestions are welcome.

1. Fork the repository.
2. Create a feature branch.
3. Make your changes.
4. Test your implementation.
5. Submit a pull request with a clear description.

For bugs and feature requests, use the repository's GitHub Issues section.

---

## ❤️ A Final Note

GallerySync continues the work of an open-source project built by developers who wanted a different approach to personal media backup.

We believe users should have more control over where their memories are stored and how their data is transferred.

This project stands on the work of its predecessors and the open-source community.

**Your Gallery. Your Telegram. Your Backup.**

<div align="center">

  <h3>📸 GallerySync</h3>
  <p><em>Built on open-source foundations. Extended for reliable media backup.</em></p>

</div>
