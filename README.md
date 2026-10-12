# wearopengym — the Wear OS companion for openGym

Native Compose for Wear OS. No WebView, no Capacitor: the watch renders its own UI and mirrors a
live workout from the phone's openGym app. Architecture and scope: `../docs/plan.md`.

## Configuration

| Setting | Value |
|---|---|
| `applicationId` | `ch.duartesantos.opengym` — **identical to the phone app.** Hard Wearable Data Layer requirement (matching package name *and* signing key). Do not change. |
| `namespace` / Kotlin root | `ch.duartesantos.opengym.wear` — deliberately different from the applicationId, so the launcher component is `ch.duartesantos.opengym/ch.duartesantos.opengym.wear.MainActivity` |
| `app_name` | `openGym` |
| Gradle project | `wearopengym` |
| UI toolkit | Wear Compose Material 3 `1.7.1` |
| minSdk / targetSdk / compileSdk | `30` / `36` / `37` |
| App model | Standalone (`com.google.android.wearable.standalone = true`) |

## Layout

```
app/src/main/java/ch/duartesantos/opengym/wear/
├── MainActivity.kt                 # creates the repository, hosts WearApp
├── data/
│   ├── WorkoutState.kt             # the projection the screens render (10 fields)
│   ├── WorkoutDocument.kt          # ActiveWorkout / WorkoutExercise / WorkoutSet + the seed
│   ├── WorkoutProjection.kt        # set index, prev-set lookup, clamps, ActiveWorkout -> WorkoutState
│   ├── WorkoutRepository.kt        # the interface + TimeSource
│   └── FakeWorkoutRepository.kt    # in-memory implementation, no phone needed
├── navigation/Destination.kt       # Destination + destinationFor(WorkoutState)
├── theme/
│   ├── OpenGymColors.kt            # the palette from docs/research/00-ux-references.md
│   └── OpenGymTheme.kt             # OpenGymWearTheme + OpenGymSpacing + the type-role mapping
└── ui/
    ├── WearApp.kt                  # AppScaffold + the two destinations
    ├── exercise/ExercisePagePlaceholder.kt   # PLACEHOLDER — M2.3 replaces it
    └── rest/RestTimerPlaceholder.kt          # PLACEHOLDER — M2.5 replaces it
```

`data/` has no `android.*` imports, so all of it is unit-testable on the JVM.

## Behaviour the screens can rely on

- The destination is derived from state, not stored: `destinationFor` returns `RestTimer` while
  `restSecondsRemaining > 0`, otherwise `ExercisePage`. `completeSet()` starts the rest clock, so
  `✓` advances; the clock reaching zero (or `skipRest()`) returns.
- `currentSetIndex` is `count { done } + 1`, clamped to `1..totalSets`.
- `previousWeightKg` / `previousReps` prefer a set completed in this session and fall back to the
  seeded previous-session history; both are null when neither exists.
- Weight snaps to 0.5 kg and clamps to 0..500; reps clamp to 1..99; rest clamps to 0..600 s.
- Finishing an exercise advances to the next one and rests for the duration of the exercise just
  completed. Finishing the session's last set ends it — `WorkoutState.None`, `isWorkoutActive`
  false, `restSecondsRemaining == 0` and no rest started.
- The fake is deterministic: it reads time only through the injected `TimeSource`, and its rest
  ticker runs on an injected `CoroutineScope`, so tests drive whole countdowns with
  `advanceTimeBy`.

## Build, run, test

> The SDK and JDK are not on `PATH`; `local.properties` covers `sdk.dir`, but `JAVA_HOME` must be
> set. `scripts/wear.ps1` does all of it. Three emulators are usually up on this box, so export
> `ANDROID_SERIAL=emulator-5554` before the Gradle commands too — it is what keeps
> `connectedDebugAndroidTest` from failing with "more than one device".

```powershell
pwsh -File scripts/wear.ps1 -Command loop     # build + install + launch + wait for content
pwsh -File scripts/wear.ps1 -Command dump     # exact labels and pixel bounds
pwsh -File scripts/wear.ps1 -Command shot     # PNG (hand it to the vision subagent, don't read it)
pwsh -File scripts/wear.ps1 -Command test     # instrumented tests; prints the <testsuite> line
```

```bash
# the underlying commands
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest
ANDROID_SERIAL=emulator-5554 ./gradlew.bat :app:connectedDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n ch.duartesantos.opengym/ch.duartesantos.opengym.wear.MainActivity
```

## Status

- ✅ Installed and launched on `WearHello_API34` (Wear OS 5, API 34, 454×454). `pm list packages`
  reports `package:ch.duartesantos.opengym`.
- ✅ Unit tests: `FakeWorkoutRepositoryTest` 17, `WorkoutProjectionTest` 18, `DestinationTest` 3 —
  38 tests, `failures="0"`.
- ✅ Instrumented: `MainActivityLaunchTest` `tests="3" failures="0" errors="0" skipped="0"` —
  launch, `✓` → rest timer, `Skip` → back to the exercise page on set 2.
- ✅ The theme is live, confirmed by pixel-checking a screenshot: 72.5 % `#000000` background,
  21.7 % `#2196F3` (the accent-filled buttons), `#FFFFFF` and `#AAAAAA` text. Not dynamic colour.
- ⚠️ `applicationId` still matches the phone app's *release* package. The debug build type does not
  add a suffix, so installing this watch app alongside an openGym debug build on one device would
  collide — irrelevant for the watch, relevant if anyone tests on a phone.
- ⚠️ No signing config yet; both the watch app and the fork still need the shared keystore (M4.2).
- ⏳ Both screens are placeholders. Delete the placeholder file when the real screen lands.
