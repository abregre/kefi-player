# Problems

(Append failures and how they were resolved.)

## Standing orchestration rule inherited from the previous plan
Only the orchestrator runs builds, and only with every agent idle. Parallel `npm run build` /
Gradle invocations from several agents corrupt each other's results (and last session an agent's
in-flight edit poisoned another task's APK). Agents verify with greps, `@vue/compiler-sfc`,
`bash -n`, and reading code. Every delegation must forbid builds explicitly.

## T3: kapt hid a broken annotation, and only the build found it (2026-08-20)

`FavoriteTrack.kt` used `@PrimaryKey` while importing only `androidx.room.Entity`. Kotlin does NOT
error on an unresolved annotation — `kaptGenerateStubsDebugKotlin` succeeded — so the annotation
was silently degraded to nothing, and `favorites` would have shipped with NO primary key. Only
`kaptDebugKotlin`'s javac pass on the generated stub failed, with:

    error: incompatible types: NonExistentClass cannot be converted to Annotation
    public FavoriteTrack(@error.NonExistentClass()

**`@error.NonExistentClass` in a kapt failure always means a missing import, never a Room bug.**

Two lessons for this project:
1. Reading a file cannot catch this class of bug, and neither can `node --check`-style syntax
   checks. The agent's report explicitly claimed "every androidx.room.* annotation has a matching
   import — checked per file"; it was false for one of five files. Any claim of the form "I checked
   every X" must be re-derived mechanically, with its output shown, or verified by the build.
2. The orchestrator's build is not a formality after a code review — for annotation-processed code
   it is the ONLY verification that exists.

Verified green afterwards: `BUILD SUCCESSFUL`, the `room.schemaLocation not recognized` warning
gone (proof room-compiler now processes the `@Database`), and `android/app/schemas/…/1.json`
exported with all three tables, the unique `(playlistId, path)` index, and the CASCADE FK.
