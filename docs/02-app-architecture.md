# 02 · App architecture

## Shape of the app

```
com.bansify.mail
├── MailApplication            app startup; initialises the shared Accounts SDK *before* any auth read
├── auth/                      MailAuth facade over the SDK, endpoint constants, native auth models
│   └── broker/                peer-to-peer silent sign-in (Binder IPC) for sibling apps
├── data/
│   ├── local/                 Room: entities, DAOs, database
│   ├── model/                 MailFolder, ServerFolder, UI models
│   ├── remote/                MailApiClient (OkHttp), API DTOs, error mapping
│   └── repository/            MailRepository — the only thing that touches both sides
└── ui/
    ├── components/            drawer, account switcher, shared widgets
    ├── navigation/            MailNavGraph, routes
    ├── screens/               inbox, message, compose, settings, signed-out, native auth
    └── viewmodel/             MailViewModel + UI state
```

## Startup order (this order matters)

1. `MailApplication.onCreate()` → `GothwadAccounts.init(...)`
2. then `MailAuth` is touched for the first time
3. `MailViewModel` starts, calls `repository.ensureInitialized()`, then observes
   `auth.state` and `activeAccountFlow`
4. on `SignedIn`, accounts are copied into Room (`syncAccountsToCache`), then the first
   folder refresh runs

Reading the SDK before `init` throws a `lateinit` crash on launch. This has happened once
(v1.0.42); do not reorder these calls.

## Data flow

```
UI  →  MailViewModel  →  MailRepository  →  MailApiClient (HTTPS + Bearer token)
                                    │                │
                                    └── Room cache ◄─┘
```

- **Reads** always render from Room first, then refresh from the API.
- **Writes** (send, star, move, delete) go to the API first; the cache is updated from the
  response so a failed call never leaves a phantom row.
- **Refresh** replaces only the slice that mirrors the server folder; *Starred* is a
  cross-folder view and upserts without deleting.

## Folders

The Worker exposes four listable mailboxes: `inbox`, `starred`, `sent`, `drafts`, `trash`.
The UI has more (Primary, Promotions, Social, Updates, Important, All mail, Snoozed, Spam);
`ServerFolder.forMailFolder()` maps each UI folder onto exactly one server folder.

## Authentication

`MailAuth` is a thin facade — the app no longer ships its own PKCE client, token store or
callback activity. It exposes:

| Member | Purpose |
|---|---|
| `state` | `SignedIn` / `SignedOut` / `Initializing` with accounts |
| `accessToken()` | current token, refreshed by the SDK |
| `events` | sign-in / sign-out notifications |
| `signIn()`, `signOut()` | delegated to the SDK |

The API client attaches `Authorization: Bearer <token>` **only** to the mail host, and
refuses to send it anywhere else.

## Error surfacing

`MailViewModel` records `syncError`; `MailNavGraph` renders it as a banner with a Retry
button. Before this existed a failed fetch looked exactly like an empty mailbox — keep the
banner when changing the shell.

## Offline behaviour

Room holds threads, messages and settings. Opening the app offline shows the last synced
state; sending is not queued (the user sees the API error instead of a false "sent").

## Instrumentation

`CrashReporter` records the last fatal error to prefs so the next launch can show it instead
of a blank screen.
