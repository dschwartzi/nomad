# Nomad

Your home phone's SMS, wherever you are.

Two transports, your pick:

1. **Nomad Travel app** (preferred) — companion Android app on your travel phone. Real chat UI, contact list synced from home, tap-to-dial, push notifications. Travels through your own Firebase Cloud Messaging relay.
2. **Telegram bot** — text-based control via any Telegram client. Useful when you don't have your travel phone but you do have a laptop.

You can configure either, both, or none.

## What it does

Nomad turns your **home Android phone (H)** into a remote-controlled SMS bridge:

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

## Travel-app setup

### Prerequisites: deploy your own relay

This repo does not ship any Firebase project identifiers, API keys, or URLs. You create your own Firebase project, deploy the Cloud Functions, and put your project-specific config files in place locally (they are `.gitignore`d).

**A. Create and configure a Firebase project**

1. Create a Firebase project at <https://console.firebase.google.com>. Free tier is fine. Note the project ID.
2. Enable: **Firestore (Native mode)**, **Cloud Messaging**, **Cloud Functions** (needs the Blaze plan — still free within monthly quota).
3. Register an **Android app** with package name `ai.nomad` and download `google-services.json` → save as `app/google-services.json`.
4. Register a second **Android app** with package name `ai.nomad.travel` → download `google-services.json` → save as `travel/google-services.json`.
5. Copy `.firebaserc.example` → `.firebaserc` and replace the placeholder with your project ID.

**B. Deploy the relay**

```bash
cd functions && npm install && cd ..
firebase login
# Generate your account key:
openssl rand -base64 48 | tr -d '/+=\n' | head -c 40; echo
# Put it in functions/.env (this file is gitignored):
echo "ACCOUNT_KEY=<the-40-char-string-you-just-generated>" > functions/.env
firebase deploy --only functions,firestore:indexes,firestore:rules
```

Your relay URL will be printed at the end — looks like `https://us-central1-<your-project>.cloudfunctions.net`. Save that URL and the account key. You'll type both into the phones.

**C. Build the APKs**

```bash
./gradlew assembleDebug
```

The APKs are at `app/build/outputs/apk/debug/app-debug.apk` and `travel/build/outputs/apk/debug/travel-debug.apk`.

### Security model, briefly

Every call to the relay must carry `X-Account-Key: <your key>`. Requests without it (or with a wrong key) return `401` immediately. The key is a Cloud Functions env var; rotating means editing `functions/.env` and redeploying.

The account key is **never committed, never in the APK, never in logs**. You hold it. You type it into both of your phones through a channel you control. No one else can talk to your relay, which means no one can spam your Firebase billing, enumerate your pairing codes, or attempt to pair with your home phone.

### On the phones

1. Install `app-debug.apk` on **H**. Grant SMS permissions, set as default SMS app.
2. Install `travel-debug.apk` on **T**.
3. On H: Settings → **Relay server** → paste the URL and account key → **Save**.
4. On H: Settings → **Travel device pairing** → **Start pairing**. A 6-digit code appears.
5. On T: open the app → paste the same URL and account key, then type the 6-digit code → **Pair**.
6. On T: **Contacts** → **Sync from home**. Your home contacts appear.
7. Send, receive, carry on.

### Architecture

```
Sender ──SMS to H#──▶  H phone  ──FCM relay──▶  T phone (Nomad Travel)
                          ▲                          │
                          └────── FCM relay ─────────┘
                     (SMS from H# goes out to sender)
```

The relay is a Firebase Cloud Function in your own GCP project, forwarding small JSON payloads via FCM data messages. End-to-end:

- **H → relay → T:** inbound SMS, send-status acks, contacts list, message history.
- **T → relay → H:** outbound SMS commands, history requests, contact-sync requests.

Two layers of auth:

1. **Account key** (`X-Account-Key` header) — a shared secret you set once on the server and type into both phones. Gates the relay itself. Without it, every endpoint returns 401. This is what keeps strangers from using your Firebase billing if the APK or URL leaks.
2. **Per-pair secret** — a 32-byte random string issued at pair time, stored in `EncryptedSharedPreferences` on both phones. Required on every `send` / `updateToken` call. Even someone who steals your account key cannot read or inject into your specific bridge without this.

### What the Travel app can and can't do

- ✅ See incoming SMS in real time, with push.
- ✅ Send SMS — recipient sees H's number.
- ✅ Browse and search contacts pulled from H.
- ✅ Tap-to-dial — opens T's dialer with the number prefilled. **The call uses T's SIM**, so the recipient sees T's number, not H's. Bridging audio so calls "appear from H" needs a SIP/VoIP setup, which is out of scope.
- ❌ MMS / images.

### Rotating the account key

If you think the key leaked:

1. Generate a new one, update `functions/.env`, `firebase deploy --only functions`.
2. On each phone: Settings → Relay server → paste the new key → Save. Pairing survives; nothing else needs changing.

## Telegram-bot setup (alternative, ~10 minutes)

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

## SMS fallback (works without internet)

If Telegram is unreachable (censored network, no data), Nomad automatically falls back to SMS. You configure a list of **trusted travel numbers** in Settings; from those numbers you can text H with a `#` command prefix:

| SMS command | Effect |
|---|---|
| `#send +15551234567 hello` | Send SMS from H to a number |
| `#send mom hello` | Send SMS to a contact name |
| `#reply hello` | Reply to the active thread |
| `#to mom` | Set active thread |
| `#last 5` | Last 5 messages in/out (reply via SMS) |
| `#status` | Bridge status |
| `#help` | Command reference |

When enabled, **inbound SMS that can't reach Telegram** are forwarded via SMS to the most recent trusted travel number that issued a command. Each leg is one SMS from H's SIM, so watch carrier SMS costs — but it works with zero internet on either end.

How to set up: Settings → **SMS fallback** section → add your travel number to the allowlist → Save. On your travel SIM, text H: `#help` to confirm it's working.

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
