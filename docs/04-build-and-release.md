# 04 · Build & release

## Local build

```
./gradlew :app:assembleDebug
```

The app id is `com.bansify.mail` (debug builds get the `.debug` suffix), `minSdk 26`,
`targetSdk 36`. Version is injected by CI through `-PversionCode` / `-PversionName`.

## Shared SDK

The app depends on the published Bansify Accounts SDK:

```
com.github.Bansify.accountsapp:core:v1.0.11
com.github.Bansify.accountsapp:ui:v1.0.11
```

Published from `Bansify/accountsapp` tags via JitPack. Bumping the SDK version is a normal
PR; the manifest placeholder `gothwadRedirectScheme` must stay equal to the application id.

## CI workflows

| Workflow | Trigger | What it does |
|---|---|---|
| `android-ci.yml` | push / PR | compiles, runs unit tests, runs the static checkers |
| `release.yml` | tag `v*` | builds, signs, publishes the GitHub Release |
| `planning.yml` | schedule | keeps the planning docs in check |

## Release artefacts

Every release publishes:

| File | Use |
|---|---|
| `BansifyMail-v<version>-Release.apk` | sideload / direct download |
| `BansifyMail-v<version>-PlayStore.aab` | Play Console upload |
| `BansifyMail-v<version>-Debug.apk` | debugging a reported issue |
| `BansifyMail-v<version>-Release.apk.idsig` | Android 11+ incremental install |
| `SHA256SUMS.txt` | integrity check |

**Downloads in the hub do not use these names.** The website links
`https://store.bansify.com/api/dl/mailapp`, which resolves the newest release and redirects
to its APK. That keeps the link valid across versions — do not put a versioned asset name
into any public page.

## Signing

The release keystore is supplied to CI as repository secrets. Nothing signing-related is
committed. If the keystore is lost, Play Store updates are impossible — keep an offline
copy.

## Versioning

- `versionCode` increments on every release
- `versionName` follows `MAJOR.MINOR.PATCH`
- the release workflow fails if the tag and `versionName` disagree

## Checklist before tagging

1. `tools/check_android_scaffold.py` and `tools/check_docs.py` pass
2. `./gradlew :app:lintDebug` is clean
3. CI green on `main`
4. changelog entry written
5. tag pushed → release workflow publishes
