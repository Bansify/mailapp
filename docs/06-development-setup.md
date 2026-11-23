# 06 · Development setup

## Requirements

| Tool | Version |
|---|---|
| JDK | 17 |
| Android SDK | API 36 (target), API 26 (minimum) |
| Gradle | via the wrapper (`./gradlew`) |
| Node | not required for the app; only for the Worker in `Bansify/mail` |

## First run

```
git clone https://github.com/Bansify/mailapp.git
cd mailapp
./gradlew :app:assembleDebug
```

JitPack builds the shared SDK on first resolve — the first build is slow, later ones are
cached.

## Static checkers

Both must pass before a PR is opened:

```
python3 tools/check_android_scaffold.py   # manifest, resources, workflow wiring
python3 tools/check_docs.py               # required docs and README sections
```

They do not compile the app — CI does that.

## Repository rules

1. **One change per PR.** Unrelated edits make review and rollback harder.
2. **Never leave a fix only in the Cloudflare dashboard** — commit it, or the next deploy
   silently reverts it.
3. **Secrets never enter the repo.** Cloudflare secret store for Workers, GitHub secrets
   for CI.
4. **Supplied artwork is copied byte-for-byte.** Verify with `sha256sum`; never redraw.
5. **`TASK.md` is at its size ceiling** — do not append to it; add a new file instead.

## Branch and merge

- branch from `main`
- PR title in the imperative: `fix: …`, `feat: …`, `docs: …`
- wait for CI, then squash-merge
- after merging a release-relevant change, tag a version so the APK exists

## Talking to the API by hand

```
# session
curl -i https://api.bansify.com/api/auth/me

# mail list (needs a bearer token)
curl -H "Authorization: Bearer <token>" \
     "https://mail.bansify.com/api/mail/messages?folder=inbox&limit=10"
```

## Where the app's behaviour is decided

| Behaviour | File |
|---|---|
| Startup order | `MailApplication.kt` |
| Token handling | `auth/MailAuth.kt` |
| Endpoints | `data/remote/MailApiClient.kt` |
| Folder mapping | `data/model/MailModels.kt` (`ServerFolder`) |
| Sync + errors | `ui/viewmodel/MailViewModel.kt` |
| Shell, banner, panes | `ui/navigation/MailNavGraph.kt` |
