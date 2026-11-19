# Bansify Mail (Android)

A Material 3 email client for **Bansify Mail**, signed in with a **Bansify Account**.
The app contains no demo or prototype data: every message, mailbox setting and account comes
from Bansify Accounts OAuth 2.0 and the Bansify Mail Worker REST API.

## Features

- **Native sign-in (no browser required)**: a 100% Jetpack Compose sheet talks directly to
  `POST https://accounts.bansify.com/api/mobile/auth/*` — identifier check, password sign-in,
  six-digit 2FA, sign-up and password recovery (`forgot-password` / `reset-password`). It works on
  a device with **no browser installed at all**. The browser OAuth flow stays available as the
  secondary "Sign in with browser" button.
- **Zero-browser cross-app silent SSO**: when `com.gothwad.accounts` (or another verified sibling
  app) is already signed in on the device, Mail asks it for a one-time PKCE code over Binder IPC
  (`issue_peer_code`), redeems it through `/api/oauth/token` and signs in **automatically at
  startup — no browser, no permission screen**. **Every** account the peer holds is copied, not
  just the first one, and the first account keeps the active slot so the others do not look lost.
  If the peer refuses or is too old, the app falls back to the native sheet pre-filled with the
  discovered identity and states the exact reason (outdated sibling app, different signing key,
  peer signed out, …).
- **Second factor**: `202 mfa_required` moves the sheet to a verification step that accepts both a
  6-digit authenticator code and a one-time recovery code; a missing or expired challenge returns
  the user to the identifier step instead of failing silently.
- **Bansify Accounts sign-in (secondary)**: OAuth 2.0 authorization code flow with PKCE (`S256`)
  opened in Custom Tabs, with a persisted `state`/`code_verifier`, strict callback validation and a
  ten-minute login window.
- **Multi-account with encrypted storage**: accounts are keyed by the immutable `userId` (`sub`)
  and stored in an Android Keystore–backed, AEAD-encrypted file. Access tokens are refreshed
  single-flight per account when less than 60 seconds remain, and rotated refresh tokens are
  persisted atomically.
- **Peer account discovery**: mutually certificate-verified sibling Bansify apps on the same
  device (`com.gothwad.accounts`, `com.gothwad.drive`, `com.gothwad.store`, `com.gothwad.tube`,
  sample builds) publish a non-secret account directory through a `ContentProvider`. Discovered
  accounts appear under **Available on this device** and sign in with one tap. Tokens never cross
  IPC.
- **Per-account offline cache**: Room stores every message under `(accountEmail, serverId)` with
  its `ETag` and body, so account switching is instant and never leaks mail between accounts.
- **Live sync**: folders map onto the Worker's `inbox` / `starred` / `sent` / `drafts` / `trash`
  mailboxes; message bodies are fetched once and re-validated with `If-None-Match` (`304` reuses
  the cached body).
- **Mutations with undo**: star, read/unread, move to Trash, undo (move back to inbox), permanent
  delete and empty Trash map onto the documented `PATCH`, batch and `DELETE` endpoints.
- **Compose, drafts and sending**: drafts are stored on the Worker and their server UUIDs are
  cached; sending consumes the draft and reports the remaining daily quota, or the exact
  `429 daily_limit` / `503 sending_not_configured` error.
- **Search**: instant local filtering plus a debounced server search with `q`, `unread`,
  `starred` and `hasAttachment` filters.
- **Adaptive layout**: two-pane list–detail on expanded widths, navigation drawer and bottom
  navigation on compact widths, with System/Light/Dark theme preferences.

## Architecture

