# Kefi Player: Sound settings — Equalizer and Volume Booster

## Goal

Add a **Sound** section to Settings. It contains two independent settings subpages:

- **Equalizer**
- **Volume booster**

Both features affect only Kefi Player playback. They share a native owner because Android
audio effects must be attached to ExoPlayer's current audio session, which may change during
the playback-service lifetime.

## Non-goals

- Do not change the device/system media volume.
- Do not request new Android permissions.
- Do not start playback or start the playback service merely by opening Settings.
- Do not hard-code a five-band equalizer; Android devices can expose different bands, ranges,
  presets, or no supported effect at all.
- Do not add a new dependency: use Android's built-in `android.media.audiofx.Equalizer` and
  `android.media.audiofx.LoudnessEnhancer`.

## Product defaults and behavior

- Equalizer and volume booster are off by default.
- Equalizer persists: enabled state, selected preset, and custom per-band levels.
- Volume booster persists: enabled state and target gain.
- The two features may be enabled together.
- Volume booster must use a conservative UI maximum and clamp native input independently of
  that UI maximum. Explain that high values may distort audio.
- Unsupported effects must render a quiet, explanatory unavailable state; never throw or show
  an alarming error dialog.
- The browser implementation is a fallback only: it reports sound effects as unavailable and
  must not claim to process audio.

## Architecture

`PlaybackService` owns the real `ExoPlayer`, therefore it is the only place that can safely
attach effects to the actual audio output session. Introduce one application-scoped
`AudioEffectsManager` that owns both Android effects. The UI still treats Equalizer and Volume
Booster as separate features.

```text
Settings views / useSoundSettings
             |
             v
        PlayerPlugin bridge
             |
             v
SoundEffectsStore <--> AudioEffectsManager <--> ExoPlayer audio session
                                         ^
                                         |
                                   PlaybackService
```

The manager must never obtain or create `PlayerController`/`MediaController`; changing sound
preferences while stopped must only save settings. `PlaybackService` attaches the manager after
it creates ExoPlayer and detaches it when the service ends.

## Tasks

### S0 — Confirm UI and value contracts

1. Agree the Settings section title: **Sound**.
2. Agree two routes:
   - `/settings/equalizer`
   - `/settings/volume-booster`
3. Use these persisted setting shapes (exact bridge names can vary, but retain the semantics):

   ```text
   equalizer: {
     enabled: boolean,
     preset: string | 'custom',
     bandLevels: number[]
   }

   volumeBooster: {
     enabled: boolean,
     gainMb: number
   }
   ```

4. Pick and document the UI gain ceiling before implementation. The manager must still clamp
   inputs even if an old/invalid saved preference exists.

**Acceptance:** user-facing names, routes, defaults, and safe gain range are decided.

### S1 — Add native persistence

Files to add:

- `android/app/src/main/java/com/kefi/player/SoundEffectsStore.kt`

Tasks:

1. Use `SharedPreferences` scoped to this app.
2. Read safe defaults when no values exist.
3. Persist equalizer enabled/preset/custom levels and booster enabled/gain.
4. Validate stored values on read; discard or clamp corrupt/out-of-range values.
5. Keep preference I/O independent from a WebView lifetime.

**Acceptance:** settings survive process restarts and can be read with no player/session present.

### S2 — Add a native audio-effects manager

Files to add:

- `android/app/src/main/java/com/kefi/player/AudioEffectsManager.kt`

Tasks:

1. Own at most one `Equalizer` and one `LoudnessEnhancer` instance.
2. Expose capability data:
   - equalizer available
   - loudness enhancer available
   - equalizer min/max band level
   - dynamic list of bands and frequency ranges
   - native preset names and identifiers
3. Attach effects to an audio-session id supplied by the service.
4. On attach/session change, release old effects, recreate supported effects, then apply persisted
   settings in a safe order.
5. On detach/destroy, disable and release both effects; make repeat release harmless.
6. Provide setters that validate input, persist it through `SoundEffectsStore`, and apply it
   immediately only if a live session is attached.
7. Use the main thread for effect/player operations and avoid retaining an Activity or Plugin.

**Acceptance:** effects are lifecycle-safe, capability-driven, and do not require an active UI.

### S3 — Wire manager to the playback service

Files to modify:

- `android/app/src/main/java/com/kefi/player/PlaybackService.kt`

Tasks:

1. After constructing `ExoPlayer`, attach `AudioEffectsManager` to its audio session.
2. Listen for ExoPlayer/Media3 audio-session changes and hand the new id to the manager.
3. Ensure attachment does not modify audio focus, queue state, media session, wake lock, or
   foreground-service behavior.
4. Detach/release manager effects in `onDestroy` before the player is released.
5. Confirm the manager remains correct when service/player is recreated after process death.

