# PackSync — Proof of Concept

Automatically distribute resource packs embedded in your server's datapacks, without external hosting or an additional HTTP port.

PackSync is a **Fabric mod for Minecraft 26.3**, requiring **Java 25** and **Fabric API**. Install the **same mod JAR on both the client and the server**.

> Experimental software: this proof of concept is intended for testing on trusted servers, not for public production deployments. The mod's interface, logs, and error messages are in English by default.

## Preparing your datapacks

The server scans **enabled datapacks** at startup and after each successful `/reload`. Both folder-based and ZIP-based datapacks are supported through Minecraft's pack APIs. Disabled datapacks are ignored.

```text
world/datapacks/my_datapack/
├── pack.mcmeta
├── data/
├── ressources.zip       # optional; exact spelling, with two consecutive s characters
└── ressources.json      # optional
```

The filenames **`ressources.zip`** and **`ressources.json`** remain unchanged. Do not rename them to `resources.zip` or `resources.json`.

`ressources.zip` must be a valid resource pack: its own `pack.mcmeta` and `assets/` directory must be **at the root of the ZIP**, without an enclosing folder. Its metadata must be compatible with Minecraft 26.3.

### Loading priority

Use the optional `ressources.json` file to specify the pack's priority:

```json
{ "priority": "HIGH" }
```

A plain JSON string such as `"HIGH"` is also accepted. Supported values are exactly `LOW`, `MEDIUM`, `HIGH`, and `CRITICAL`. Missing or invalid configuration defaults to `MEDIUM`; invalid configuration also produces a warning.

Priority increases in this order: **LOW < MEDIUM < HIGH < CRITICAL**. CRITICAL packs appear above LOW packs in Minecraft's resource pack list and take precedence when resources conflict. Packs with the same priority are sorted alphabetically by datapack ID; the last one takes precedence.

The mod's packs are placed above personal resource packs without saving changes to the user's personal pack selection.

## How it works

1. Immediately after joining in the play phase, the client checks the Fabric channel and requests a catalog of datapacks containing `ressources.zip`.
2. If the channel is absent, the client logs an INFO message and continues normally. If the channel is advertised but the catalog request receives no response, synchronization is skipped after **5 seconds**, with an INFO message.
3. The server supplies each pack's datapack ID, priority, size, and SHA-256 hash.
4. Packs are downloaded sequentially in **256 KiB chunks**, with one chunk in flight at a time. The PoC supports up to **128 packs**, with a maximum of **512 MiB per ZIP**.
5. A download screen displays a green progress bar, overall byte-weighted percentage, current datapack ID, completed/total pack count, downloaded/total MB, and **average MB/s since the download started**. Units are decimal: 1 MB = 1,000,000 bytes, not megabits.
6. After SHA-256 verification, the packs are activated together and Minecraft reloads its resources. The packs are deactivated on disconnect.

The connection is already established during the download: **this PoC does not delay world entry during the configuration phase**. The screen prevents normal gameplay during the transfer, but the player already exists on the server. Minecraft's standard resource reload overlay appears afterward.

If a chunk receives no response for **30 seconds**, or a download encounters invalid data or a disk error, synchronization is aborted, the partial file is removed, a warning is logged, and the connection is kept open. An empty catalog does not open a download screen.

### Live updates with `/reload`

After a successful `/reload`, the server compares the previous and new resource pack catalogs by datapack ID, ZIP hash, size, and priority. **If nothing changed, PackSync sends no synchronization packets** (normal Minecraft reload traffic is unaffected).

When resources change, the server sends a delta to every connected client that has initialized PackSync and supports the delta channel. The delta contains only added/modified pack entries and the IDs of removed packs, not the entire catalog or unchanged ZIPs.

- New or changed ZIPs are downloaded automatically with the usual progress screen.
- ZIPs already verified during the current connection are reused; unchanged packs are not downloaded again.
- Priority-only changes reorder the packs without downloading their ZIPs again.
- Removed packs are deactivated, including when the last resource pack is removed.
- Updates received during a download or a resource reload are queued. Successive updates are merged into the latest desired state, while the current transfer finishes using its original files.
- Failed datapack reloads do not publish updates. No reconnection is required for supported clients.

Both client and server must use this updated version of PackSync to support live deltas. Older clients without the delta channel still need to reconnect.

## Building

With JDK 25 installed and selected for Gradle:

```bash
./gradlew build
```

Install `build/libs/packsync-1.0-SNAPSHOT.jar`, not the `-sources.jar` file.

Run automated tests with:

```bash
./gradlew test
```

## Manual in-game test checklist

- Server without the mod: normal connection, no download screen or disconnect, and an INFO message.
- Server with the mod but no resource ZIPs: empty catalog and normal connection.
- Ten datapacks with resources: counter advances from 0/10 to 10/10, progress reaches 100%, and download speed is visible.
- Two packs overriding the same texture: CRITICAL takes precedence over LOW.
- Folder-based and ZIP-based datapacks: resources are discovered in both cases.
- Run `/reload` without changing resources: no PackSync delta or ZIP chunks are sent.
- Update a ZIP and run `/reload`: only changed/new ZIPs are downloaded, without reconnecting.
- Change only a priority: ordering updates without any ZIP download.
- Remove one or all resource packs and run `/reload`: removed packs are deactivated.
- Run several reloads during a download: the client eventually applies the latest state.
- Disconnect during a transfer: no partial `.part` file is retained, and downloaded packs are deactivated.
- ZIP without `pack.mcmeta`, invalid JSON, or a stalled transfer: appropriate warnings without an expected crash.
- Also check singleplayer, coexistence with a vanilla server resource pack, and recovery after a Minecraft resource reload failure.

## PoC limitations and security

Use this version only with **trusted servers and resource packs**. It is not yet hardened for public deployment.

- Downloading and activation are automatic, without a dedicated consent screen.
- SHA-256 verifies transfer integrity, **not the server's identity or trustworthiness**.
- The mod does not extract ZIP files. Cache filenames are derived from hashes, and chunk sizes and individual pack sizes are bounded. This does not eliminate risks from resources loaded by Minecraft, including ZIP bombs, excessive memory usage, or shaders.
- There is no global disk quota, automatic cache eviction, server request rate limiting, signing, or thorough resource validation yet.
- Client and server `packsync-cache/` directories persist on disk. Verified ZIPs are reused for live updates within a connection, but are downloaded again after reconnecting. Cache files can be removed manually **while the game/server is stopped**. Old server hashes remain available for the duration of each client session so in-flight downloads can finish across reloads.
- Server scanning/copying/hashing and client writing/hashing are synchronous and may cause pauses with large files. Production versions should move these operations to worker threads.
- The one-request-per-chunk protocol bounds memory usage but limits throughput on high-latency connections. Pipelining or a sliding window could improve this.
- The displayed download speed is an overall average. Resuming, parallel downloads, and a dedicated cancel button are not implemented; disconnecting interrupts the transfer.

Automated tests cover hashing, bounded copying, priority ordering, catalog/chunk/delta codec round trips, oversized packet lists, and delta computation/application (no changes, additions, modifications, removals, priority changes, and successive updates). They do not replace real client/server networking and graphical tests.
