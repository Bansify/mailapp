# Gothwad Mail (Android)

A Material 3 email client for **Gothwad Mail**, signed in with a **Gothwad Account**.
The app contains no demo or prototype data: every message, mailbox setting and account comes
from Gothwad Accounts OAuth 2.0 and the Gothwad Mail Worker REST API.

## Features

- **Gothwad Accounts sign-in**: OAuth 2.0 authorization code flow with PKCE (`S256`) opened in
  Custom Tabs, with a persisted `state`/`code_verifier`, strict callback validation and a
  ten-minute login window.
- **Multi-account with encrypted storage**: accounts are keyed by the immutable `userId` (`sub`)
  and stored in an Android Keystore–backed, AEAD-encrypted file. Access tokens are refreshed
  single-flight per account when less than 60 seconds remain, and rotated refresh tokens are
  persisted atomically.
- **Peer account discovery**: mutually certificate-verified sibling Gothwad apps on the same
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
├── auth/                      Gothwad Accounts OAuth 2.0 + PKCE and session storage
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
| Authorize | `GET https://accounts.gothwadtech.com/api/oauth/authorize` (`S256` PKCE) |
| Account chooser | `https://accounts.gothwadtech.com/signin?choose=1&next=<authorize>` |
| Token exchange / refresh | `POST https://accounts.gothwadtech.com/api/oauth/token` |
| Profile | `GET https://accounts.gothwadtech.com/api/oauth/userinfo` |
| Server grant revoke | `POST https://accounts.gothwadtech.com/api/oauth/revoke` |
| Session & quotas | `GET https://mail.gothwadtech.com/api/session` |
| Mailbox settings | `GET` / `PATCH https://mail.gothwadtech.com/api/mail/settings` |
| Message list & search | `GET https://mail.gothwadtech.com/api/mail/messages` |
| Message detail | `GET https://mail.gothwadtech.com/api/mail/messages/:id` (`If-None-Match`) |
| Mutations | `PATCH /api/mail/messages/:id`, `POST /api/mail/messages/batch`, `DELETE /api/mail/messages/:id`, `DELETE /api/mail/trash` |
| Drafts | `POST /api/mail/drafts`, `DELETE /api/mail/drafts/:id` |
| Send | `POST /api/mail/send` (returns `remainingToday`) |

The `Authorization: Bearer` header is attached **only** to `mail.gothwadtech.com` requests that
need it, redirects are never followed, and the session probe is sent without a token.

### Sign-in states on device

- **Sign out of this account** (`revokeOnServer = false`): removes Mail tokens locally only.
- **Disconnect Mail grant on the server** (`revokeOnServer = true`): calls `POST /oauth/revoke`
  first, then clears local tokens, reporting the truthful outcome.
- **Remove account from this device**: records a tombstone, deletes the account and its cached
  mail, and notifies every verified sibling Gothwad app through
  `notify_account_removed`.

## Build and test

```bash
python3 tools/check_docs.py             # README links, required sections, endpoint table
python3 tools/check_android_scaffold.py # XML, manifest, version catalog, wrapper, release workflow
./gradlew testDebugUnitTest assembleDebug
```

The two `tools/` scripts are static checks copied in spirit from `GothwadTech/accountsapp`. They
run in `.github/workflows/planning.yml` (documentation only) and at the start of the Android CI
build, so a broken README link, a malformed manifest or an unsigned release contract fails fast
instead of after a full Gradle build. They are **not** a substitute for the Gradle tests.

### Workflows

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `android-ci.yml` | push, pull request, manual | static checks, then `testDebugUnitTest`, `lintDebug`, `assembleDebug`; uploads the test report and debug APK |
| `planning.yml` | push, pull request, manual | `python3 tools/check_docs.py` |
| `release.yml` | push to `main`/`master`, `v*` tags, manual | builds Release APK + Debug APK + Play AAB, zipaligns and signs them, publishes a GitHub Release |

Unit tests cover PKCE (`S256` vector), callback validation, single-flight refresh and credential
eviction, sign-out semantics, peer trust verification and discovery, the Mail API client
(bearer scoping, ETag `304`, send/draft/trash mapping) and repository sync with per-account
isolation.

Release builds are never signed with the debug key. `.github/workflows/release.yml` builds the
Release APK, Debug APK and Play Store AAB and signs the release artifacts with
`RELEASE_KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

## Manual device checks

1. Cold start with no signed-in account shows the **Welcome to Gothwad Mail** screen (no demo
   mail), with **Sign in with Gothwad** and **Use another account**.
2. Signing in opens Custom Tabs; returning through
   `https://mail.gothwadtech.com/oauth/callback` (or `com.gothwad.mail:/oauth/callback`) lands on
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
