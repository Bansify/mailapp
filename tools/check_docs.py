"""Check local Markdown file links and required documentation sections.

Mirrors `tools/check_docs.py` in GothwadTech/accountsapp, adapted to the single-module
Mail app: the app reads real mail through the Worker, so the docs that describe the endpoint
contract, the release secrets and the on-device checks must never drift from the code.
"""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
errors: list[str] = []

required_dirs = ("app", "gradle", ".github/workflows")
for folder in required_dirs:
    if not (root / folder).is_dir():
        errors.append(f"Missing scaffold directory: {folder}")

required_files = (
    "README.md",
    ".github/workflows/android-ci.yml",
    ".github/workflows/planning.yml",
    ".github/workflows/release.yml",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "gradle/libs.versions.toml",
    "gradle/wrapper/gradle-wrapper.jar",
    "gradlew",
)
for name in required_files:
    if not (root / name).exists():
        errors.append(f"Missing required file: {name}")

readme = root / "README.md"
if not readme.is_file():
    errors.append("README.md is missing")
else:
    text = readme.read_text(encoding="utf-8")

    for file in [readme, *sorted(root.glob("docs/*.md"))]:
        body = file.read_text(encoding="utf-8")
        for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", body):
            if "://" in target or target.startswith("#") or target.startswith("mailto:"):
                continue
            if not (file.parent / target.split("#", 1)[0]).exists():
                errors.append(f"{file.relative_to(root)}: broken link {target}")

    required_sections = (
        "## Architecture",
        "### Endpoints used",
        "## Build and test",
        "## Manual device checks",
    )
    for section in required_sections:
        if section not in text:
            errors.append(f"README.md: missing section {section!r}")

    # The release workflow cannot sign anything without these four repository secrets, so the
    # README has to keep naming them exactly.
    for secret in (
        "RELEASE_KEYSTORE_BASE64",
        "KEYSTORE_PASSWORD",
        "KEY_ALIAS",
        "KEY_PASSWORD",
    ):
        if secret not in text:
            errors.append(f"README.md: release secret {secret} is undocumented")

    # Every endpoint the client relies on must stay listed in the endpoint table.
    for endpoint in (
        "/api/oauth/authorize",
        "/api/oauth/token",
        "/api/mail/messages",
        "/api/mail/send",
        "/api/mail/trash",
        "/api/mail/drafts",
        "/api/mail/settings",
        "/api/session",
    ):
        if endpoint not in text:
            errors.append(f"README.md: endpoint {endpoint} is undocumented")

if errors:
    print("\n".join(errors), file=sys.stderr)
    raise SystemExit(1)
print("Documentation links, required files and README sections OK (not an Android build).")
