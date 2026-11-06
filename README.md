# Stop Guard

A server-side NeoForge mod for Minecraft 1.21.1 that makes sure a dedicated server process really exits after
`/stop`.

Sometimes a server saves every level, prints "All dimensions are saved"... and the Java process stays up. The cause
is a non-daemon thread that never ends (for example a world-generation thread of a LOD mod parked forever on a
future), and one such thread keeps the whole JVM alive. A restart script or host then waits for nothing, or has to
force-kill the process.

Stop Guard starts a watchdog only after `ServerStoppedEvent`, which NeoForge posts after every level has been saved.
If the JVM is still alive `exitAfterSeconds` later (default 15), it logs the non-daemon threads holding it (name,
state, top stack frames) and calls `System.exit(0)`. If even that hangs (a stuck shutdown hook), `Runtime.halt(0)`
follows `haltAfterSeconds` later (default 30). A clean exit never sees it: the watchdog is a daemon thread and dies
with the JVM.

Dedicated servers only: a single-player (integrated) server posts the same event when a world closes, and the game
must keep running, so nothing is armed there.

Designed by DuskyTheDolphin, written with AI assistance.

## Compared with Force Stop and similar mods

Force Stop, Stray Thread Killer and No More Zombie Threads also end a server that won't exit. Stop Guard differs in
three ways: it arms only after every level has been saved, so it never cuts a save short; before exiting it logs which
threads held the process, so you can report the real culprit to that mod; and it escalates from a normal exit to a halt
only if the exit itself hangs. If one of those mods already works for you, you don't need this one.

## Install

Server only. Put the jar in the server's `mods/` folder; clients do not need it.

- Minecraft 1.21.1
- NeoForge 21.1 or newer (built and tested against 21.1.250)
- No other dependencies

Release test for 1.0.1: a dedicated NeoForge 21.1.250 server with only this mod installed; its start-up line reports
the watchdog armed.

## Config

`config/forgeward_stopguard-common.toml`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Arm the watchdog after a server stop |
| `exitAfterSeconds` | `15` | Seconds after the saves before `System.exit(0)` (5..600) |
| `haltAfterSeconds` | `30` | Further seconds before `Runtime.halt(0)` (5..600) |
| `testSpawnStuckThread` | `false` | Check it on your own server: at the next stop, start a thread that never ends, so you can watch the watchdog fire. Leave `false` in normal use |

## Log

```
[forgeward_stopguard] armed: exit after 15 s, halt after 45 s (counted from ServerStoppedEvent, after all saves)
[forgeward_stopguard] server stopped (all levels saved): watchdog started, System.exit(0) in 15 s if the JVM is still up
[forgeward_stopguard] JVM exiting normally 5 ms after the server stopped: not needed
```

When it fires, the holder threads are listed. The lines also go to stdout, because logging may already be shutting
down.

## Building

`build.ps1` compiles with `javac` against a NeoForge 21.1.250 server's `libraries/` (set `SERVER` and `JDK`).
Mod id `forgeward_stopguard`.

## License

MIT, see [LICENSE](LICENSE).
