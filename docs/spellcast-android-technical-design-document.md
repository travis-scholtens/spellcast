# **Technical Design Document: Spellcast (Spelly) Port to AAOS**

Automotive Embedded Systems Engineering Specification for Turn-Based Roguelike Word Applications

## **1. Overview & Objectives**

This document specifies the technical design, architectural framework, and lifecycle strategies required to port **Spellcast (Spelly)**—a single-player, turn-based procedural dungeon-crawler and word game—to connected vehicle environments. The deployment targets **Android Automotive OS (AAOS)** (native execution on vehicle Head Units / In-Vehicle Infotainment systems), with architecture isolated so **Android Auto** projection can be added in the future if Android Auto opens up canvas rendering for parked games.

### **1.1 Core Gameplay Fidelity**

This port is a faithful implementation of the web original ([`app.js`](../app.js), [`bingo.py`](../bingo.py)):

* **Procedural Dungeon Crawling:** Multi-depth levels (`24x24` at depth 1 scaling up to `64x64` at depth 5+) generated with room/corridor digging, box-drawing walls, up/down stairs, and shadowcasting field-of-view (radius 7).
* **Speed-Scheduled Combat & Corpses:** Monsters `a`–`z` plus the `.` metamorph shapeshifter act on a speed-based action-time scheduler (where each turn advances scheduler time by `1 / speed`, e.g., `0.01` time units for a speed-100 ant), with stats, Scrabble values, and spawn frequencies defined per depth. Slain monsters drop corpses that rot over time (`> 0.9` scheduler time units to start rotting, `> 1.0` time units to rot away).
* **Corpse Rack & Spell Casting:** Players hold up to 6 corpses in their rack plus 1 underfoot floor slot. Tapping rack/floor tiles moves them into a center modal to **Drop** (1 tile) or **Cast** (2–7 tiles) valid English words for Scrabble-based experience points (`+50` bonus for 7-letter bingos, blank `.` wildcard resolution cycling through unused matches), amplified by floor shrines (`Biglyphica` 2× letter, `Trirunius` 3× letter, `Duverbia` 2× word, `Troilogos` 3× word).
* **Progression, Achievements & Victory:** Experience levels alternate between upgrading player mean attack damage and speed; descending to a new depth restores health. Casting `YENDOR` ascends and wins the game, tracking 15 achievements and high scores.

### **1.2 Technical Goals**

* **Deterministic Battery & Thermal Efficiency:** Transition the render cycle from a continuous loop to a strictly reactive, event-driven pattern (`non-continuous rendering`). The system targets 0% GPU/CPU utilization while waiting for player input.
* **Unified Codebase:** Isolate game domain rules into a pure Kotlin core under `/android`, leveraging LibGDX multiplatform abstractions (`SpriteBatch` + `gdx-freetype`) for windowing and 1:1 `ROT.Display` ASCII grid rendering, alongside direct Kotlin ports of `rot.js` (`Digger`, `PreciseShadowcasting`, `AStar`, and Alea `RNG`) for map generation, pathfinding, field-of-view mechanics, and deterministic RNG serialization.
* **Dynamic Display-Adaptive Grid:** Calculate visible dungeon tile rows and columns dynamically from the vehicle display dimensions and target cell size, rather than assuming the web original's fixed `26x26` grid.
* **Regulatory Driver Safety Compliance:** Enforce rigorous parked-only restrictions via Android’s `CarUxRestrictions` and `CarDrivingState` APIs, guaranteeing that interactive game surfaces cannot be viewed or manipulated while the vehicle is in gear or traveling above 0 km/h.

## **2. Architecture & Rendering Engine**

Automotive IVI (In-Vehicle Infotainment) hardware exhibits strict thermal throttling envelopes and operates in shared compute environments alongside safety-critical vehicle subsystems. Standard game execution loops (running continuous 60 FPS delta ticks) cause thermal spikes and battery drain, which are unacceptable in automotive integration.

### **2.1 Decoupled Core Layering**

