#!/usr/bin/env python3
"""Install our apps from their own sources: resolve the newest APK, push it, pm install -r it.

apps.txt at the repository root lists each app - its package name, where it comes from (a GitHub
release, F-Droid, or a plain URL), and for GitHub which asset to take. Resolving happens on this
computer, from a source that was named rather than found by search, and GitHub releases are checked
against the sha256 the release itself publishes.

Nothing is ever uninstalled: an app already on the TV is skipped, --update installs over the copy
there, and a build signed differently than the installed one is refused with that copy left exactly
as it was (ISSUES.md 17).

Usage:
    TV_HOST=192.168.1.19 tools/venv/bin/python -I tools/install_apps.py              # all of apps.txt
    TV_HOST=192.168.1.19 tools/venv/bin/python -I tools/install_apps.py net.mrowser  # just these
    TV_HOST=192.168.1.19 tools/venv/bin/python -I tools/install_apps.py --update     # install over what is there
    tools/venv/bin/python -I tools/install_apps.py --dry-run                         # resolve only, no TV

Pushing a big APK (TizenTube is about 80 MB) works over b64push.py in 48 KB shell chunks but takes a
while; set TV_ADB_BINARY to a Google adb and the push becomes one `adb push` (tools/native-adb.md).

Exit status: 0 every app is installed or was already; 1 an app failed to resolve, download, push or
install; 2 the command line, apps.txt, or the TV connection was refused before anything was sent.
"""
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "apps.txt"
TV_PY = ROOT / "tools" / "tv.py"
B64PUSH = ROOT / "tools" / "b64push.py"
SOURCES = ("github", "fdroid", "url")
MAX_BYTES = 500 * 1024 * 1024
USER_AGENT = "mytv-install-apps (tools/install_apps.py)"


def version_of(package):
    """The installed versionName, read from the whole dumpsys and filtered here: a `grep -m1` on the
    TV closes the pipe early and dumpsys then writes its Broken-pipe warning into our output (ISSUES 10)."""
    for line in tv(f"dumpsys package {package}").splitlines():
        if "versionName=" in line:
            return line.strip().split("=", 1)[1]
    return ""


class Refused(Exception):
    """A problem answered before anything was sent, or an app that could not be installed."""


def stop(message):
    print(message, file=sys.stderr)
    raise SystemExit(2)


def venv_python():
    """The venv interpreter tv.py and b64push.py need for adb_shell, or this one."""
    for name in ("bin/python", "Scripts/python.exe"):
        candidate = ROOT / "tools" / "venv" / name
        if candidate.exists():
            return str(candidate)
    return sys.executable


def tv(command):
    """One shell command on the TV, as tv.py takes it; its stdout and stderr joined."""
    done = subprocess.run([venv_python(), "-I", str(TV_PY), command],
                          capture_output=True, text=True)
    return (done.stdout + done.stderr).strip()


class Entry:
    def __init__(self, line, where):
        parts = line.split()
        if len(parts) < 3 or len(parts) > 4:
            raise Refused(f"{where}: expected '<package> <github|fdroid|url> <id> [asset pattern]', got: {line}")
        if parts[1] not in SOURCES:
            raise Refused(f"{where}: source must be one of {', '.join(SOURCES)}, got: {parts[1]}")
        self.package, self.source, self.subject = parts[0], parts[1], parts[2]
        self.pattern = parts[3] if len(parts) == 4 else ""


def entries(path=MANIFEST):
    rows = []
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        try:
            rows.append(Entry(line, f"{path.name}:{number}"))
        except Refused as problem:
            stop(problem)
    if not rows:
        stop(f"{path} lists no apps")
    return rows


def fetch_json(url):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def resolve(entry):
    """(url, sha256hex or None, a line saying what this is)."""
    if entry.source == "github":
        release = fetch_json(f"https://api.github.com/repos/{entry.subject}/releases/latest")
        assets = [a for a in release.get("assets", [])
                  if a.get("name", "").lower().endswith(".apk")
                  or a.get("content_type") == "application/vnd.android.package-archive"]
        if entry.pattern:
            want = entry.pattern.lower()
            assets = [a for a in assets if want in a.get("name", "").lower()]
        if not assets:
            raise Refused(f"{entry.subject}: no APK in the latest release matches {entry.pattern or 'it'}")
        if len(assets) > 1:
            raise Refused(f"{entry.subject}: {len(assets)} APKs and no pattern to choose between them: "
                          + ", ".join(a["name"] for a in assets))
        asset = assets[0]
        digest = asset.get("digest") or ""
        sha = digest.split(":", 1)[1] if digest.startswith("sha256:") else None
        return (asset["browser_download_url"], sha,
                f"{entry.subject} {release.get('tag_name', '?')} ({asset['name']}, "
                f"{asset['size'] / 1e6:.1f} MB)")
    if entry.source == "fdroid":
        package = fetch_json(f"https://f-droid.org/api/v1/packages/{entry.subject}")
        code = package.get("suggestedVersionCode")
        if not code:
            raise Refused(f"F-Droid publishes no suggested version for {entry.subject}")
        name = next((p.get("versionName", "") for p in package.get("packages", [])
                     if p.get("versionCode") == code), str(code))
        return f"https://f-droid.org/repo/{entry.subject}_{code}.apk", None, f"F-Droid {entry.subject} {name}"
    return entry.subject, None, "url listed in apps.txt"


