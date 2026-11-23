# 05 · Operations & troubleshooting

Symptom → cause → fix, for the failures this project has actually hit.

## The inbox is empty but webmail shows mail

**Cause (found once):** `MailViewModel` recorded `syncError` but nothing rendered it, so an
API failure looked identical to an empty mailbox.

**Check:** open the app, look for the red banner at the top of the canvas.
- *"Your Bansify Account session expired"* → token problem, sign in again
- *"Could not sync Bansify Mail: HTTP 403 …"* → scope problem, see below
- *"Could not sync Bansify Mail: HTTP 500 …"* → Worker error, check `wrangler tail`
- timeout → network

## Sign-in fails with "requested scope is not permitted"

The client record in the accounts D1 (`ecosystem_apps`) is missing a scope.
Mail needs `profile, email, offline_access, mail, account.read, account.profile.write`.
Fix the row, then sign in again — existing tokens keep their old scopes.

## App shows "Sign in" although the user is signed in (web)

The session probe must hit a real endpoint. A relative `/api/me` on a host without a Worker
returns the SPA's `index.html` with a 200, which used to be read as "signed out". Use
`api.bansify.com/api/auth/me`, and treat only a parsed JSON body with
`authenticated: false` as anonymous.

Also confirm the calling origin is in the accounts Worker's trusted list — `www.<domain>`
was missing once and produced exactly this symptom.

## Launch crash immediately after install

The Accounts SDK was read before `GothwadAccounts.init()`. Initialise first, in
`MailApplication.onCreate()`.

## Keyboard covers the composer / black screen behind it

`windowBackground` plus a `Surface` wrapper around the content — already applied in
v1.0.46. Do not remove them.

## Inbound mail never arrives

1. Email Routing → **Activity** — did Cloudflare accept the message?
2. If it was forwarded instead of delivered, the catch-all still points at an email
   address, not the Worker
3. Check the D1 row: `SELECT to_username, subject, received_at FROM mail_messages ORDER BY received_at DESC LIMIT 5`
4. If `to_username` has no matching account, the mail is stored but the app never lists it
   — send to the real username

## Outbound mail lands in spam

Authentication is not the problem if SPF, DKIM and DMARC all pass. It is domain age and
reputation. Warm up slowly, keep volume low, ask recipients to mark *not spam*, and keep
`DMARC p=none` until reports look clean.

## Store download links break after a release

They should not: they point at `store.bansify.com/api/dl/<repo>`. If a link 404s, the repo
has no release with an `.apk` asset, or the asset name contains `UNSIGNED`/`Debug` (those
are skipped on purpose).

## Admin API returns 401 / 403

- `401` → no valid session; sign in
- `403 Admin access required` → the username is not in `ADMIN_USERNAMES` (currently
  `pawan`), which is a Worker secret

## Where to look

| Question | Where |
|---|---|
| Is the Worker healthy? | `GET https://mail.bansify.com/api/health` |
| Did the Worker throw? | `npx wrangler tail mail` |
| Did mail arrive? | Email Routing → Activity |
| Is the token valid? | `GET https://api.bansify.com/api/auth/me` in the browser |
| What did the app send? | `adb logcat -s MailApiClient` |
