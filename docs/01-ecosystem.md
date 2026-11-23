# 01 · Ecosystem

Bansify is a small ecosystem of products that share **one identity**. A user signs in once
at `accounts.bansify.com` and every other product trusts that session.

## Products and domains

| Product | Web | Android | Status |
|---|---|---|---|
| Accounts (identity) | `accounts.bansify.com` | `Bansify Accounts` | live |
| Mail | `mail.bansify.com` | `Bansify Mail` (this repo) | live |
| Drive | `drive.bansify.com` | `Bansify Drive` | live (web), app pending |
| Meet | `meet.bansify.com` | — | live |
| Pay (billing) | `pay.bansify.com` | — | live (mock provider) |
| AI | `ai.bansify.com` | `Bansify AI` | live |
| Store | `store.bansify.com` | `Kiwi Store` | live |
| Store admin | `storeadmin.bansify.com` | — | live, admin-only |
| Store console | `storeconsole.bansify.com` | — | live, public developer console |
| GitPlus | `gitplus.bansify.com` | — | live |
| Browser / Launcher / Manager / Computer / Chat / Indogram / Notes / Contacts / Education / PrepMate / PW Parent | — | respective repos | live, APK only |
| Official website | `bansify.com`, `www.bansify.com` | — | live |

## The identity layer

```
        ┌────────────────────────────┐
        │  accounts.bansify.com      │  sign-in UI
        │  api.bansify.com           │  OAuth 2.0 + PKCE, sessions, userinfo
        └─────────────┬──────────────┘
                      │ access token (scoped)
   ┌──────────────────┼──────────────────┬─────────────────┐
   ▼                  ▼                  ▼                 ▼
 Mail Worker      Drive Worker      Store Worker      Pay Worker
 (scope: mail)    (drive.read)      (store)           (pay)
```

- **Clients** are registered in the accounts D1 (`ecosystem_apps`) with their package name,
  signing-certificate SHA-256 and allowed scopes.
- **Scopes** are enforced per client. Mail needs
  `profile, email, offline_access, mail, account.read, account.profile.write`.
  If a scope is missing from the client record, the sign-in fails with
  *"requested scope is not permitted"* — fix the client row, not the app.
- **Cookies** are set on `.bansify.com`, so a session started at `accounts.bansify.com`
  is visible to every sibling subdomain. `isTrustedOrigin()` in the accounts Worker lists
  exactly which origins may make credentialled cross-origin calls (apex, `www`, and the
  named first-party subdomains). An origin missing from that list silently looks
  "signed out".
- **Native Android sign-in** uses the same OAuth flow through the shared SDK. The
  `gothwadRedirectScheme` manifest placeholder must match the application id
  (`com.bansify.mail`) or the callback never returns.

## Naming

The product is **Bansify**. Older code, database rows and docs may still say
`Gothwad` / `gothwadtech.com` — those are historical. Anything user-visible must say
Bansify and use `*.bansify.com`.

## What the app must never do

- Talk to any host other than `mail.bansify.com` (see `MailApiClient.GothwadMailHost`).
- Store tokens outside the SDK's encrypted store.
- Assume the user has only one account — the ecosystem supports up to six slots.