def download(url, destination, sha):
    """Write url to destination; check the size, the sha256 when one is known, and that it is a zip."""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    total = 0
    written = 0
    digest = hashlib.sha256()
    with urllib.request.urlopen(request, timeout=60) as response, open(destination, "wb") as out:
        total = int(response.headers.get("Content-Length") or 0)
        if total > MAX_BYTES:
            raise Refused(f"{total / 1e6:.0f} MB is more than this script will pull in one go")
        while True:
            chunk = response.read(256 * 1024)
            if not chunk:
                break
            out.write(chunk)
            digest.update(chunk)
            written += len(chunk)
            if total:
                print(f"\r    downloading {written / 1e6:6.1f} / {total / 1e6:.1f} MB", end="", flush=True)
    if total:
        print()
    with open(destination, "rb") as check:
        if check.read(4)[:2] != b"PK":
            raise Refused("what came down is not a zip, so not an APK")
    if sha and digest.hexdigest() != sha:
        raise Refused(f"sha256 does not match the release's: expected {sha}, got {digest.hexdigest()}")
    return written


def push(local, remote):
    """The file onto the TV: Google adb's push when TV_ADB_BINARY is set, else b64push.py."""
    adb = os.environ.get("TV_ADB_BINARY")
    if adb:
        done = subprocess.run([adb, "-s", os.environ["TV_HOST"], "push", str(local), remote],
                              capture_output=True, text=True)
        if done.returncode != 0:
            raise Refused(f"adb push failed: {(done.stderr or done.stdout).strip()[-400:]}")
        return "adb push"
    done = subprocess.run([venv_python(), "-I", str(B64PUSH), str(local), remote],
                          capture_output=True, text=True)
    if done.returncode != 0:
        raise Refused(f"b64push.py failed: {(done.stderr or done.stdout).strip()[-400:]}")
    return "b64push.py"


def install(entry, remote):
    """pm install -r, and the answer for this TV: installed, refused, or still not there."""
    answer = tv(f"pm install -r {remote}")
    if "Success" in answer:
        return None
    if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in answer:
        return ("is signed differently than the copy already installed, so it was refused and the "
                "installed app was left alone (ISSUES.md 17)")
    return f"pm install -r said: {answer.splitlines()[-1] if answer.splitlines() else 'nothing'}"


def main(argv):
    update = "--update" in argv
    dry_run = "--dry-run" in argv
    wanted = [a for a in argv if not a.startswith("--")]
    if "--help" in argv or "-h" in argv:
        print(__doc__)
        return 0
    unknown = [a for a in argv if a.startswith("--") and a not in ("--update", "--dry-run")]
    if unknown:
        stop(f"unknown option {unknown[0]}\n{__doc__}")

    try:
        chosen = entries()
        if wanted:
            by_name = {e.package: e for e in chosen}
            missing = [name for name in wanted if name not in by_name]
            if missing:
                stop(f"not in {MANIFEST.name}: {', '.join(missing)}")
            chosen = [by_name[name] for name in wanted]
    except OSError as problem:
        stop(f"cannot read {MANIFEST.name}: {problem}")

    present = set()
    if not dry_run:
        if not os.environ.get("TV_HOST"):
            stop("set TV_HOST to the TV's IP address, e.g. TV_HOST=192.168.1.19")
        if "error" in tv("echo tv-ok").lower() or "tv-ok" not in tv("echo tv-ok"):
            stop("the TV did not answer; check TV_HOST and that debugging is on")
        present = {line.split(":", 1)[1] for line in tv("pm list packages").splitlines()
                   if line.startswith("package:")}

    failures = 0
    with tempfile.TemporaryDirectory(prefix="mytv-apps-") as scratch:
        for entry in chosen:
            print(entry.package)
            if dry_run:
                try:
                    url, _, about = resolve(entry)
                    print(f"    {about}\n    {url}")
                except (Refused, OSError, ValueError, json.JSONDecodeError) as problem:
                    print(f"    refused: {problem}")
                    failures += 1
                continue


            if entry.package in present and not update:
                print(f"    already installed ({version_of(entry.package)})")
                continue

            remote = f"/data/local/tmp/{entry.package}.apk"
            local = Path(scratch) / f"{entry.package}.apk"
            try:
                url, sha, about = resolve(entry)
                print(f"    {about}")
                size = download(url, local, sha)
                print(f"    pushed with {push(local, remote)} ({size / 1e6:.1f} MB)")
                problem = install(entry, remote)
                if problem:
                    raise Refused(problem)
                check = tv(f"pm list packages {entry.package}")
                if f"package:{entry.package}" not in check:
                    raise Refused("pm install -r reported Success but the package is not listed")
                print(f"    installed {version_of(entry.package) or 'ok'}")
                present.add(entry.package)
            except (Refused, OSError, ValueError, json.JSONDecodeError) as problem:
                print(f"    refused: {problem}")
                failures += 1
            finally:
                tv(f"rm -f {remote}")

    if failures:
        print(f"{failures} of {len(chosen)} app(s) could not be installed")
        return 1
    if dry_run:
        print(f"all {len(chosen)} app(s) resolved; nothing was sent to the TV")
        return 0
    print(f"all {len(chosen)} app(s) installed or already present")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
