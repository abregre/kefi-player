# Decisions (taken with the user, 2026-08-21)

1. Metadata editing is OUT of this plan entirely — confirmed platform dead end via AOSP source
   (MediaProvider's sMutableColumns allowlist rejects TITLE/ARTIST/ALBUM/ALBUM_ARTIST/YEAR/TRACK
   writes for every app, always; silently reverted on rescan). Do NOT add any edit-metadata UI or
   any ContentResolver.update() call targeting those columns.
2. Delete-from-device supports every device this app supports (minSdk 24 up) — three native
   SDK-gated branches, not one code path.
3. File details shows everything: free fields immediately, MIME type/bitrate/sample rate/absolute
   path fetched on demand when the sheet opens.

## Orchestrator file-conflict resolution (2026-08-21)
- T1 -> T2 -> T5 -> T6 run strictly sequentially (all touch PlayerPlugin.kt and/or
  player.js/player.web.js). T4 runs in parallel with T1 (disjoint files: MainActivity.java or a
  new small Kotlin file, explicitly NOT PlayerPlugin.kt). T5 additionally depends on T4's broker.
- T8 (Strings) is the SOLE writer of src/i18n/en.js and src/i18n/el.js for this whole plan. T3 and
  T7 do NOT edit the locale files despite the plan's literal file list — they record every key
  they need, with proposed EN and EL text, in learnings.md instead. This mirrors the pattern that
  worked cleanly in the previous two plans (playlists-tab, hidden-downloader).
- T3 and T7 both touch TrackMenu.vue -> strictly sequential (T3 before T7).