The application uses a decoupled architectural pattern dividing OS-specific harnesses, engine lifecycle bridges, and core domain logic:

* **Platform Adapter Layer (`/android/automotive`):** Houses the AAOS native `Activity` integration (`AutomotiveHarnessActivity`). This layer intercepts vehicle state signals (parked/driving), hardware input bindings, and Android lifecycle events.
* **LibGDX Host Adapter (`/android/automotive`):** Hosts an `AndroidApplication` context wrapping the native rendering surface (`SurfaceView` or `TextureView`) and injecting the platform bridge into the game runtime.
* **Engine Core (`/android/core` — Kotlin + LibGDX + `gdx-freetype`):** Fully isolated from Android system dependencies. Implements a custom `AsciiDisplay` grid matching `ROT.Display` (including stacked multi-glyph cells like `[letter, subscriptValue, underline]` and `%c{fg}%b{bg}` markup word-wrapping), speed-based turn scheduler, `rot.js`-ported dungeon generator/FOV/A* pathfinder, dictionary validation (`assets/words.txt` filtered to 2–7 lowercase letters from `dict/words` plus `yendor`), achievement evaluation, and high-score tracking.

### **2.2 Event-Driven Rendering Pipeline**

To minimize resource consumption, continuous rendering is disabled immediately upon engine initialization:

```kotlin
override fun create() {
    Gdx.graphics.isContinuousRendering = false
    Gdx.graphics.requestRendering()
}
```

Calls to `Gdx.graphics.requestRendering()` are dispatched exclusively when specific events invalidate the frame buffer:

* User interaction events (e.g., tapping a dungeon tile to walk/attack, tapping a rack/floor corpse tile, or interacting with the spell modal).
* Multi-step player path walking (`10 ms` step cadence along an A* path until interrupted by a newly revealed foe or shrine, corpse pickup, stair transition, or combat/rot message) and visible multi-turn monster movement ticks (`100 ms` step cadence when a foe acts more than once since `lastDrawnAt`).

```
+-----------------------------------------------------------+
|                   Platform Input / Turn Event             |
+-----------------------------------------------------------+
                              |
                              v
             [Gdx.graphics.requestRendering()]
                              |
                              v
+-----------------------------------------------------------+
|                     render(delta: Float)                 |
|  1. Render revealed dungeon tiles, FOV, stairs & shrines  |
|  2. Render player, visible foes, health/XP meters & log   |
|  3. Render bottom corpse rack (6+1 slots) & active modal  |
|  4. If multi-step walk/foe step pending -> Schedule tick  |
+-----------------------------------------------------------+
```

## **3. Technology Mapping Matrix**

The migration from the web (`rot.js`) profile to Android Automotive uses the following stack:

