# Decisions (taken with the user, 2026-08-20)

1. ytmp3 stays a separate, separately-shipped app. Its code is COPIED here; divergence accepted.
2. arm64-v8a only, release AND debug. Consequence accepted: the x86_64 emulator may not be able
   to run the download engine (T2 measures this).
3. The easter egg lives on the existing Settings support card. No visible change to that card.
4. Downloads land in MediaStore `Music/YTMP3` and appear in the library like any other music.
5. Share-to-app is ported but only becomes visible after unlock (activity-alias toggled at runtime).
6. The downloader is a THIRD bottom tab once unlocked: Library | Downloads | Settings.
7. The unlock is permanent (`kefi.downloader.unlocked`). No re-lock gesture.
8. No network in the automated QA. Real downloads are the user's manual test on their phone.
9. The SAF custom-folder picker is NOT ported: files in a picked tree never enter MediaStore,
   which would break decision 4.