```
com.gothwad.mail
├── auth/                      Bansify Accounts OAuth 2.0 + PKCE and session storage
│   ├── Pkce.kt                S256 verifier/challenge, state generation
│   ├── CallbackValidator.kt   scheme/host/path/state/code validation
│   ├── EncryptedTokenStore.kt Keystore-backed AEAD store (AtomicFile)
│   ├── MailOAuthService.kt    token exchange, refresh, userinfo, revoke (client in MailOAuthClient.kt)
│   ├── MailAuthSessionManager.kt  multi-account state, single-flight refresh, sign-out modes
│   ├── GothwadMailAuth.kt     app-wide facade: Custom Tabs sign-in, peer discovery, token provider
│   ├── AccountCallbackActivity.kt  App Link / custom scheme redirect trampoline
│   └── broker/                AccountDirectoryProvider, PeerBrokerClient, TrustVerifier, models
├── data/
│   ├── local/                 Room database v2, DAO and type converters
│   ├── model/                 Entities, folders, retention policy, mapping helpers
│   ├── remote/                Mail Worker models and MailApiClient
│   └── repository/            MailRepository (offline-first sync) and MailMappers
└── ui/                        Compose screens, navigation, state and view model
```

### Endpoints used

| Purpose | Request |
| --- | --- |
| Authorize | `GET https://accounts.bansify.com/api/oauth/authorize` (`S256` PKCE) |
| Account chooser | `https://accounts.bansify.com/signin?choose=1&next=<authorize>` |
| Token exchange / refresh | `POST https://accounts.bansify.com/api/oauth/token` |
| Profile | `GET https://accounts.bansify.com/api/oauth/userinfo` |
| Server grant revoke | `POST https://accounts.bansify.com/api/oauth/revoke` |
| Session & quotas | `GET https://mail.bansify.com/api/session` |
| Mailbox settings | `GET` / `PATCH https://mail.bansify.com/api/mail/settings` |
| Message list & search | `GET https://mail.bansify.com/api/mail/messages` |
| Message detail | `GET https://mail.bansify.com/api/mail/messages/:id` (`If-None-Match`) |
| Mutations | `PATCH /api/mail/messages/:id`, `POST /api/mail/messages/batch`, `DELETE /api/mail/messages/:id`, `DELETE /api/mail/trash` |
| Drafts | `POST /api/mail/drafts`, `DELETE /api/mail/drafts/:id` |
| Send | `POST /api/mail/send` (returns `remainingToday`) |

The `Authorization: Bearer` header is attached **only** to `mail.bansify.com` requests, exactly
the same way for every `/api/*` endpoint including `GET /api/session`, and redirects are never
followed.

### Sign-in states on device

- **Sign out of this account** (`revokeOnServer = false`): removes Mail tokens locally only.
- **Disconnect Mail grant on the server** (`revokeOnServer = true`): calls `POST /oauth/revoke`
  first, then clears local tokens, reporting the truthful outcome.
- **Remove account from this device**: records a tombstone, deletes the account and its cached
  mail, and notifies every verified sibling Bansify app through
  `notify_account_removed`.

## Build and test

```bash
python3 tools/check_docs.py             # README links, required sections, endpoint table
python3 tools/check_android_scaffold.py # XML, manifest, version catalog, wrapper, release workflow
./gradlew testDebugUnitTest assembleDebug
```

The two `tools/` scripts are static checks copied in spirit from `Bansify/accountsapp`. They
run in `.github/workflows/planning.yml` (documentation only) and at the start of the Android CI
build, so a broken README link, a malformed manifest or an unsigned release contract fails fast
instead of after a full Gradle build. They are **not** a substitute for the Gradle tests.

### Workflows

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `android-ci.yml` | push, pull request, manual | static checks, then `testDebugUnitTest`, `lintDebug`, `assembleDebug`; uploads the test report and debug APK |
| `planning.yml` | push, pull request, manual | `python3 tools/check_docs.py` |
| `release.yml` | **every push**, `v*` tags, manual | builds Release APK + Debug APK + Play AAB and publishes them as a GitHub Release |

### Where the release APK appears

`release.yml` runs on every push, so a downloadable APK always matches the latest commit:

- Push to `main`/`master`, a `v*` tag, or a manual run → a new **versioned release**
  `v1.0.<run_number>` with `…-Release.apk`, `…-Debug.apk`, `…-PlayStore.aab` and
  `SHA256SUMS.txt`.
- Push to any other branch → the single rolling pre-release tagged **`latest`**
  (*Latest build (branch)*) whose assets are replaced on every push, so preview versions never
  fill the release list.
- Without the signing secrets the run still succeeds and publishes the debug-signed APK plus an
  `…-Release-UNSIGNED.apk`; release artifacts are never signed with the debug key.

