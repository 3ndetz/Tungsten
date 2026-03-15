# DEVELOP.md — How to build and run Tungsten

## Prerequisites

| Requirement | Version |
|---|---|
| Java (JDK) | 21 |
| Gradle | bundled via `gradlew` (no separate install needed) |
| Git | any recent version |
| Internet | needed for the first build (~1 GB: MC + Fabric + nether-pathfinder) |

---

## Folder structure (required)

```
<any folder>/
├── Tungsten/               <- mod source, run gradle here
└── baritone_altoclef/      <- separate repo, must sit NEXT TO Tungsten/
    ├── baritone/           <- git submodule (cabaletta/baritone)
    ├── patches/            <- altoclef patches for baritone
    ├── maven/              <- separate git repo with prebuilt JARs (MiranCZ/maven)
    ├── apply_patches.bat   <- Windows
    └── apply_patches.sh    <- Linux/Mac
```

`Tungsten/build.gradle` pulls Baritone from `../baritone_altoclef/maven` —
both folders **must** be side by side.

---

## Step 1 — Clone baritone_altoclef (one time)

```bash
# Clone next to Tungsten/ (skip if already there)
git clone https://github.com/3ndetz/baritone_altoclef
cd baritone_altoclef

# Pull the baritone submodule
git submodule update --init

# maven/ is a separate git repo with prebuilt JARs.
# It comes with the clone — nothing to build.
```

That's it. `maven/cabaletta/baritone-unoptimized-fabric/1.21/` already contains
a ready-to-use `baritone-unoptimized-fabric-1.21.jar`. Jump to step 2.

---

## Step 1.5 — Rebuild Baritone (only if you changed baritone code)

Only needed if you edited files inside `baritone/` (patches, fixes, etc.).
If you just cloned — **skip this step**.

```bash
cd baritone_altoclef/baritone

# Switch to the 1.21 branch
git checkout origin/1.21

# Apply patches
cd ..
apply_patches.bat           # Windows
bash apply_patches.sh       # Linux/Mac

# Build Baritone
cd baritone
gradlew.bat :fabric:build -x test    # Windows
./gradlew :fabric:build -x test      # Linux/Mac
```

> The first baritone build is slow (5-10 min) — downloads mappings, MC, forge deps.
> Subsequent builds are fast (~30 sec).

After the build, the JAR will be in:

```
baritone/fabric/build/libs/baritone-fabric-*.jar
```

Copy it into `maven/`:

**Windows (PowerShell):**

```powershell
$src = "baritone\fabric\build\libs"
$dst = "maven\cabaletta\baritone-unoptimized-fabric\1.21"
Copy-Item "$src\baritone-unoptimized-fabric-fabric-*.jar" "$dst\baritone-unoptimized-fabric-1.21.jar" -Force
```

**Linux/Mac:**

```bash
cp baritone/fabric/build/libs/baritone-unoptimized-fabric-fabric-*.jar \
   maven/cabaletta/baritone-unoptimized-fabric/1.21/baritone-unoptimized-fabric-1.21.jar
```

> Alternative: run `maven-windows.bat` or `maven-unix.sh` from `maven/maven-scripts/` —
> they use `mvn install:install-file` to do the same thing.

---

## Step 2 — Run Tungsten

```bash
cd Tungsten
./gradlew runClient         # Linux/Mac
gradlew.bat runClient       # Windows
```

**First run** downloads (~1 GB):

- Minecraft 1.21 + native libs (Mojang CDN)
- Fabric Loader 0.16.2 + Fabric API (FabricMC maven)
- `dev.babbaj:nether-pathfinder:1.5` (babbaj.github.io/maven)

`baritone-unoptimized-fabric:1.21` is pulled **locally** from `../baritone_altoclef/maven`.

Everything is cached in `~/.gradle/caches/` after the first download.

---

## Build JAR (deploy to server/client)

```bash
cd Tungsten
./gradlew build             # Linux/Mac
gradlew.bat build           # Windows
```

Output: `build/libs/tungsten-fabric-ALPHA-1.6.0-1.21compat.jar`

Drop it into `.minecraft/mods/` as a regular Fabric mod.
Baritone and nether-pathfinder are already **bundled** inside the JAR (via `include` in build.gradle).

---

## Mod config

Created on first launch: `.minecraft/config/tungsten.json`

```json
{
  "driftCorrectionEnabled": false,
  "driftThreshold": 0.5,
  "verboseDebugLogging": false,
  "baritoneEnabled": true
}
```

Change via in-game chat commands (auto-saves):

```
;settings baritone [true/false]        — parallel Baritone fallback
;settings verboseDebug [true/false]    — verbose console logging
;settings driftCorrection [true/false]
;settings driftThreshold [0.5]
```

Without an argument — shows the current value.

---

## Mod commands

| Command | Description |
|---|---|
| `;followPlayer <name>` | Follow a player (push mode) |
| `;followPlayer <name> <radius>` | Follow at a given radius distance |
| `;stop` | Stop everything |
| `;goto <x> <y> <z>` | Go to coordinates |

---

## Pathfinding architecture

```
dist < 6 + LOS  ->  SUPER_FAST: direct sprint + WindMouse rotation (~60 FPS)
dist >= 6       ->  Tungsten A* (always, primary)
                    + Baritone GoalFollowEntity (fallback while Tungsten is searching)
dist > 20       ->  TRAILING: navigate along the target's movement trail (waypoints)
```

- Tungsten finds a path -> executor starts -> Baritone stops immediately
- After executor finishes, Baritone waits 3 sec (cooldown = 60 ticks) before starting
- TRAILING: target "escaped" (dist>20 / height diff>5 / no progress for 5 sec) -> follow their trail
- `baritoneEnabled=false` -> Tungsten A* only, no fallback
