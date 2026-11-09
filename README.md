# Hari Player – custom music player for the iBasso DX270 (version 0.3)

## What it does

- **Library:** every song on the DX270, from internal storage and the SD card.
- **Composers:** your library grouped by composer.
- **12 smart mixes:**
  - Gym, Commute, Car, Study, Bass, Orchestral
  - Tamil, Remixes, Tamil Classical, Anirudh, Love Songs, Harris Jayaraj
  - A song joins a mix when a keyword appears in its folder name, title, artist, album or composer.
- **Playback:** in the background with a notification. The DX270's play / previous / next keys work, the wheel controls volume, and music pauses when the IEMs are unplugged.
- **AutoEQ for your IEM:** pick **My IEM** (your five are at the top, then every IEM on the squig.link network) and a **Target sound** (your V5.2 and V5.1 first, then every squig.link target). Tap **Make EQ** and the app works out a 10-band parametric EQ, shows the before/after graph, and saves it as a one-tap preset. Sites that have opted out of aggregation, such as Crinacle's Hangout.Audio, aren't used.
- **One-tap EQ per IEM:** saved presets, plus any Equalizer APO / AutoEQ `.txt` preset you import.
- **Sound modes:**
  - Direct
  - Tube character: warm triode, classic pentode or modern tube
  - Sub-bass rumble
  - Vinyl resonance
  - Turntable warmth
  - Soundstage width
  - Quick presets: Hari Bass, Vinyl Night
- **New in 0.2:**
  - Crash reports and Safe mode
  - A safe way back to iBasso's player
  - Over-the-air updates

### Not yet