| Layer | Web Original (`spellcast`) | Automotive Stack (AAOS) | Architectural Rationale |
| :---- | :---- | :---- | :---- |
| **Language & SDK** | ES5 JavaScript + Python build scripts | Kotlin 2.0+ (JVM 17), Android SDK `compileSdk = 35` / `minSdk = 29` | Null-safe domain modeling, clean serialization, and idiomatic Android/LibGDX interop targeting AAOS (Android 10+ baseline, current SDK 35). |
| **Base Engine** | `rot.js` (`ROT.Display` HTML5 Canvas) | LibGDX 1.12+ (`gdx-backend-android`, `gdx-freetype`) | Direct access to OpenGLES 2.0/3.0 abstraction over AAOS `SurfaceView`/`TextureView` with a lightweight custom `AsciiDisplay` renderer supporting stacked per-cell glyphs and `%c{...}` / `%b{...}` markup. |
| **Grid, FOV & Path** | `rot.js` (`ROT.Map.Digger`, `PreciseShadowcasting`, `AStar`, `RNG`) | Pure Kotlin ports of `rot.js` algorithms | Guarantees 1:1 procedural room/corridor digging, radius-7 shadowcasting FOV, 4-way A* pathfinding, and exact Alea `RNG` state serialization without external roguelike library bloat. |
| **Font & Glyphs** | `SpellyFreeMono.ttf` (`freefont/FreeMono.sfd`) | Build step: `FreeMono.sfd` -> `assets/SpellyFreeMono.ttf` loaded via `FreeTypeFontGenerator` | Generates `SpellyFreeMono.ttf` via FontForge and rasterizes crisp DPI-matched `BitmapFont` glyphs at runtime (including Private Use Area `\uE071`–`\uE092` subscript scores, locks, checkmarks, crosses, and box-drawing walls). |
| **Dictionary** | `WORDS_FILE` -> `compiled_words.js` | `assets/words.txt` (generated from `/usr/share/dict/words`) | Build step filters `/usr/share/dict/words` to 2–7 lowercase ASCII `[a-z]` words (excluding proper nouns and punctuation, matching `Makefile` + `gen_words.sh`, plus `yendor`), loaded into an in-memory `HashSet` + regex matcher for `.` blanks. |
| **Persistence** | `localStorage` (`spellyGameState`, `spellyLevelState*`) | Jetpack DataStore / Room SQLite (WAL) | ACID compliance, robust against sudden vehicle power drops (Key-Off). |
| **Safety Hook** | N/A (Mobile Browser) | Android Car API (`android.car` stub, `CarUxRestrictionsManager`) | Dynamically evaluates driving restrictions (`DRIVING_STATE_PARKED`) via `useLibrary("android.car")`. |

## **4. Display-Adaptive Layout & Input Scheme**

Center Information Displays (CIDs) vary widely in aspect ratio and DPI across vehicle models. Rather than hardcoding the web version's `26x26` character grid (`fontSize: 24`, aspect ratio `0.625`), the viewport dynamically computes the tile grid from the physical display dimensions while keeping HUD elements anchored cleanly.

### **4.1 Dynamic Tile Grid Calculation & HUD Anchoring**

* **Target Cell Size & Display-Driven Tile Count:** On `resize(width, height)`, the renderer uses a target cell size of **`24dp × 38dp`** (preserving the original `0.625` monospace width-to-height ratio while ensuring at least `26×26` cells fit on standard automotive displays). It computes `columns = max(26, width / cellWidth)` and `rows = max(26, height / cellHeight)`, centering the dungeon camera on the player's `(x, y)` coordinates between the top status/message rows and bottom rack rows.
* **Status Bar & Power Meters Alignment:**
  * The left-edge vertical health meter (`♥` in `lightpink`, `0..50` HP in eighths) is drawn in column `0` (`x = 0`), and the right-edge vertical experience meter (`⚡` in `lightgreen`, current XP level progress in eighths) is drawn in the rightmost column (`x = columns - 1`), spanning from `y = messageCount + 1` down to `rows - 4`.
  * The top status bar (`y = 0`) aligns directly above the meters on either side of the screen: dungeon depth (`D: <depth>`) is **left-aligned** starting at `x = 0` above the health meter, experience level (`L: <level>`) is **right-aligned** ending at `x = columns - 1` above the experience meter, and total experience score is horizontally **centered** at `x = (columns - scoreText.length) / 2`.
* **Centered Corpse Rack:** The bottom 2 rows (`y = rows - 2` and `y = rows - 1`) render the 26-column rack assembly **horizontally centered** at `rackOriginX = (columns - 26) / 2`:
  * The 6-slot inventory rack occupies `rackOriginX .. rackOriginX + 19` (`20` columns in `tan`, with slots at `rackOriginX + 2 + 3 * i` for `i in 0..5`).
  * After a 1-column gap (`rackOriginX + 20`), the 1-slot underfoot floor corpse tray occupies `rackOriginX + 21 .. rackOriginX + 25` (`5` columns in `gray`, with the tile at `rackOriginX + 23`).
