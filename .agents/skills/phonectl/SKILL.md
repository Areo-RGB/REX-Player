---
name: phonectl
description: Drive Android phones that run the PhoneCtl app (Pixel 7) from the shell with `phonectl`: read the screen, tap/type/wait by text, scroll, apps, clipboard, screenshots, batches and Maestro-style YAML test flows (~10-40 ms per call over Wi-Fi). Also starts, tasks and ends Claude Code on the Pixel (`pixel-claude`). Prefer it over `phone`/`@phone`/Portal when the device has PhoneCtl.
allowed-tools: Bash(phonectl *), Bash(pixel-claude *)
---

# phonectl

Repo `/home/paul/Projects/phonectl` (README has every command and argument). The app is an
AccessibilityService with a token-protected HTTP API; text search, taps, waits and batches run
**on the phone**, so `tap text=X` is one request. CLIs are on PATH via `cli/install.sh`.

Devices live in `~/.config/phonectl/<name>.env` (URL, TOKEN, SERIAL); `default.env` links to the
first device set up (here `pixel7`).
Other device: `phonectl -d NAME …` or `PHONECTL_DEVICE=NAME`. New phone: install the APK, enable
the service, then `phonectl setup <adb-serial> <name>` (grants the permissions it needs).

## Commands

```bash
phonectl --help; phonectl tap --help      # all commands; one command's options
# options: --text X / --expect-text Y (typed), or the same as key=value (text=X expect_text=Y)
phonectl state [contains=X]            # "[package]" + one @eN line per element (~500 tokens)
phonectl find contains=senden          # one element; all=true for every match
phonectl info                          # package, activity, width, height, orientation
phonectl tap text=Senden expect_text=Gesendet      # also long_press, double_tap; or x= y=
phonectl type value="hallo" enter=true # focused field, or add a selector; append=true
phonectl clear [id=url_bar]            # empty the focused (or selected) field
phonectl wait package=com.whatsapp timeout=5       # selector, gone=true, or nothing = quiet screen
phonectl scroll dir=down; phonectl scroll_until contains=Jana dir=down max=8
phonectl app package=com.whatsapp      # or url=…; action=stop = soft stop
phonectl key key=back                  # home, recents, notifications, quick_settings, lock
phonectl clipboard; phonectl clipboard value="text"
phonectl packages contains=whats       # JSON: .packages = "package  Label" lines joined by \n
phonectl screenshot max=512 -o /tmp/s.jpg          # then Read the file
phonectl batch 'steps=[{"cmd":"app","package":"com.android.chrome"},{"cmd":"wait"},{"cmd":"key","key":"home"}]'
```

Selectors: `text=` exact text or description (case-insensitive), `contains=` (text, description
or view id), `id=` (the `#url_bar` part of a `state` line: `id=url_bar`), `text_regex='\d+ comments'`
(whole text, for changing values), `selected=true` (active tab), `enabled=true`, `checked=`,
`right_of="System-on-chip"` / `below=` / `above=` / `left_of=` (anchor text; nearest first:
the way to hit a table/infobox value or the link in a given row), `ref=@e5`, `index=` (nth match, negative from the end). Lookups wait up to
`wait_timeout=` (2 s), so no sleeps before taps. Exit code 1 means `"ok": false`.

## Rules that save mistakes and tokens

- **Steps cost more than output**: every tool call re-reads the whole context. Chain fixed
  sequences with `batch`, put checks into the action (`expect_*`), and use
  `state contains=X` / `find` instead of a full `state` (~500 tokens) when you know what you want.
- Long pages: `scroll_until contains=VALUE` (search the value, not the label: Wikipedia's
  "Release date" is "First released"), then `find`; avoid scroll + full state loops.
- Actions wait for the screen to settle (up to 2 s), so `state` right after a tap shows the new
  page; still prefer `expect_*` to verify. A tap that does nothing: `retry_if_no_change=true`.
- Put the check into the action (`expect_text=`, `expect_contains=`, `expect_package=`) instead
  of tap + state.
- Never tap a bare `ref=` without a fresh `state`/`find`: a stale ref taps whatever is there now.
- `text=` is exact; use `contains=` when unsure. `state contains=X` filters on the phone, use it
  on big screens (long chats).
- Orient with `state` (~500 tokens) before any screenshot; `max=512` screenshots cost ~160 tokens.
- Line flags: `click edit scroll checked selected focused`; `selected` marks the active tab/chip.
- Save screenshots in a scratch/temp dir, not the project (`-o $TMPDIR/s.jpg`).
- Do not reinstall PhoneCtl while something important runs on the phone: right after an
  `adb install -r` of PhoneCtl, Android removed the foreground app's task (a running Yo-Yo test ended).
- Typing works in normal text fields only (`ACTION_SET_TEXT`), not in terminal views like Termux.

## YAML flows (repeatable tests)

`phonectl flow test.yaml [-e KEY=VAL] [--timeout 5]` runs Maestro-style flows (subset; list in the
README), one line per step, exit 1 and `test.failure.jpg` on failure. Read the FAIL line and the
screenshot only. Use `optional: true` for steps that depend on app state, and clear fields before
typing (apps such as Chrome restore old text). Example: `examples/chrome-search.yaml`.

## Claude Code on the Pixel

`pixel-claude` runs Claude Code (Haiku 5.5, medium effort, auto mode) with Remote Control in a
detached tmux session in the phone's Debian chroot (`device/claude-rc` there).

```bash
pixel-claude start "optional first task"   # Remote Control name: pixel7-haiku
pixel-claude status [lines]                # running? + last screen lines
pixel-claude stop                          # this session only
pixel-claude end                           # ALL Claude sessions on the phone + close Termux
```

Task it with `SendMessage` to `pixel7-haiku` (take the `[ref]` from `ListAgents` when an old
session with the same name is listed); replies come back as cross-session messages. Messages
arrive without approval because the chroot has `"crossSessionInbound": "accept"`. Check its
answers: Haiku misread `dumpsys battery` status 4 ("not charging") as charging.
`pixel-claude end` also stops anything else running in Termux (e.g. sshd from SSH-Start).

## System-level jobs: plain adb (not in phonectl on purpose)

```bash
S=31071FDH2008FK                                   # Pixel 7
uid=$(adb -s $S shell pm list packages -U com.whatsapp | grep -o 'uid:[0-9]*' | cut -d: -f2)
adb -s $S logcat -d --uid=$uid '*:W' | tail -100  # not -t: it cuts before the uid filter
adb -s $S logcat -d -b crash -t 50                 # crash stack traces
adb -s $S shell su -c dmesg | tail -50            # kernel/hardware log (root only)
adb -s $S shell am force-stop com.android.chrome   # real force-stop
adb -s $S install -r app.apk
```

## If it fails to connect

- `curl: (7)` or timeouts: the service may be restarting (Android can kill it under memory
  pressure; it is back within ~10 s), or the app lost LAN access (Android 17+):
  `adb shell pm grant dev.phonectl android.permission.ACCESS_LOCAL_NETWORK`.
- Service off: `adb shell settings get secure enabled_accessibility_services` must contain
  `dev.phonectl/dev.phonectl.PhoneCtlService` (append to the list, never overwrite: Portal is in it).
- USB instead of Wi-Fi: `adb forward tcp:18765 tcp:8765` and `URL=http://127.0.0.1:18765` in the
  device's env file (PC port 8765 is taken).
