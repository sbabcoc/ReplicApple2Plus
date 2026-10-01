# PadBridge

Reads a real, already-paired Bluetooth game controller on Android through
Android's own standard game-controller input APIs, and forwards its state
as UDP packets to ReplicApple2Plus's `NetworkPadProvider`, running inside
Termux/PRoot on the same phone.

## Why this exists

Android blocks unprivileged processes from reading `/dev/input` directly —
confirmed on this specific setup by `ls -l /dev` inside PRoot showing
nothing at all, which also rules out any device-node-based workaround
(including a virtual `uinput` device), not just direct device access.
That closes off Jamepad/SDL's usual Linux backend entirely inside PRoot.
Android apps, however, have full unprivileged access to connected game
controllers through `InputDevice`/`MotionEvent`/`KeyEvent` — the same
APIs any Android game uses. This app is the bridge between that access
and the Java/Swing emulator, which never needs to know whether its
input came from SDL or from here.

## Status: **not verified on a real device**

Every Java-side piece of this feature (`NetworkPadProvider` and its 12
tests, the `--network-input` wiring in `Apple2Plus`, an actual end-to-end
run of the real application) was built, compiled, and tested directly.
This Android app was not. There is no Android SDK, emulator, or physical
device available in the environment that produced it — it was written
against Android's documented APIs and carefully re-read by hand, but
never compiled. Building it in Android Studio is the first real
compilation it will ever undergo.

## Getting the APK (recommended: let GitHub build it)

Given this is realistically a project of one user, the goal here is
"push code, download a file, install it once" -- not a real release
pipeline. A GitHub Actions workflow
(`.github/workflows/build-padbridge.yml`) does the actual Android build
on GitHub's own servers, since they have real internet access to the
Android SDK and Gradle's own infrastructure and this sandbox does not.

1. Commit this `android-bridge/` directory (including
   `.github/workflows/build-padbridge.yml`) into the ReplicApple2Plus
   repo and push. **This assumes the workflow lives at the repo root and
   this project at `android-bridge/` there -- if it ends up somewhere
   else, the `paths:` and `working-directory:` lines in the workflow
   need adjusting to match.**
2. Check the repo's Actions tab. The build takes a few minutes.
3. Once it finishes, the repo's Releases page will have a new release
   (tagged with the commit hash) with the `.apk` attached. That link
   stays valid indefinitely, unlike a workflow-run artifact, which
   GitHub deletes after 90 days by default -- worth knowing for an app
   that might go a while between rebuilds.
4. Open that release page on the phone (or transfer the file over),
   tap the `.apk`, and install. Android will show a one-time "allow
   installing from this source" prompt for whichever app performed the
   download (the browser, a file manager, etc.) -- expected, not a
   deeper security setting to hunt for.
5. To rebuild without changing any code (e.g. after only editing this
   README), trigger the workflow manually from the Actions tab
   ("Run workflow") rather than needing a throwaway commit.

**Not verified**: like the Kotlin source itself, this workflow was
written against well-established, commonly used GitHub Actions
(`setup-java`, `android-actions/setup-android`,
`gradle/actions/setup-gradle`, `softprops/action-gh-release`) but has
never actually been run -- there is no way to trigger a real GitHub
Actions run from the environment that produced it. The individual
actions are each widely used for exactly this purpose, which is a
reasonable basis for confidence, but it is not the same as having
watched this specific workflow succeed.

## Building locally instead (Android Studio)

Only needed for actively developing or debugging the app itself --
day-to-day, the GitHub Actions path above is the intended route.

1. Open this directory in Android Studio (File > Open).
2. Let Gradle sync. `compileSdk 34` / `minSdk 26` are set in
   `app/build.gradle` — adjust if your installed SDK differs.
3. Build and install onto the phone (Run > Run 'app'), or build an APK
   (Build > Build Bundle(s) / APK(s) > Build APK(s)) and install it via
   `adb install` or by copying it to the phone directly.
4. Pair the 8BitDo SN30 Pro to the phone in **Switch mode** (hold
   Y + START when powering it on) — this is the mode confirmed earlier
   in this project to be the one SDL/Android recognize cleanly.