Unit tests cover PKCE (`S256` vector), callback validation, single-flight refresh and credential
eviction, sign-out semantics, peer trust verification and discovery, the Mail API client
(bearer scoping, ETag `304`, send/draft/trash mapping) and repository sync with per-account
isolation.

Release builds are never signed with the debug key. `.github/workflows/release.yml` builds the
Release APK, Debug APK and Play Store AAB and signs the release artifacts with
`RELEASE_KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

### Native auth endpoints (`/api/mobile/auth/*`)

| Purpose | Request |
| --- | --- |
| Identifier probe | `POST /api/mobile/auth/check-identifier` → `{ ok, exists, kind }` |
| Username probe | `POST /api/mobile/auth/check-username` → `{ ok, available, valid, email }` |
| Password sign-in | `POST /api/mobile/auth/signin` → tokens, or `202` with `mfa_required` + `challenge` |
| Second factor | `POST /api/mobile/auth/signin/mfa` (`challenge`, 6-digit `code`) |
| Sign-up | `POST /api/mobile/auth/signup` → `201` with tokens |
| Recovery | `POST /api/mobile/auth/forgot-password`, `POST /api/mobile/auth/reset-password` |
| Peer code (Mail as the peer) | `POST /api/mobile/auth/peer-code` (Bearer, returns a one-time `code`) |

`issue_peer_code` is the IPC method a signed-in sibling app answers: the caller passes
`target_client_id`, `redirect_uri`, `scope`, `code_challenge` and `code_challenge_method`; the
provider verifies the Binder caller with `TrustVerifier`, refreshes its own access token if needed,
and returns `{ ok, code, redirect_uri, scope }`. **Tokens never cross IPC** — only a single-use code
does. An untrusted caller gets `{ ok: false, error: "untrusted_caller" }`.

## Troubleshooting sign-in on a device

**The browser returns to the app but the welcome screen appears again.** The callback activity
used to run the token exchange in its own `lifecycleScope`; that scope is cancelled in `onDestroy`
as soon as the activity finishes itself after the redirect, which aborted the exchange and dropped
the fresh account (visible as a brief avatar flash before the welcome screen returns). The exchange
now runs on the application-owned scope, so it always completes. If you still see the welcome
screen, the app shows an explicit reason ("That sign-in attempt expired…", "The sign-in response
was invalid…") instead of failing silently.

**The redirect opens Chrome instead of the app.** The App Link
`https://mail.bansify.com/oauth/callback` is only verified when
`https://mail.bansify.com/.well-known/assetlinks.json` lists this app's signing certificate
SHA-256. Until it does, the `com.gothwad.mail:/oauth/callback` custom scheme still returns to the
app; the release workflow prints the certificate SHA-256 in its step summary for exactly this.

**The seed values below are not secrets.** `client_id` is public; the redirect must stay an App
Link plus the reverse-DNS custom scheme, and the PKCE `code_verifier` never leaves the device.

## Manual device checks

1. Cold start with no signed-in account shows the **Welcome to Bansify Mail** screen (no demo
   mail), with **Sign in with Bansify** and **Use another account**.
2. Signing in opens Custom Tabs; returning through
   `https://mail.bansify.com/oauth/callback` (or `com.gothwad.mail:/oauth/callback`) lands on
   the inbox with real messages.
3. Install `com.gothwad.accounts` with a different signed-in account: it appears under
   **Available on this device** both on the welcome screen and in the account switcher; tapping it
   pre-fills `login_hint` in the Accounts sign-in page.
4. Switch between two accounts: the list changes immediately and no message from the other
   account appears.
5. Open an unread message: it becomes read, the body loads once, and re-opening after a restart
   does not re-download it.
6. Swipe to Trash and press **Undo**; the message returns to its folder. Re-swipe, open Trash,
   **Empty Trash** clears the mailbox.
7. Send a message: the snackbar shows `Message sent — N remaining today`; exhausting the daily
   quota shows the `429 daily_limit` message.
8. Settings → **Mailbox retention**: switching between 500 / 1,500 / Store All persists on the
   Worker and survives a restart.
