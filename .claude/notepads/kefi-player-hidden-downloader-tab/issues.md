# Issues

(Append blocking or surprising issues, with the task id.)

## T4 → carried into T6: `androidx.documentfile` is currently dead weight
T4 added `androidx.documentfile:documentfile:1.1.0` (the plan listed it) with the comment
"used when the user picks their own download folder". But decision 9 / Must NOT Have 3 drops the
SAF folder picker entirely, and `grep -rn "DocumentFile" android/app/src/main/java/` returns **0**
after T4. In ytmp3 the ONLY user of DocumentFile is `StorageExporter.exportToTree`, which is the
SAF branch we are not porting.

**T6 must**: port `StorageExporter` with the MediaStore branch only, confirm nothing imports
`androidx.documentfile`, then REMOVE that dependency from `android/app/build.gradle` and the
misleading comment with it. Leaving it in would advertise a capability the app deliberately does
not have, which is exactly the kind of drift that misleads the next reader.

## T10 → constraint for T11 and T16: `initialize()` must only run inside the downloader screen
`useDownloads().initialize()` calls `Ytdlp.prepare()` AND, once a day, `updateEngine('nightly')` —
i.e. it touches the network. Must NOT Have 12 says a user who never unlocks the hidden tab must
generate no network traffic at all.

The composable itself is inert at import time (verified). The trigger therefore lives entirely in
its CALLER: `initialize()` may be called ONLY from `DownloaderView.vue`'s `onMounted`. It must
never be called from `App.vue`, `main.js`, the router, `TabBar.vue`, or the easter-egg composable.
F4's scope review greps for this.
