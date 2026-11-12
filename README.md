# Slot Cycler

A lightweight Fabric client mod (Minecraft 1.21.11).

Press **`X`** to cycle an item between hotbar slots 1 and 2 repeatedly.
Built with [Fabric](https://fabricmc.net/) and [Kotlin](https://kotlinlang.org/).

---

## Features

| Feature | Details |
|---------|---------|
| **Keybind** | `X` starts the slot cycle (rebindable via the Controls screen) |
| **Fast** | One full switch (pick up + place) every tick; 3 there-and-back cycles finish in 6 ticks |
| **Surplus drop** | If slot 1 ends up with more items than it started with, the extras are dropped (like pressing `Q`) |
| **Safe** | Runs on the client thread, ignores key presses mid-sequence, and cancels if you open a container or leave the world |
| **In-game feedback** | An action-bar message reports the result and how many extras were dropped |

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/install/) for Minecraft **1.21.11**.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) and [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) into your `mods/` folder.
3. Download the latest `slotcycler.jar` from [Releases](https://github.com/ImGabri/slotcycler/releases).
4. Drop the jar into your `mods/` folder.
5. Launch the game and press **`X`** in-game.

---

## Building from source

Requires **JDK 21**.

```bash
git clone https://github.com/ImGabri/slotcycler.git
cd slotcycler
./gradlew build
```

The built mod appears at `build/libs/slotcycler.jar` (archive name set in `build.gradle.kts`).

---

## Project structure

```
slotcycler/
├── build.gradle.kts          # Gradle build (Fabric Loom + Kotlin)
├── gradle.properties         # Gradle configuration
├── settings.gradle.kts
├── src/
│   └── main/
│       ├── kotlin/slotcycler/SlotCyclerMod.kt     # mod logic
│       └── resources/fabric.mod.json              # mod metadata
├── LICENSE                   # MIT
└── README.md                 # this file
```

---

## How it works

The mod registers a Fabric `KeyBinding` on `X`. When pressed, it records how many items
are in hotbar slot 1, then a tick-based state machine walks the slot sequence
`36 → 37 → 37 → 36` (hotbar slots 1 ↔ 2) three times, sending two clicks per tick.

Each time the stack is back in slot 1, and for 5 ticks after the last click, the mod
compares the slot's count with the starting count. Any surplus is dropped one item at a
time with a `THROW` slot click, which is what `Q` does.

Speed, cycle count and the watch window are the `CLICKS_PER_TICK`, `CYCLES` and
`SETTLE_TICKS` constants at the top of `SlotCyclerMod.kt`.

---

## License

[MIT](LICENSE) — free to use, modify, and distribute.

---

Made with ❤️ by [ImGabri](https://github.com/ImGabri).
