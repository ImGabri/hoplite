# hoplite

A lightweight Fabric client mod for **Hoplite.gg** (Minecraft 1.21.1).

Press **`X`** to cycle an item between hotbar slots 1 and 2 repeatedly.  
Built with [Fabric](https://fabricmc.net/) and [Kotlin](https://kotlinlang.org/).

---

## Features

| Feature | Details |
|---------|---------|
| **Keybind** | `X` starts the slot cycle (configurable via the default keybinds screen) |
| **Debounced** | Holding `X` only fires once — no accidental re-triggers |
| **Thread-safe** | Runs on the main client thread; never touches Minecraft from a background thread |
| **In-game feedback** | Action-bar messages tell you when the sequence starts and completes |
| **No magic numbers** | Slots, delays, and attempt counts are named, documented constants |

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/install/) for Minecraft **1.21.1**.
2. Download the latest `hopliter.jar` from [Releases](https://github.com/ImGabri/hoplite/releases).
3. Drop the jar into your `mods/` folder.
4. Launch the game and press **`X`** in-game.

---

## Building from source

Requires **JDK 21** (Fabric Loom setup) and **Gradle**.

```bash
git clone https://github.com/ImGabri/hoplite.git
cd hoplite
./gradlew jar
```

The built mod appears at `build/libs/hopliter.jar` (archive name set in `build.gradle.kts`).

---

## Project structure

```
hoplite/
├── build.gradle.kts          # Gradle build (Fabric Loom + Kotlin)
├── gradle.properties         # Gradle configuration
├── settings.gradle.kts
├── src/
│   └── main/
│       ├── kotlin/hoplitedupe/HopliteDupeMod.kt   # mod logic
│       └── resources/fabric.mod.json              # mod metadata
├── LICENSE                   # MIT
└── README.md                 # this file
```

---

## How it works

The mod registers a Fabric `KeyBinding` on `X`. When pressed, a tick-based state machine
walks through the slot sequence `36 → 37 → 37 → 36` (hotbar slots 1 ↔ 2) up to 3 times,
waiting 2 ticks between clicks. All interaction happens on the main client thread via
`ClientPlayerInteractionManager.clickSlot(...)`, so there's no risk of concurrency issues
or game freezes from background threads.

---

## License

[MIT](LICENSE) — free to use, modify, and distribute.

---

Made with ❤️ by [ImGabri](https://github.com/ImGabri).