* **Touch Targets:** At `24dp × 38dp` per cell, each 3×2-cell rack slot and 3-row modal/menu button provides a **`72dp × 76dp`** touch hit area (satisfying Android Automotive's `≥ 48dp–76dp` touch target requirements), while dungeon tile taps include the 3×3-tile nearest-passable-neighbor tolerance from `getTileClickCoords` in `app.js`.

### **4.2 Touch-First Rack & Dungeon Controls**

* **Dungeon Navigation & Combat:** Tapping a revealed dungeon tile computes a 4-way A* path (`topology = 4`, avoiding stairs unless they are the destination). If the adjacent tile along the path contains a foe, the player attacks; otherwise the player walks step-by-step until reaching the target or encountering an event. Optional D-pad/rotary directional keys map to single-tile N/E/S/W steps.
* **Touchscreen Corpse Rack & Spell Modal:** Interaction with the 6-slot inventory rack and 1 underfoot floor slot is strictly touchscreen-driven:
  * Tapping occupied rack/floor slots appends corpses to the centered spell modal, displaying subscript letter values, active shrine multipliers (underlined in the shrine's color), and known-good (`✓` lime) or known-bad (`✗` red) word indicators.
  * With 1 rack corpse selected, the modal offers `Drop`; with 2–7 corpses selected, it offers `Cast`; tapping `X` returns all tiles to the rack/floor without spending a turn.

## **5. Turn-Based Persistence Strategy**

Vehicular power management operates across unpredictable operating states: immediate ignition shutoff, accessory timeouts, and platform-driven low-memory terminations. A zero-loss persistence strategy mirrors the `localStorage` save hooks in `app.js`.

```
       [Player Turn / Stairs / Modal Action]
                      |
                      v
     Serialize Attempt & Active Level State
                      |
                      v
     Atomic Write to Room Database (WAL)
                      |
                      v
        State Persisted (Safe Idle)
                      |
      +---------------+---------------+
      |                               |
Vehicle Key-Off                OS Low-Memory Kill
      |                               |
      +---------------+---------------+
                      |
                      v
          [Vehicle System Reboot]
                      |
                      v
    Query Active Attempt & Visited Levels
                      |
                      v
    Restore Scheduler, FOV, Rack & Dungeon
```

### **5.1 Game State Snapshot Schema**

The persistence schema directly models the Spelly `Attempt`, `Player`, `Level`, `Foe`, and `Corpse` state structures:

```kotlin
data class CorpseSnapshot(
    val glyph: Char,
    val ageTicks: Double // Game.scheduler.getTime() - corpse.timeOfDeath
)

data class CastSpellRecord(
    val tiles: String,
    val depth: Int,
    val shrineType: Int?,
    val formatted: String
)

data class FoeSnapshot(
    val x: Int,
    val y: Int,
    val kindGlyph: Char,
    val shiftedKindGlyph: Char?,
    val hurtSinceShift: Boolean,
    val lastSawPlayer: Int?,
    val revealed: Boolean,
    val health: Int,
    val scheduledTime: Double
)

data class TileSnapshot(
    val x: Int,
    val y: Int,
    val glyph: Char,
    val corpseGlyph: Char?,
    val corpseAgeTicks: Double?,
    val revealed: Boolean
)

data class LevelSnapshot(
    val depth: Int,
    val width: Int,
    val height: Int,
    val radius: Double,
    val upX: Int,
    val upY: Int,
    val downX: Int,
    val downY: Int,
    val shrineX: Int?,
    val shrineY: Int?,
    val shrineType: Int?,
    val tiles: List<TileSnapshot>,
    val foes: List<FoeSnapshot>
)

data class AttemptSnapshot(
    val rngState: List<Double>,
    val balanceVersion: Int,
    val ascended: Boolean,
    val complete: Boolean,
    val playerDepth: Int,
    val playerX: Int,
    val playerY: Int,
    val playerWoundedness: Int,
    val playerExperience: Int,
    val playerColor: String,
    val rack: List<CorpseSnapshot>,
    val remainingShrines: Map<Int, Int>,
    val triedShrine: Boolean,
    val castAtShrines: Int,
    val usedFoes: Set<Char>,
    val wastedCorpse: Boolean,
    val spellsCast: List<CastSpellRecord>,
    val goodWords: List<String>,
    val badPatterns: List<String>,
    val wordRepeated: Boolean,
    val attemptAchievements: Set<String>,
    val lastDrawnAgeTicks: Double? // Game.scheduler.getTime() - display.lastDrawnAt
)
```

### **5.2 Serialization Cycle & Crash Resistance**

* **Commit Hooks:** State is persisted whenever the player completes a turn or path walk, transitions stairs, finishes casting/dropping in the modal, or when Android invokes `onPause()` / `onStop()`. Global achievements and top-12 high scores are persisted alongside the active attempt.
* **Database Pipeline:** Game state writes use Room SQLite with Write-Ahead Logging (WAL) enabled (or Jetpack DataStore), ensuring atomic commits under `< 10` ms.
* **Rehydration Flow:** On launch, if an incomplete `AttemptSnapshot` exists, the game offers `Continue` on the title screen (or resumes directly into the active session), restoring exact scheduler timestamps (`Game.scheduler.getTimeOf(foe)`) so monsters never get an extra free turn on reload.

## **6. AAOS Deployment Configuration**

Deploying to Android Automotive OS requires explicit manifest declarations, category filters, and driving-state safety enforcement. (Android Auto `CarAppService` projection is omitted for now until Android Auto supports custom canvas rendering for parked games.)

### **6.1 Manifest Declarations (`AndroidManifest.xml`)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.spellcast.automotive">

    <!-- Operating limits for hardware profiling -->
    <uses-feature android:name="android.hardware.type.automotive" android:required="true" />
    <uses-feature android:name="android.hardware.touchscreen" android:required="true" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/AutomotiveTheme">

        <!-- Automotive Declaration Metadata -->
        <meta-data
            android:name="com.google.android.gms.car.application"
            android:resource="@xml/automotive_app_desc" />

        <!-- AAOS Native Activity Target -->
        <activity
            android:name=".AutomotiveHarnessActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:screenOrientation="unspecified"
            android:configChanges="orientation|screenSize|screenLayout|density">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="android.intent.category.APP_GAMES" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

### **6.2 Automotive App Descriptor (`automotive_app_desc.xml`)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="games" />
</automotiveApp>
```

### **6.3 Driving State Interceptor & Safety Enforcement**

Automotive compliance dictates that entertainment applications must block interaction while the car is in motion. The engine hooks into Android's `CarUxRestrictionsManager` to suspend frame updates and display a standardized safety lock overlay:

```kotlin
class SafetyRestrictionManager(
    private val context: Context,
    private val onRestrictionChanged: (isRestricted: Boolean) -> Unit
) {
    private var car: Car? = null
    private var uxRestrictionsManager: CarUxRestrictionsManager? = null

    private val restrictionListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { restrictions ->
        val requiresLockout = (restrictions.activeRestrictions and
            CarUxRestrictions.UX_RESTRICTIONS_NO_INTERACTION) != 0
        onRestrictionChanged(requiresLockout)
    }

    fun init() {
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            car = Car.createCar(context)
            uxRestrictionsManager = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            uxRestrictionsManager?.registerListener(restrictionListener)

            // Check immediate state on boot
            val currentRestrictions = uxRestrictionsManager?.currentCarUxRestrictions
            val isRestricted = ((currentRestrictions?.activeRestrictions ?: 0) and
                CarUxRestrictions.UX_RESTRICTIONS_NO_INTERACTION) != 0
            onRestrictionChanged(isRestricted)
        }
    }

    fun dispose() {
        uxRestrictionsManager?.unregisterListener()
        car?.disconnect()
    }
}
```

When `onRestrictionChanged(true)` triggers:

* Any in-progress player path walk is paused and the current `AttemptSnapshot` is immediately saved.
* Engine input dispatchers are detached via `Gdx.input.inputProcessor = null`.
* An accessible high-contrast overlay stating **"Vehicle In Motion: Gameplay Suspended"** is surfaced over the rendering context.
* Event loops halt until physical transmission sensors report the vehicle is back in `DRIVING_STATE_PARKED`.
