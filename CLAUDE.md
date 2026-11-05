# REX Player

## Device testing (phonectl)

- Device is pinned per project: `.phonectl/device` = `tablet` (Xiaomi, MIUI, adb serial `4c637b9e`).
  No `-d` needed here; `phonectl devices` shows what is active and why.
- Build + install: `.phonectl/install.sh` (builds debug, installs with `-r -d -t`, taps MIUI's
  "Install via USB" prompt). Don't run Gradle separately and install later: Android Studio shares
  `app/build` and may delete the APK in between.
- Repeated navigation goes in flows, not hand-typed taps. List them:
  `for f in .phonectl/*.yaml; do printf '%-34s %s\n' "$f" "$(sed -n '1s/^# //p' $f)"; done`
  Run: `phonectl flow [-e TIMER="FIFA 11+ Level 1"] .phonectl/<flow>.yaml`.
  In flows, `text:` is a regex: use `contains:` for names with `+ ( )`.
- Cheap reads: `phonectl state <text>` (a filter is required; `--full` only when lost), screenshots
  default to 512 px in `$TMPDIR`. Never pipe phonectl into `/dev/null`: its JSON errors are the signal.
- Test data on the tablet: `/storage/emulated/0/Download/timer-clips/<folder>/` with `timer*.json`.
  Restore anything you edit (back up first); delete test timers you create.

## Timers / timer-clip playlists (feature notes)

- `utils/media/TimerClips.kt`: folder scan (port of the old Lua script), timer.json load/save (keeps
  unknown fields), phase flattening. `ui/browser/timers/TimersScreen.kt`: runner, list, editor,
  folder picker, split-screen rest view, player overlay.
- Player timer mode (`TimerRunner.playerMode`) forces portrait + crop in
  `PlayerOrientationController` and `PlaybackManager.applyVideoAspect`.
- Extended FABs need an explicit `semantics { contentDescription }`: material3 1.5 alpha drops
  their label from accessibility, so phonectl/TalkBack can't find them otherwise.

## Delegating device checks

Measured on one run of "start timer, skip to rest, verify lower third, stop":
Haiku low ~23k tokens (passed after self-correcting), Opus ~21k, Codex gpt-6-luna at xhigh ~227k
(full-screen dump, tried to delete a screenshot despite "don't edit").
- Routine pass/fail checks: Haiku at low effort with a specific flow.
- Avoid Codex for these; if used: `-c model_reasoning_effort=low`, read-only sandbox, stdin closed.
- `codex exec` and `pi -p` hang waiting on stdin: always add `</dev/null`.

@AGENTS.md
