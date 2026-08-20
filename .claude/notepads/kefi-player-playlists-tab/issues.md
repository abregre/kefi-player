# Issues

(Append blocking or surprising issues, with the task id.)

## T12 → constraint for the UI task that calls it
`setAsRingtone` resolves `{ granted: false }` AND opens system Settings when `WRITE_SETTINGS`
has not been granted — it does NOT reject. So the calling UI must handle three outcomes, not two:
`{granted:true}` (done), `{granted:false}` (the user was just sent to Settings; say so rather than
showing an error, and do not retry automatically), and a rejection (a real failure worth showing).
Treating `{granted:false}` as an error would tell the user something broke when nothing did.

## T6 → two watch items, neither blocking

1. **First-paint latency is now gated on a native round trip.** `main.js` awaits
   `initializePlaylists()` alongside `initLocale()`/`initializeEasterEgg()`, but those two are
   sub-millisecond Preferences reads while this one opens Room (creating the DB file and running
   schema creation on a cold first launch) and makes two plugin calls. `.finally()` still mounts
   on failure and the function cannot throw, so the risk is delay, not breakage. Only a device can
   show whether it is perceptible — an F5 observation, not something to pre-optimise.
2. **`recentlyAdded`'s comparator relies on NaN being falsy.** Two undated tracks give
   `effectiveDateAdded(b) - effectiveDateAdded(a)` = `-Infinity - -Infinity` = `NaN`, and the
   `|| collator.compare(...)` fallback catches it because `NaN` is falsy — so undated tracks fall
   through to the title tie-break, which is the intended behaviour. It is correct, but it is
   correct by a subtlety; do not "simplify" that `||` away.

## T9 → one UX call for the user to confirm (F5)
The Playlists tab deliberately ignores the search query: Favorites, Recently added and a user
playlist are fixed by name/membership, so there is no well-defined "search hit" for one of them.
The reasoning is sound, but the consequence is that the search field stays VISIBLE and inert on
one tab out of five, which a user can reasonably read as broken. Two alternatives if they dislike
it: hide the search row while the Playlists tab is active, or filter the list rows by name. Not
changed unilaterally — it is a visible-behaviour choice, so it belongs to the user.

## T10 → `window.confirm` is now in the app (decide whether to keep it)
Delete-a-playlist is gated behind `window.confirm`. It works in a Capacitor WebView (the bridge
implements `onJsConfirm`), and it genuinely satisfies Must NOT Have 12 (no destructive delete
without confirmation), so it is not a defect. But it renders as a bare system dialog, which is the
only piece of non-app-styled UI anywhere in this app, and T8 is concurrently building a real sheet
component that could host a styled confirm instead. Worth revisiting once the sheet exists — a
one-line swap then, versus a second dialog idiom now.

## T14 → a transformed ancestor and a fixed-position sheet (low risk, know about it)
`NowPlayingView`'s `.page` gets `transform: translateY(...)` while the drag-to-dismiss gesture is
active, and `TrackMenu` is mounted inside `.page`. A CSS `transform` on an ancestor becomes the
containing block for `position: fixed` descendants — so IF the sheet is open AND the user starts a
drag outside it (on the artwork, say), the sheet stops being viewport-fixed for the duration of
that drag and travels with the page instead.

Why it is not being fixed now: `.page`'s transform is only set while `offset` is non-zero, so
there is no transform at rest; touches starting inside the sheet are in the gesture's `ignore`
list; and the sheet moving with the page arguably looks intentional. Recorded so that if the user
reports "the menu slides with the screen", the cause is known and the fix is a teleport
(`<Teleport to="body">`) on the sheet rather than a hunt.
