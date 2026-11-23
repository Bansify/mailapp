# 03 · Cloudflare

Everything Mail runs on Cloudflare: one Worker, one D1 database, one Pages project, plus
DNS, Email Routing and secrets.

## Components

| Piece | Name | Purpose |
|---|---|---|
| Worker | `mail` | API at `mail.bansify.com/api/*`; also the `email()` handler for inbound mail |
| D1 | `mail` (`87854cea-7feb-46f3-ab99-2a2137f31105`) | `mail_messages`, `mail_settings` |
| Pages | `bansifymail` | the webmail SPA at `mail.bansify.com` |
| Route | `mail.bansify.com/api/*` → `mail` | API traffic; the rest of the host is Pages |
| Email Routing | catch-all `*@bansify.com` → Worker `mail` | inbound delivery |

## Worker configuration (`worker/wrangler.toml`)

```
name       = "mail"
main       = "src/index.ts"
route      = mail.bansify.com/api/*
D1         = mail

APP_DOMAIN        = "bansify.com"
AUTH_HUB_URL      = "https://api.bansify.com"
AUTH_SIGNIN_URL   = "https://accounts.bansify.com"
MAIL_URL          = "https://mail.bansify.com"
ENVIRONMENT       = "production"
DAILY_SEND_LIMIT  = "30"          # outbound mails per account per day
MAX_INBOUND_BYTES = "10485760"    # 10 MB inbound cap
```

`ALLOWED_ORIGINS` stays empty in production — the trusted-origin list comes from the
accounts Worker policy, not from a comma-separated override.

## Secrets (Cloudflare secret store only — never in the repo)

| Secret | Used for |
|---|---|
| `RESEND_API_KEY` | outbound mail through Resend |
| `ACCOUNTS_INTERNAL_TOKEN` | server-to-server calls to the accounts API |

Set with `wrangler secret put <NAME>` or through the dashboard. Rotating a secret requires
a redeploy of the Worker version that references it.

## Email: inbound and outbound

**Inbound**

1. MX records for `bansify.com` point at `route1/2/3.mx.cloudflare.net`
2. Email Routing's catch-all sends `*@bansify.com` to the Worker
3. the Worker's `email()` handler stores the message in D1 (`folder = 'inbox'`) keyed by
   the local part (`to_username`)

**Outbound**

- Sent through **Resend** with DKIM on `bansify.com`
- `send_email` binding is intentionally absent — Cloudflare Email Sending requires the
  Workers Paid plan
- SPF: `v=spf1 include:_spf.mx.cloudflare.net ~all`
- DKIM: `resend._domainkey.bansify.com`, `resend._domainkey.noreply.bansify.com`
- DMARC: `_dmarc.bansify.com` → `v=DMARC1; p=none; rua=mailto:dmarc@bansify.com; fo=1`

Deliverability notes: a brand-new domain lands in spam regardless of perfect
authentication. Warm up slowly, ask recipients to mark *not spam*, and move DMARC to
`p=quarantine` after a month of clean reports.

## Database

`mail_messages` columns that matter:

| Column | Notes |
|---|---|
| `id`, `to_username` | `to_username` is the local part of the recipient address |
| `from_email`, `from_name`, `to_email`, `cc_emails`, `bcc_emails` | envelope |
| `subject`, `snippet`, `body_text`, `body_html`, `body_gzip` | body; large bodies are gzipped |
| `folder` | `inbox` \| `sent` \| `drafts` \| `trash` |
| `is_read`, `is_starred`, `provider_id`, `received_at` | state and ordering |

Indexes exist on `(to_username, folder, received_at)` and starred rows; a full scan here
would burn the D1 read quota quickly.

## Free-tier limits worth knowing

| Limit | Free | Paid ($5/mo) |
|---|---|---|
| Databases | 10 | 1,00,000 included |
| Size per database | 500 MB | 10 GB |
| Rows read | 5 M/day | 25 B/month included |
| Rows written | 1,00,000/day | 5 crore/month included |
| Queries per Worker invocation | 50 | 1,000 |

Attachments and large bodies must not live in D1 — use object storage and keep metadata
here.

## Deploying

```
cd worker
npm install
npx wrangler deploy            # uses worker/wrangler.toml
```

Then verify:

```
curl https://mail.bansify.com/api/health
```

The health payload reports which bindings are wired (`d1`, `emailBinding`, `provider`).
A missing binding shows up here before users notice.

## Hard-won rules

1. **Always pass explicit `vars` when deploying.** Inheriting bindings has silently
   restored stale dashboard values (once pointing at `gothwadtech.com`).
2. **Pages custom domains are one-per-project.** A hostname attached to another project
   fails with `8000018 already added`.
3. **A CNAME must point at the project that owns the domain**, otherwise the site 522s.
4. **Zone DNS edits need `Zone:DNS:Edit`** — an account-scoped token returns
   `9106 Authentication failed` on DNS and Workers endpoints.
5. **The repo wins.** If you change something in the dashboard, copy it back into
   `wrangler.toml` the same day.