5. Launch PadBridge. It should show the controller's name once Android
   sees it. If it says "No controller detected," the pairing or mode is
   the first thing to check, not this app.
6. In Termux, launch the emulator with the network option:
   ```bash
   java -cp build/classes/java/main:build/resources/main \
       com.nordstrom.emulator.Apple2Plus --config slots.ini --network-input 8942
   ```
7. PadBridge's default target is `127.0.0.1:8942`, matching
   `NetworkPadProvider.DEFAULT_PORT` — this should just work without
   touching the IP/port fields, since both processes run on the same
   phone and this loopback path is the same one already proven to work
   for the Termux/PRoot PulseAudio audio bridge set up earlier in this
   project.

## Testing it independently of the emulator

Before trusting the whole chain at once, it's worth confirming the app
actually sends packets, independent of whether the emulator is even
running. From a computer on the same Wi-Fi (not the phone itself, since
this uses loopback for the real setup, but any reachable address works
for this check), or from Termux itself:

```bash
nc -u -l 8942
```

Set PadBridge's target IP to that machine's address, move the stick and
press buttons, and watch the raw text arrive. This isolates "does the
app read the controller and send correctly" from "does the emulator
receive and parse it," which matters if something isn't working — the
protocol is plain, readable text specifically so this kind of manual
check is possible before either side has to trust the other.

## Wire protocol

One UDP packet per update, plain ASCII text. Either the literal body
`ABSENT` (no controller connected), or space-separated `KEY=VALUE`
pairs:

```
LEFT_X=-0.23 LEFT_Y=0.87 RIGHT_X=0.00 RIGHT_Y=0.00 BUTTONS=A,DPAD_UP
```

Axis names match `PadAxis`, button names match `PadButton`, both from
the Java project — see `NetworkPadProvider`'s own Javadoc for the full,
authoritative specification (which field is optional, what an empty
`BUTTONS=` means, how malformed fields are handled, and so on). This app
should follow that spec exactly; if the two ever disagree, the Javadoc
is the source of truth, not this README.

## Things that likely need adjustment on real hardware

- **Right stick and trigger axis codes.** `AXIS_Z`/`AXIS_RZ` for the
  right stick and `AXIS_LTRIGGER`/`AXIS_RTRIGGER` for the triggers are
  Android's standard, documented mapping, but some controllers or
  Android versions report triggers via `AXIS_BRAKE`/`AXIS_GAS` instead.
  `MainActivity.kt` reads both and uses whichever is non-zero — that
  fallback logic itself hasn't been tested. If the right stick or
  triggers don't respond, this is the first place to look.
- **Button key codes.** The mapping in `BUTTON_KEY_CODES` uses Android's
  standard gamepad key codes. Whether the SN30 Pro specifically sends
  exactly these for every button (especially BACK/GUIDE, which vary
  more across controllers) is unconfirmed.
- **Dead zone.** Deliberately not applied here — raw axis values are
  sent as-is, and the existing, already-tested dead zone in the Java
  project's `InputMapping` (`deadzone` in `default-input.ini` or a
  custom `--input` file) is what should handle it, the same as it does
  for the desktop Jamepad path. Applying a second dead zone here would
  just make that setting harder to reason about.
- **PadBridge must be the foreground/focused app to receive controller
  input at all** -- confirmed directly: Android only delivers
  `MotionEvent`/`KeyEvent` to whichever app currently has focus, so
  switching away to look at the emulator stops input dead. Also
  confirmed: this is about focus, not visibility. A desktop-mode feature
  that supports multiple windows (Samsung DeX, or an equivalent) lets
  PadBridge stay focused while positioned almost entirely off-screen,
  with the emulator's own window taking up the visible display --
  genuinely unobtrusive in practice, not just in theory. Plain
  alt-tabbing between the two apps works too, just with the expected
  back-and-forth friction. The original concern here was about screen-off
  throttling specifically; in actual use, needing foreground focus turned
  out to be the real constraint, and a multi-window desktop mode is the
  practical answer to it.
