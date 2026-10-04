# OkCupidPremiumPatcher

Patches `com.okcupid.okcupid` by injecting a hook module built with
[stitch](https://github.com/Schwartzblat/Stitch) and
[ArtHooks](https://github.com/Schwartzblat/ArtHooks).

Every app-specific identifier is discovered at patch time by a regex signature
in `artifactory_generator/`, so the patcher keeps working across app updates
instead of pinning obfuscated names that change on every release.

**`NOTES.md` is the reference for what shipped and why** — the hook inventory,
each finder's anchor, stitch's finder-skip landmine, the install data-loss
warning and the honest scope limits. Read it before changing a finder. This file
is only how to run the thing.

## Requirements

- Python 3.11+ with `stitch~=1.4.3` (a `.venv/` here already has it)
- JDK 17+ (21 is what this was built with)
- Android SDK (`gradlew` needs `sdk.dir` in `smali_generator/local.properties`,
  or `$ANDROID_HOME`)

## Install

```bash
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
```

## Usage

The bundle input, the extraction work dir and the research documents live one
level up, in the repo root:

```bash
export ANDROID_HOME=/home/alon/Android/Sdk

# 1. compile the module alone -- isolates Java errors from patching errors
(cd smali_generator && ./gradlew assembleRelease)

# 1b. JVM unit tests for the pure likes core (160 @Test methods, 14 classes; no device)
(cd smali_generator && ./gradlew test)

# 2. THE GATE -- never skip. Must print "GATE PASSED" and exit 0.
.venv/bin/python /home/alon/.claude/skills/android_patching/scripts/validate-artifactory.py \
    --project . --work ../.patcher-work/okcupid

# 3. patch. Bundle in -> bundle out, so the output is named .xapk.
rm -rf ./temp                      # Stitch refuses an existing temp dir
.venv/bin/python main.py -p ../okcupid.xapk -o ../okcupid-patched.xapk --arch arm64-v8a

# 4. install (your call -- it uninstalls first, which wipes the session)
unzip -o ../okcupid-patched.xapk -d out && adb install-multiple out/*.apk
```

Read the gate's `PREMIUM_GATE_TARGETS` value, not just its exit code: it must
list **two** `class:method:sig` triples on this build. One means the multi-target
finder found a single carrier and the premium unlock will be partial.

Useful flags:

| Flag | Meaning |
|---|---|
| `--arch` | ABI whose `libarthooks.so` is injected. Default `arm64-v8a` — set `x86_64` for an emulator, or hooks will not load. |
| `--no-sign` | Leave the output unsigned. |
| `--extra-artifacts K:V` | Inject `{{K}}` → `V` without writing a finder. Debugging aid only; it hard-codes what finders exist to avoid. |
| `-g/--google-api-key` | Replace the app's `google_api_key` resource. |

Signing uses stitch 1.4.x's own bundled debug key unless you export a keystore —
see `.env.example`.

## What it unlocks

| Hook | Target | Forced to |
|---|---|---|
| `PremiumGate` | every carrier of `getUserHasPremium(PremiumFeatures)Z` — two classes on 116.0.0 | `true` |
| `IncognitoGate` | `SessionHelper.isIncognitoEnabled()Z` | `true` |
| `LikesCapGate` | `LikesCapManager.hasReachedLikesCap()Z` | `false` (inverted) |
| `BlurredUserFlag` | `User.getShowBlurred()Z` | `false` |
| `BlurredCardFlag` | `UserCardState.getShowBlurred()Z` | `false` |
| `UnlimitedRewinds` | `RewindManagerImpl.setUserTokens(Integer)V` | argument rewritten to `null` (the `-1` = unlimited sentinel) |
| `ShowRealName` | `UserCardState.getName()Ljava/lang/String;` | the stored name for that card, looked up by its normalized `getUserImage()` path; anything already named is returned untouched |
| `TransportCapture` | `okhttp3.internal.connection.RealCall.getClient()Lokhttp3/OkHttpClient;` | unchanged; the app's own authenticated client is borrowed so the sweep sends requests as the app does |
| `LikesCursorCapture` | `LikesPageRepo$LikesPagePayload.getUserList()Lcom/okcupid/okcupid/domain/ObservableData;` | unchanged; the page's own payload is read for its cursor, which names the window's last entry, then the sweep is triggered |
| `OpenRealProfile` | `LikesPageController.triggerNavigateToProfile(User)V` | the method's entire body is reimplemented (route, `CameFrom` tag, `Bundle`, listener dispatch); the route is built from the resolved id instead of the placeholder one, everything else is unchanged |

`OpenRealProfile` now owns **every** tap-to-profile navigation on this
controller, gated or not — a 3-arg call-through crashed the app (see below),
so there is no way for the original method to still run alongside this hook.
Its original target, `LikesPageFragment.navigateToProfile(String)V`, turned
out to be dead code on 116.0.0 (zero callers anywhere in the dex) and was
replaced after on-device verification caught it; the retargeted method then
crashed under a call-through backup, and the first reimplementation attempt
dropped a `Bundle` extra that turns out to drive real back-navigation
decisions, not just analytics. See "Likes-card identity resolution" in
`NOTES.md` for the full story and how each was found and fixed.

UI-side entitlement is genuinely unlocked; server-authorized consumable spend is
not, and is deliberately not faked. `NOTES.md` has the full scope section, plus
a table of the features that were investigated and found **not** bypassable
because the server withholds the data rather than the client hiding it.

## Layout

| Path | Role |
|---|---|
| `NOTES.md` | As-built reference: hooks, anchors, landmines, scope. |
| `main.py` | Wires finders + the hook module into `Stitch(...)` and runs `patch()`. |
| `artifactory_generator/` | Signature finders. Each produces `{{KEY}}` → value pairs. |
| `smali_generator/` | Android Gradle module holding the hooks. Built to `smali_generator.apk`; its dex and jni libs are injected into the target. |
| `probe-*.js` | Frida probes that check the hooks are in force, not merely installed. |
| `../*.md`, `../docs/graphql/` | The research this patcher came out of: GraphQL map, user-data surface, written findings, test plans. |

`{{PLACEHOLDER}}` strings anywhere under `smali_generator/` are substituted from
the artifactory before the module is compiled.

## Adding a patch

1. Write a finder in `artifactory_generator/` that locates the target class and
   method by a stable anchor and emits `{{...}}` keys — normally the group
   `PREFIX_CLASS_NAME` / `PREFIX_METHOD_NAME` / `PREFIX_METHOD_SIG`. When a
   concept has more than one target and which classes carry it may change, emit
   a single `PREFIX_TARGETS` key holding `|`-separated `class:method:sig`
   triples instead, and loop over them in the hook (`PremiumGate` is the worked
   example, and `NOTES.md` records its one real trade-off).
2. Register it in `main.py`'s `artifactory_list` — and read the ordering comment
   there first, because stitch can silently skip a finder.
3. Write a `Hook` implementation in
   `smali_generator/app/src/main/java/com/smali_generator/patches/`, installing
   through `HookUtil.install(...)` so it logs on success as well as failure.
4. Register it in `InitProviderOkCupidPremium.hooks`.
5. Validate before building — a finder that matches nothing leaves `{{KEY}}` in
   the compiled source and the hook fails silently at runtime.

## Debugging

```bash
adb logcat -s PATCH ArtHooks
```

Every hook logs on success as well as failure, so a silent `PATCH` tag means the
provider never ran — usually the wrong `--arch`. `NOTES.md` has the expected
logcat for a working patch.

To check that a hook is not merely installed but actually changing what the app
computes, run the probes against a device — `probe-control.js` **first**, since a
premiums map that is already all-true means the account is genuinely premium and
every other result is meaningless as evidence:

```bash
frida -U -f com.okcupid.okcupid -l probe-control.js    # is the account really not premium?
frida -U -f com.okcupid.okcupid -l probe-premium.js    # are both premium carriers forced true?
frida -U -f com.okcupid.okcupid -l probe-blur.js       # which image URL does the card get?
```

They name app classes directly and are therefore version-specific; see
`NOTES.md` for what each one proves.
