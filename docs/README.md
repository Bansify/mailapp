# Bansify Mail — documentation

Everything about the Mail Android app, the product it belongs to, and the Cloudflare
infrastructure behind it.

| Document | What it covers |
|---|---|
| [01 · Ecosystem](01-ecosystem.md) | The Bansify products, domains, the shared Accounts identity and how the apps relate |
| [02 · App architecture](02-app-architecture.md) | Modules, data flow, offline cache, authentication, key classes |
| [03 · Cloudflare](03-cloudflare.md) | The Mail Worker, D1 database, Pages site, DNS, Email Routing and secrets |
| [04 · Build & release](04-build-and-release.md) | CI workflows, signing, versioning, APK/AAB naming, Play Store |
| [05 · Operations & troubleshooting](05-operations-troubleshooting.md) | Symptom → cause → fix for the failures we have actually hit |
| [06 · Development setup](06-development-setup.md) | Local setup, the shared SDK from JitPack, checkers, PR rules |

## The app in one paragraph

`Bansify Mail` (`com.bansify.mail`) is a Kotlin/Compose Android client for the Bansify
mail service. It talks only to `https://mail.bansify.com/api`, signs users in through the
shared **Bansify Accounts** SDK, and keeps a Room cache so the inbox opens instantly and
works offline. Inbound mail reaches the Cloudflare Worker through Email Routing, is stored
in the `mail` D1 database, and is fetched by the app over the same API the webmail uses.

## Repositories in play

| Repository | Role |
|---|---|
| `Bansify/mailapp` | This app |
| `Bansify/mail` | The Worker API and the webmail at `mail.bansify.com` |
| `Bansify/accounts` | The Accounts Worker — OAuth 2.0 + PKCE, sessions, user directory |
| `Bansify/accountsapp` | The shared Android SDK (`:core`, `:ui`) published to JitPack |
| `Bansify/bansify` | The official website (hub) at `bansify.com` |

## Ground rules

1. **The repo is the source of truth.** Never leave a fix only in the Cloudflare
   dashboard — commit it, or the next deploy will silently undo it.
2. **Never hard-code secrets.** They live in the Cloudflare secret store only.
3. **Supplied artwork is copied byte-for-byte.** Icons are never redrawn.
4. **Every change ships through a PR** with the static checkers passing.
