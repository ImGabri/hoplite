# Slot Cycler

A lightweight **client-only** Fabric mod for Minecraft **1.21.11**. It works in single
player and on unmodified multiplayer servers: nothing needs to be installed in a server's
`mods/` folder, and the mod sends no custom server packets.

Press **`X`** to cycle an item between two hotbar slots, check for ghost items, and drop
any surplus. Press **`Right Shift`** to open the settings screen.
Built with [Fabric](https://fabricmc.net/) and [Kotlin](https://kotlinlang.org/).

---

## Features

| Feature | Details |
|---------|---------|
| **Keybinds** | `X` runs the cycle, `Right Shift` opens settings (both rebindable in Controls) |
| **Settings screen** | Sliders and switches for slots, cycles, clicks per tick, delays and wait times, with a live preview of the run |
| **Client-only** | Only the client installs the mod; there is no server entrypoint or custom networking channel |
| **Ghost clicks** | Optionally apply the clicks on the client only, so the server never sees them, ending with a display-only copy so the item shows in both slots |
| **Real copy** | Creates a genuine server-side copy in singleplayer (any game mode) or multiplayer creative mode, using only the integrated server or vanilla creative packets; no server mod is needed |
| **Keep ghost visible** | The display copy is redrawn whenever the server clears it, until you press Clear ghosts |
| **Ghost check** | After a run, asks the server for the real inventory and reports how many items were ghosts |
| **Surplus drop** | If slot A ends up with more items than it started with, the extras are dropped (like pressing `Q`) |
| **Safe** | Runs on the client thread, ignores key presses mid-run, and cancels if you open a container or leave the world |

Settings are saved to `config/slotcycler.json`.

> **Real copy availability.** Enable **Ghost clicks** and **Real copy**, and leave slot B
> empty. In singleplayer, the mod asks the integrated server to create the copy after
> validating that the real inventory has not changed. On a multiplayer server, vanilla
> Minecraft accepts the equivalent creative-inventory packet only in **creative mode**.
> In survival or adventure mode on a remote server, the mod falls back to a display copy.
>
> **Display-only means display-only.** Minecraft servers own their inventories. No
> client-only mod can create a real item in survival on an arbitrary unmodified remote
> server. A display copy cannot be used, dropped, traded, or stored as a real item. Normal
> (non-ghost) slot cycling still uses real server-authoritative inventory movements.

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/install/) for Minecraft **1.21.11**.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) and [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) into your `mods/` folder.
3. Download the latest `slotcycler.jar` from [Releases](https://github.com/ImGabri/slotcycler/releases).
4. Drop the jar into your `mods/` folder.
5. Launch the game and press **`X`** in-game.

Only the **client** needs Slot Cycler. Do not install anything on the server. Both Fabric API
and Fabric Language Kotlin are client-side dependencies; the server does not need copies of
them for this mod to work.

---

## Building from source

Requires **JDK 21**.

```bash
git clone https://github.com/ImGabri/slotcycler.git
cd slotcycler
./gradlew build
```

The built mod appears at `build/libs/slotcycler-<version>.jar`.

### One command: build and install

From the VS Code terminal (or any terminal) in the project folder:

```bash
./gradlew installMod
```

On Windows use `.\gradlew installMod`. This builds the jar and copies it into your
Minecraft `mods` folder, replacing any older Slot Cycler jar. If your mods folder is
somewhere else, add `-PmodsDir="C:\path\to\mods"`.

In VS Code you can also press `Ctrl+Shift+B`, which runs the same thing.

### One command: download the latest release

No JDK needed; this just downloads the newest released jar:

```bash
curl -L -o slotcycler.jar https://github.com/ImGabri/slotcycler/releases/latest/download/slotcycler.jar
```

In Windows PowerShell, type `curl.exe` instead of `curl`.

---

## Project structure

```
slotcycler/
├── build.gradle.kts          # Gradle build (Fabric Loom + Kotlin)
├── gradle.properties         # Gradle configuration
├── settings.gradle.kts
├── src/
│   └── main/
│       ├── kotlin/slotcycler/SlotCyclerMod.kt     # client run logic
│       ├── kotlin/slotcycler/Settings.kt          # saved options
│       ├── kotlin/slotcycler/ConfigScreen.kt      # settings screen
│       └── resources/fabric.mod.json              # mod metadata
├── LICENSE                   # MIT
└── README.md                 # this file
```

---

## How it works

A tick-based state machine walks the slot sequence A → B → B → A (pick up, place, pick
up, place) for the configured number of cycles, sending the configured number of clicks
per tick. With **Finish in slot B** on, it does one extra move so the stack ends in B.

- **Ghost clicks** run the same clicks through the local inventory only. No slot-click
  packet is sent, so the client and server disagree about where the stack is. If a real
  copy is unavailable, the run ends by putting a single client-only copy of the item in
  whichever of the two slots is empty, so the item shows in both.
- **Real copy** captures the original stack and confirms that slot B was empty before the
  ghost clicks. In singleplayer it schedules a validated copy on the integrated server and
  syncs the resulting inventory. In multiplayer creative mode it sends slot B and the copy
  with vanilla `CreativeInventoryActionC2SPacket`; the server remains authoritative and can
  accept or reject that packet. Neither path installs or contacts a Slot Cycler server mod.
- **Keep ghost visible** redraws that copy whenever the server clears it (after a ghost
  check, or when you click it), until a real item takes the slot, you start a new run,
  or you press **Clear ghosts** in the settings screen. The copy is display-only: the
  server never has it, so it cannot be used.
- **Ghost check** takes a snapshot of what the client believes, then sends a click that
  does nothing but carries a wrong revision number. The server responds to that mismatch
  by resending the whole inventory. Anything in the snapshot that is missing afterwards
  was a ghost, and the resend also clears it.
- **Surplus drop** compares slot A with its starting count and drops the difference with
  `THROW` slot clicks, which is what `Q` does. When a ghost check is on, it waits for
  the server's answer first so it never acts on ghost counts.

---

## License

[MIT](LICENSE) — free to use, modify, and distribute.

---

Made with ❤️ by [ImGabri](https://github.com/ImGabri).
