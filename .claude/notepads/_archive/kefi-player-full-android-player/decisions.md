# Decisions

(Empty at start. Append one line per decision with the task id.)
- T12: `player.repeat` is persisted as Media3's raw `Player.REPEAT_MODE_*` Int (not the
  `off|one|all` string) — the restore path writes straight to `ExoPlayer.repeatMode`, and
  `PlayerPlugin.kt`'s existing `repeatModeToString()` still converts to the string at the
  plugin boundary for every JS-facing read, so the frozen `getPlayerState` contract is
  unaffected.
- T12: position-persistence uses a `Handler`-driven 5s heartbeat (started/stopped by the
  existing `onIsPlayingChanged` callback) rather than writes driven purely by discrete
  Player.Listener callbacks — see `issues.md`/`learnings.md` `## T12` for why a bare
  callback-driven design cannot meet the QA scenario's position-freshness requirement.
