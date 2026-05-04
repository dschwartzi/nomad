# Nomad

Your home phone's SMS, wherever you are — via Telegram.

## What it does

Nomad turns your **home Android phone (H)** into a Telegram-controlled SMS bridge:

- Every SMS that arrives at H is forwarded to your Telegram.
- Reply in Telegram → H sends the SMS back from your real number.
- Use `/send +15551234567 text` to initiate new SMS from anywhere.
- Contacts resolution: `/send mom call me` works if Mom is in your phone contacts.

```
Sender ──SMS to H#──▶  H phone  ──Telegram──▶  You (anywhere)
                           ▲                          │
                           └─────── Telegram ─────────┘
                      (SMS from H# goes out to sender)
```

Your recipients always see your real number. You never touch a travel SIM.

## Setup (10 minutes)

### 1. Create a Telegram bot

1. In Telegram, chat with **@BotFather**.
2. `/newbot` → pick a name (display), then a unique username ending in `bot`.
3. Copy the **token** BotFather gives you (looks like `7123456789:AAF-xxxxxx...`).
4. Open a chat with your new bot and send `/start` — this is required so the bot can message you.
5. Find your **chat ID**: open
   `https://api.telegram.org/bot<YOUR_TOKEN>/getUpdates`
   in a browser. Look for `"chat":{"id":12345678,...}` — that number is your chat ID.

### 2. Install on H

**Easiest — download the APK on H itself:**

1. On H, open the [latest release page](https://github.com/dschwartzi/nomad/releases/latest) in your browser.
2. Tap **app-debug.apk** to download.
3. When prompted, allow installs from this source (Settings → enable → back → Install).
4. Open **Nomad** from the launcher.

**Alternative — install from your computer via ADB:**

```bash
adb install app-debug.apk
```

**Build from source (developers):**

```bash
./gradlew :app:installDebug
```

or open the project in Android Studio (Koala+) and hit **Run**.

> **Note:** This is sideload-only. Google Play essentially won't approve any third-party SMS app, so there will never be a Play Store listing.

### 3. Configure on H

1. Launch **Nomad** on H.
2. **Grant permissions** (SMS, Contacts, Notifications).
3. **Set as default SMS app** — tap the button; Android will show a system dialog. You can revert any time in Settings → Apps → Default apps → SMS app.
4. Go to the **Settings** tab. Paste your **bot token** and **chat ID**. Tap **Test** — you should get a message in Telegram.
5. Back to **Status**. Toggle **Bridge** on.

### 4. Try it

- Have someone text H. You should see a message in Telegram within a couple of seconds.
- **Reply** to that message in Telegram — H sends your reply back as SMS.
- Send `/help` in Telegram for commands.

## Commands (in Telegram)

| Command | Effect |
|---|---|
| `/send +15551234567 hello` | Send SMS to a number |
| `/send mom see you soon` | Send SMS to a contact named Mom |
| `/to +15551234567` | Set active thread; bare-text messages go there |
| `/recent` | Last 10 messages in/out |
| `/status` | Bridge health, active thread |
| `/help` | Command reference |
| *reply to forwarded SMS* | Respond to that specific sender |
| *any plain text* | Reply to current active thread |

## Architecture

```
app/src/main/java/ai/nomad/
├── NomadApp.kt                 Application; singletons (DB, prefs)
├── MainActivity.kt             Compose entry point + permission flows
├── ui/NomadApp.kt              Compose UI (Status, Inbox, Settings tabs)
├── sms/
│   ├── SmsDeliverReceiver.kt   Catches SMS_DELIVER (default-SMS-only)
│   ├── SmsSender.kt            Sends SMS via SmsManager
│   ├── MmsReceiver.kt          Stub (required)
│   └── HeadlessSmsSendService.kt  Stub (required)
├── bridge/
│   ├── BridgeService.kt        Foreground service; long-polls Telegram
│   ├── BridgeRouter.kt         SMS ↔ Telegram routing logic
│   ├── ContactResolver.kt      Name ↔ phone lookup
│   └── BootReceiver.kt         Auto-restart on reboot
├── telegram/
│   ├── TelegramBot.kt          OkHttp-backed Bot API client
│   └── TelegramDtos.kt         Serialization DTOs
├── data/
│   ├── NomadDatabase.kt        Room DB
│   ├── Entities.kt             Message / Conversation / Event
│   └── Daos.kt
└── util/
    ├── Prefs.kt                EncryptedSharedPreferences
    └── Logger.kt
```

## Security

- Only messages from the **configured Telegram chat ID** are accepted. Any other user messaging the bot is ignored.
- Bot token and chat ID are stored in `EncryptedSharedPreferences` (hardware-backed where available).
- Nothing leaves the phone except: (a) SMS to recipients via your carrier, (b) HTTPS to `api.telegram.org`.
- No cloud. No analytics. No account.

**Important:** anyone with your bot token can impersonate the bot. If you suspect leakage, revoke via @BotFather (`/revoke`) and rotate.

## Sharing this with someone else

If a friend or family member wants to use Nomad on their phone, they should **create their own Telegram bot**. One bot per person — don't share bots.

Why: a Telegram bot has one update stream and one token. Anyone with the token can read every message sent to that bot. Two people sharing one bot = each can read the other's SMS.

What to share:
- The [release page link](https://github.com/dschwartzi/nomad/releases/latest) so they can install the APK
- This README so they can follow setup
- **Not** your bot token, **not** your chat ID

They'll create their own bot via @BotFather, get their own chat ID, and configure their own H phone. Two completely separate bridges. Total privacy.

## Privacy caveats

- Telegram messages are **not** end-to-end encrypted by default (only "Secret Chats" are). Messages pass through Telegram servers in the clear-to-Telegram. For most people this is fine; if you need E2E, swap the transport layer for Signal-CLI or similar (bigger project).
- Carrier SMS is unencrypted by design. This app doesn't change that.

## Roadmap

Phase 1 (done in this repo):
- Default SMS app, receive/send, Telegram bridge, commands, contact resolution, boot persistence.

Phase 2 (next):
- AI layer: auto-categorize (OTP / bank / personal / spam), per-category routing, summaries, translation.
- Smart notifications: only ping Telegram for important categories.
- Skills: user-defined rules ("extract 2FA codes", "auto-reply when I'm asleep").

Phase 3:
- MMS support (images).
- Multi-device: pair several Telegram users with scoped permissions.
- Optional SMS-over-SMS fallback for when home internet is dead.

## Troubleshooting

- **Bridge toggle is disabled.** Complete the checklist on the Status tab: permissions, default SMS app, Telegram configured.
- **Nothing arrives in Telegram when someone texts H.** Check: is the foreground service notification showing? Is H online? Is the bot token right? Tap **Test** in Settings.
- **Reply from Telegram doesn't send.** Check the Android notification panel for send failures; check carrier restrictions; make sure H has cell signal (SMS doesn't go over WiFi-only).
- **"Could not reach Telegram"** during Test. Likely firewall/DNS issue, or wrong token. Try the token in `https://api.telegram.org/bot<TOKEN>/getMe` from a browser.

## License

Personal project. Do what you want.
