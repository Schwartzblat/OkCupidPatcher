# OkCupidPremiumPatcher

Patches `com.okcupid.okcupid` by injecting a hook module built with
[stitch](https://github.com/Schwartzblat/Stitch) and
[ArtHooks](https://github.com/Schwartzblat/ArtHooks).

Every app-specific name the hooks need is discovered at patch time by a regex
signature in `artifactory_generator/`, so the patcher survives app updates
instead of pinning obfuscated names that change every release.

## Features

- **Premium unlocked** — every carrier of the A-List entitlement check returns true.
- **Incognito** enabled.
- **No likes cap** and **unlimited rewinds**.
- **Unblurred photos** in *Interested in You* — both the URL that gets loaded
  and the blur the card draws.
- **Real names** on the gated cards, in place of `------`.
- **Tap a gated card to open the real profile**, instead of a dead card.
- **Automatic identity sweep** — walks the whole *Interested in You* list in the
  background, recovers who each anonymized card is, and stores the result in a
  local SQLite database (`files/likes_cards.db`). It runs incrementally: a
  routine page load costs one request, and a new like triggers another pass.
- **A progress bar while the sweep runs**, at the top of the app window: how
  many cards carry a recovered id out of how many are known, plus the stage
  the pass is in. It tracks ids rather than names, because names are looked up
  in one pass at the very end -- a bar following them would sit still for the
  whole walk. Fades out once the pass finishes.

Unblurred photos, real names, tappable cards and the sweep all work because each
page cursor the server returns is the user id of that page's last entry. Anchoring
a walk on an id already known moves the window onto a different offset, which is
what lets the sweep name every card rather than one in twenty.

## Requirements

- Python 3.11+ with `stitch~=1.4.3`
- JDK 17+
- Android SDK (`$ANDROID_HOME`, or `sdk.dir` in `smali_generator/local.properties`)

```bash
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
```

## Usage

```bash
# 1. build the hook module (isolates Java errors from patching errors)
(cd smali_generator && ./gradlew assembleRelease)

# 2. unit tests for the pure core -- no device needed
(cd smali_generator && ./gradlew test)

# 3. patch: bundle in, bundle out
rm -rf ./temp                      # stitch refuses an existing temp dir
python main.py -p okcupid.xapk -o okcupid-patched.xapk --arch arm64-v8a

# 4. install. Use -r: a plain install uninstalls first and wipes the session.
unzip -o okcupid-patched.xapk -d out && adb install-multiple -r out/*.apk
```

Useful flags:

| Flag | Effect |
|---|---|
| `--arch` | ABI whose `libarthooks.so` is injected. Default `arm64-v8a`; use `x86_64` for an emulator or the hooks will not load. |
| `--no-sign` | Leave the output unsigned. |
| `-g/--google-api-key` | Replace the app's `google_api_key` resource. |

## Layout

| Path | What |
|---|---|
| `main.py` | Registers the finders and runs the patch. |
| `artifactory_generator/` | Signature finders; each yields `{{KEY}}` → value. |
| `smali_generator/` | Gradle module holding the hooks, injected as dex + jni libs. |