**Acceptance:** settings apply to real playback, survive player recreation, and cannot leak an
effect bound to an obsolete session.

### S4 — Expose a Capacitor bridge contract

Files to modify:

- `android/app/src/main/java/com/kefi/player/PlayerPlugin.kt`
- `src/plugins/player.js`
- `src/plugins/player.web.js`

Tasks:

1. Add a single read method, e.g. `getSoundSettings`, returning settings plus capabilities.
2. Add scoped mutation methods:
   - `setEqualizerEnabled`
   - `setEqualizerPreset`
   - `setEqualizerBandLevel`
   - `setVolumeBoosterEnabled`
   - `setVolumeBoosterGain`
3. Make plugin validation reject malformed values with actionable errors.
4. Add JSDoc types for all request/response shapes.
5. Implement the browser plugin methods as predictable unavailable/no-op behavior suitable for
   rendering the UI, rather than faking real sound processing.

**Acceptance:** frontend code can fully render and update sound settings through a documented
native API without touching playback APIs.

### S5 — Add shared frontend state

Files to add:

- `src/composables/useSoundSettings.js`

Tasks:

1. Keep module-level reactive state so Settings and both subpages share one source of truth.
2. Load bridge capabilities/settings once, with explicit `loading`, `ready`, `unsupported`, and
   recoverable `error` states.
3. Wrap mutations so UI state changes only after native success, or rolls back correctly on
   failure.
4. Do not call `usePlayer().initialize()` or any playback-starting method.
5. Make later calls idempotent, matching the existing composable style.

**Acceptance:** repeated route changes do not duplicate calls or create inconsistent state.

### S6 — Add Settings navigation and routes

Files to modify:

- `src/views/SettingsView.vue`
- `src/router/index.js`
- `src/i18n/en.js`
- `src/i18n/el.js`

Files to add:

- `src/views/EqualizerView.vue`
- `src/views/VolumeBoosterView.vue`

Tasks:

1. Add a Sound card in Settings with separate Equalizer and Volume Booster rows.
2. Show compact status summaries where capabilities are loaded, such as Off, preset name, or
   current gain.
3. Add the two subroutes and retain standard router/hardware-back behavior.
4. Add every visible and accessibility string in English and Greek.
5. Match existing page padding, card, option, and active-state styling; avoid a separate visual
   design system.

**Acceptance:** routes are accessible, localised, and behave like the rest of the application.

### S7 — Implement the Equalizer subpage

File to implement:

- `src/views/EqualizerView.vue`

Tasks:

1. Add a page header and back control.
2. Add the Equalizer enabled switch.
3. Render presets from native capability data; do not hard-code names or indexes.
4. Render one slider per device-reported band, labelled with its reported frequency range.
5. Any manual band change selects/records `custom`.
6. Add reset-to-flat behavior that writes valid native levels and has a clear confirmation/status.
7. Disable controls accessibly while the effect is off, loading, or unsupported.
8. Render an unavailable explanation if no Equalizer is available.

**Acceptance:** UI works for different Android band counts/presets and accurately reflects
native state after returning to the page.

### S8 — Implement the Volume Booster subpage

File to implement:

- `src/views/VolumeBoosterView.vue`

Tasks:

1. Add a page header and back control.
2. Add a booster enabled switch.
3. Add a gain slider backed by millibels, with a readable dB display.
4. Add copy explaining this affects Kefi playback only and high gains can distort sound.
5. Retain the chosen gain when the switch is turned off.
6. Render an unavailable state when `LoudnessEnhancer` is unsupported.

**Acceptance:** only valid gain values are sent, output labels are understandable, and off/on
behavior restores the selected gain.

### S9 — Tests and device QA

Tasks:

1. Add tests where the current Android test setup supports them:
   - `SoundEffectsStore` defaults/round-trip/clamping
   - manager setting validation and capability mapping
   - web fallback/composable state behavior
2. Build and run the Android app.
3. Test on a physical device if possible:
   - open settings without ever playing a track
   - settings while paused and while playing
   - switch tracks, queue edits, and seek
   - background/foreground and WebView recreation
   - app/process/service recreation
   - speaker, wired, and Bluetooth output where available
   - effect unsupported/error scenarios
4. Verify no new permissions, no audio-focus regression, no service start from Settings, and no
   regression to widget, notification, queue, or normal playback.

**Acceptance:** sound settings are persistent, lifecycle-safe, and do not alter existing player
behavior outside audio processing.

## Recommended implementation order

Complete S0 through S4 before building either UI page. Then do S5 and S6, followed by S7 and
S8. Finish with S9. This order keeps the native capability contract stable before frontend
controls depend on it.
