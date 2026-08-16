# Developing

Fork of [khalifa007/rabbitR1Luncher](https://github.com/khalifa007/rabbitR1Luncher).
This file covers the loop on **this** machine; `CLAUDE.md` covers the codebase itself.

## One-time setup

```bash
./bootstrap.sh
```

Resolves the JDK and Android SDK, writes `local.properties`, fetches
`platform.keystore`, verifies its fingerprint, and regenerates
`gradle/wrapper/gradle-wrapper.jar` (which is `.gitignored` upstream, so a
fresh clone has no working `./gradlew` until you run this).

Toolchain it expects — all already installed here:

| Piece | Where |
|---|---|
| JDK 17 | `/usr/lib/jvm/java-17-openjdk` |
| Android SDK | `~/Android/Sdk` (platform-tools, platforms;android-34, build-tools;34.0.0) |
| Env | `~/.config/android-dev.env`, sourced from `.bashrc` |
| Health check | `android-doctor` |

## The loop

```bash
./r1.sh              # build + install + restart on the device
./r1.sh log          # follow the launcher's logcat tags
./r1.sh shot         # screenshot -> shots/
./r1.sh mirror       # live screen in a desktop window (scrcpy), touch works
```

`./r1.sh` with no arguments is the whole inner loop: ~40 s incremental Gradle
build, ~10 s install, instant restart.

Full command list: `./r1.sh` with a bad argument, or read the header of the script.

## Why a plain `adb install` isn't enough

Three device-specific traps, all handled by `r1.sh`:

**1. versionCode floor.** The launcher also lives at `/system/app/R1Launcher/`,
baked into the CarrotOS image at **versionCode 1000**. A `/data/app` install is
only accepted at or above that number, and this repo tracks the real release
number (15). Upstream edits the file and hides the edit with `git update-index
--skip-worktree`; this fork takes it as a Gradle property instead:

```bash
./gradlew assembleDebug -Pr1.versionCode=1001
```

`r1.sh` reads the floor off the connected device with `dumpsys package` and
passes the next number up, so you never think about it. If you ever see
`INSTALL_FAILED_VERSION_DOWNGRADE`, the ROM was rebuilt with a higher number —
`r1.sh` will pick that up on its own.

**2. Signature match.** Both build types sign with `platform.keystore`, the
public AOSP test key (`c8a2e9bc…192ab8`). It must match the system copy's
signature or the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
`bootstrap.sh` refuses to proceed if the fingerprint is wrong. Signing with
this key is also what grants the signature-only permissions the launcher uses
(e.g. `ACCESS_MESSAGES_ON_ICC` for reading SMS off the SIM).

**3. Restart, not `am start`.** The launcher is the HOME app and is always
running, so `am start` just foregrounds the old process — your new code never
loads. It must be `am force-stop` first. "Activity not started, intent has been
delivered to currently running top-most instance" is the symptom.

## Testing

There are **no automated tests** in this repo — no `app/src/test`, no
`androidTest`, no JVM unit tests, nothing. Every change is verified by running
it. Budget for that.

The emulator is not a practical option on this machine: `/proc/cpuinfo` reports
no `vmx` flag, so Intel VT-x is disabled in the BIOS/UEFI and the emulator falls
back to full CPU emulation. Enable VT-x in firmware if you want that path back,
then `sdkmanager "emulator" "system-images;android-33;google_apis;x86_64"` and
an AVD at 480×480 / 320 dpi. Even then the emulator has no carroot socket, no
side button, no scroll wheel, and no camera motor — so the panels that matter
most still need hardware.

What actually works, cheapest first:

**Mirror the screen.** `./r1.sh mirror` gives you the round 480×480 panel in a
desktop window with working touch and keyboard. This is the single biggest
quality-of-life win for UI work — no more squinting at the device.

**Drive the UI without touching it.** The launcher navigates by scroll wheel,
which maps to D-pad keycodes:

```bash
adb shell input keyevent KEYCODE_DPAD_DOWN     # wheel down
adb shell input keyevent KEYCODE_DPAD_CENTER   # click
adb shell input keyevent KEYCODE_BACK          # back
```

`./r1.sh keys` prints the full set. The **side button is the exception** — it's
remapped to `BUTTON_1` by the ROM keylayout and its tap / double-tap (350 ms) /
long-press (500 ms) state machine reads real DOWN/UP timing, which injected
events don't reproduce. Test that one by hand.

**Cut the cable.** `./r1.sh wifi` flips adb to TCP so you can iterate with the
device sitting on the desk. Note that adb over TCP is unauthenticated — trusted
networks only.

**Use the web companion.** The launcher serves an HTTP+WebSocket panel on port
8080 (Settings → Network → "remote panel"). Open it in a desktop browser and you
get live state, the SMS list, the terminal, and the chat panels without touching
the device at all. Its UI is plain HTML/CSS/JS in `app/src/main/assets/web/`
with no build step, so front-end changes are a file edit plus a reinstall.

**Get root.** `adb shell` is uid 2000, but the ROM parks an unauthenticated root
shell on `127.0.0.1:1337` (`carroot`). `./r1.sh root <cmd>` runs through it.
This is the same channel the launcher itself uses for Wi-Fi toggles, hotspot,
the camera motor, and factory reset — so if a toggle silently no-ops, check
carroot first: `./r1.sh doctor` reports whether it's answering.

**Read the logs.** `./r1.sh log` tails the launcher's own tags plus
`AndroidRuntime:E` / `ActivityManager:E`, so crashes and ANRs surface in the
same stream.

## Shipping a build to the device over the air

`.github/workflows/release.yml` fires on `v*` tags, builds a release APK signed
with the platform key, and attaches it plus a SHA-256 sidecar to a GitHub
release. `OTAUpdater` polls this fork's releases API, so tagging is a
cable-free way to push a build:

```bash
# edit versionCode + versionName in app/build.gradle.kts first
git tag v1.2.0 && git push origin main --tags
```

CI needs the `PLATFORM_KEYSTORE_B64` repo secret (base64 of `platform.keystore`)
or the build fails on the first step.

## Staying current with upstream

```bash
git fetch upstream
git rebase upstream/main
```

`upstream`'s push URL is deliberately broken so you can't push to khalifa007's
repo by accident. Open a PR instead. The `beta` branch is 5 commits *behind*
`main` — ignore it.

Only two files in this fork will conflict on rebase: `app/build.gradle.kts`
(the versionCode override) and `updater/OTAUpdater.kt` (the fork's release URL).

## Where things live

`LauncherActivity.kt` is 6,400 lines and holds the `LauncherHost`
implementation, every system integration, and all the broadcast receivers.
It's the first place to look and the hardest file to change safely.

| Want to change | Go to |
|---|---|
| A screen's layout | `ui/<Name>Panel.kt` |
| Navigation / what back does | `LauncherNav.kt` |
| Any observable state | `LauncherState.kt` (`Panel` enum lists all ~35 screens) |
| Adding a screen | new composable + register it in `ui/LauncherRoot.kt` |
| Adding an app to the grid | `AppEntry` sealed class + every `when` in `ui/AppsPanel.kt` |
| Colors, type, animation tokens | `ui/Theme.kt`, `ui/Common.kt` |
| Web companion | `app/src/main/assets/web/` + `web/WebRpc.kt` |