- Instrument Studio, DSD remastering, AI restore and AI hiss removal (planned on the PC, via Stem Studio)
- Gain and output switching (these stay in iBasso's settings)
- DSD and APE files (use Mango)
- Hi-res audio is processed at 16-bit so the effects can run. For untouched hi-res, use Direct or Mango.

---

## Recommended setup: GitHub builds the app and sends updates over Wi-Fi

You don't need Android Studio for this. GitHub builds the app for free every time the code changes. The app on the DX270 then sees the new version over Wi-Fi and installs it.

### One-time setup (about 15 minutes)

1. **Create a free GitHub account** at https://github.com, if you don't have one.
2. **Create a new public repository** called `HariPlayer`. Click **+ › New repository**, choose **Public**, and leave it empty.
   It has to be public so the DX270 can download updates without a password. Your signing key stays private (step 4).
3. **Upload the code.** Either:
   - drag the *contents* of this `HariPlayer` folder (including the hidden `.github` folder) into the repository's **Add file › Upload files** page and click **Commit**, or
   - connect the repository to Claude and ask Claude to upload it for you.
4. **Add your signing key as two secrets.** This key makes every new version install over the old one.
   In the repository, go to **Settings › Secrets and variables › Actions › New repository secret**:
   - Name `KEYSTORE_B64`, value: the whole contents of `KEYSTORE_B64.txt`
   - Name `KEYSTORE_PASSWORD`, value: the contents of `KEYSTORE_PASSWORD.txt`

   Both files came separately from the code. Keep them private, and keep a backup: without the key you can't update the app, only reinstall it.
5. **Start the first build.** Go to **Actions › Build Hari Player › Run workflow** (pushing new code also starts it). It takes about 5–10 minutes.
6. When it's done, open **Releases** (right side of the repository page). Download `HariPlayer-N.apk` and install it as described in "Install it on the DX270".

### How a change reaches your DX270

1. Tell Claude what to change or fix, with the crash report if there is one, and give Claude access to the `HariPlayer` repository.
2. Claude edits the code and pushes it. GitHub builds a new version automatically (about 5–10 minutes).
3. The next time you open Hari Player on Wi-Fi, a teal **"Update ready"** bar appears:
   - If **Install updates automatically** is on (App & support screen, uses Magisk root), it installs by itself and reopens.
   - Otherwise, tap **Install**, then **Update** on Android's screen. Android requires that one tap for apps without root.

Your settings and EQ presets are kept across updates.

---

## Alternative: build it yourself with Android Studio

1. Install Android Studio from https://developer.android.com/studio.
2. **File › Open** the `HariPlayer` folder, then wait for Gradle sync (it downloads 1–2 GB the first time).
3. **Build › Build App Bundle(s) / APK(s) › Build APK(s)**, then **locate** `app\build\outputs\apk\debug\app-debug.apk`.

A version built this way is signed with your PC's own key, so it **won't receive the Wi-Fi updates**. To switch to the GitHub version later, uninstall this one first.

---

## Install it on the DX270

**Option A – copy the file**

1. Copy the APK to the DX270, by USB (choose File transfer) or on the SD card.
2. On the DX270, open **Files** and tap the APK.
3. Allow **Install from this source** when asked, then tap **Install**.
4. If Play Protect warns about an unknown app, tap **More details › Install anyway**.

**Option B – download it on the DX270:** open the repository's **Releases** page in the DX270's browser, tap the APK, then install it the same way.

**First run:** allow **Music and audio** access, and allow notifications. Root isn't needed for anything except silent updates.

## Internal memory or SD card?

- **The app goes on internal memory.** Android 13 installs apps there, and it's faster and safe if you swap cards. It needs only a few MB.
- **The APK file** can sit on the SD card for installing. Delete it afterwards.
- **Your music stays on the SD card.** The app reads it from wherever it is.

---

## If it crashes

- **What gets saved:** the app saves a report automatically with what went wrong, the app's recent log, your device and firmware, and your sound settings.
- **Next time you open it:** an amber **"Hari Player crashed last time"** bar appears. Tap it, then:
  - **Email crash report:** opens your email app, addressed to `dr.yuvaraj.2007@gmail.com` with a subject starting `[HariPlayer report]`. Tap Send. Then tell Claude in a chat "check my Hari Player crash report". With your Gmail connected, Claude can find it.
  - **Copy:** copies the report so you can paste it straight into Claude.
- **For problems that aren't crashes** (no sound, a wrong song, a slow screen), use **Email diagnostic report** on the **App & support** screen (⚙ on Home).
- **Safe mode:** if the app crashes twice in a row while starting, it opens in Safe mode with every effect off, so a bad setting can't keep crashing it. Turn Safe mode off on the App & support screen once it's fixed.

It can't send reports with no tap at all. Android doesn't let an app send email silently, and Claude can't receive files from the app directly. That's why it takes one tap to send.

## Getting back to iBasso's software (no risk of bricking)

Hari Player is an ordinary app. It doesn't touch the DX270's system, firmware, Mango, or the Anantar / Magisk setup. The only thing root is used for (if you allow it) is installing its own updates.

- **Open Mango:** App & support › Open Mango, or open Mango from the home screen.
- **Remove it completely:** App & support › Uninstall Hari Player, or Settings › Apps › Hari Player › Uninstall. The DX270 is left exactly as it was.
- **If the app won't open at all:** long-press its icon › App info › Uninstall. Nothing else needs undoing.

---

## Where things are in the code

| File | What it does |
|---|---|
| `ui/App.kt` | All screens: Home, Composers, song lists, Now Playing, EQ, Sound modes, App & support |
| `ui/AppState.kt` | Navigation, queue, now-playing and update state |
| `data/Library.kt` | Reads the music library; smart mix keywords |
| `dsp/SoundProcessor.kt` | The audio engine: PEQ, tube, rumble, vinyl, soundstage |
| `dsp/PeqPreset.kt` | Reads EQ preset files |
| `dsp/AutoEq.kt` | Fits a parametric EQ from an IEM measurement to a target |
| `data/Squig.kt` | Reads IEM and target lists and measurements from squig.link |
| `ui/EqScreens.kt` | EQ screen and the My IEM / Target sound pickers |
| `support/CrashReporter.kt` | Crash reports, diagnostic reports, Safe mode |
| `support/Updater.kt` | Checks GitHub for updates, downloads and installs them |
| `PlaybackService.kt` | Background playback, hardware buttons, notification |
| `.github/workflows/build.yml` | The automatic build on GitHub |
