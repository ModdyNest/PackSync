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
├── resources.zip       # optional
└── resources.json      # optional
```

`resources.zip` must be a valid resource pack: its own `pack.mcmeta` and `assets/` directory must be **at the root of the ZIP**, without an enclosing folder. Its metadata must be compatible with Minecraft 26.3.

### Loading priority

Use the optional `resources.json` file to specify the pack's priority:

```json
{ "priority": "HIGH" }
```

A plain JSON string such as `"HIGH"` is also accepted. Supported values are exactly `LOW`, `MEDIUM`, `HIGH`, and `CRITICAL`. Missing or invalid configuration defaults to `MEDIUM`; invalid configuration also produces a warning.

Priority increases in this order: **LOW < MEDIUM < HIGH < CRITICAL**. CRITICAL packs appear above LOW packs in Minecraft's resource pack list and take precedence when resources conflict. Packs with the same priority are sorted alphabetically by datapack ID; the last one takes precedence.

The mod's packs are placed above personal resource packs without saving changes to the user's personal pack selection.

## How it works

1. During the **configuration phase**, the server adds a PackSync task only for compatible clients and sends a catalog of datapacks containing `resources.zip`. Clients without PackSync are fully accepted without this task.
2. For a nonempty catalog, the client asks permission with **Install**, **Disconnect**, and a **Remember for this server** checkbox. No ZIP is requested before permission is granted. An empty catalog proceeds immediately without a prompt. Servers without PackSync are skipped with an INFO message.
3. The server supplies each pack's datapack ID, priority, size, and SHA-256 hash.
4. Packs are downloaded sequentially in **256 KiB chunks**, with one chunk in flight at a time. The PoC supports up to **128 packs**, with a maximum of **512 MiB per ZIP**.
5. A download screen displays a green progress bar, overall byte-weighted percentage, current datapack ID, completed/total pack count, downloaded/total MB, and **average MB/s since the download started**. Units are decimal: 1 MB = 1,000,000 bytes, not megabits.
6. After SHA-256 verification, the packs are activated together and Minecraft reloads its resources. Only after the reload future completes does the client acknowledge the configuration task, allowing world entry. The packs are deactivated on disconnect.

With this version installed on both sides, **initial consent, download, and resource loading happen before the player enters the world**. Keep-alive traffic continues while the permission prompt is open. Remembered installation permission is stored by server address in `config/packsync-permissions.json`; delete an entry (or the file) while the game is stopped to revoke it. This permission also covers live pack updates during the connection. Remembering is opt-in and saved only when **Install** is selected; it is not proof of the server's identity. Connections without a stable server address are not remembered.

If a chunk receives no response for **30 seconds**, or a download encounters invalid data or a disk error, the partial file is removed and a warning is logged. During initial configuration, the client disconnects instead of entering the world without the requested resources. During live updates, the connection is kept open. The permission prompt itself has no download timeout. For compatibility with older PackSync servers, a play-phase fallback remains: those servers cannot provide the before-world-entry guarantee, and an unanswered catalog request is skipped after **5 seconds**.

### Live updates with `/reload`

After a successful `/reload`, the server compares the previous and new resource pack catalogs by datapack ID, ZIP hash, size, and priority. **If nothing changed, PackSync sends no synchronization packets** (normal Minecraft reload traffic is unaffected).

When resources change, the server sends a delta to every connected client that has initialized PackSync and supports the delta channel. The delta contains only added/modified pack entries and the IDs of removed packs, not the entire catalog or unchanged ZIPs.

- New or changed ZIPs are downloaded automatically with the usual progress screen.
- Live updates stay in the play phase; players already in the world are not moved back into configuration. If the server initially had no packs, the first nonempty update asks permission before downloading.
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
- Client without the mod: normal connection to a PackSync server, without an installation task.
- First connection with packs: prompt appears before world entry; Install downloads and loads before spawning; Disconnect exits without requesting ZIP chunks.
- Leave the prompt open for more than 30 seconds: it remains usable without a download timeout.
- Remember + Install: reconnect to the same address without another prompt; a different address still prompts. Without Remember, reconnecting prompts again.
- Invalid or stalled download during configuration: disconnect without entering the world.
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

- Consent is address-based and covers later updates in that connection. Remembered permissions trust future packs served at the same address; there is no publisher authentication or per-update confirmation after acceptance.
- SHA-256 verifies transfer integrity, **not the server's identity or trustworthiness**.
- The mod does not extract ZIP files. Cache filenames are derived from hashes, and chunk sizes and individual pack sizes are bounded. This does not eliminate risks from resources loaded by Minecraft, including ZIP bombs, excessive memory usage, or shaders.
- There is no global disk quota, automatic cache eviction, server request rate limiting, signing, or thorough resource validation yet.
- Client and server `packsync-cache/` directories persist on disk. Verified ZIPs are reused for live updates within a connection, but are downloaded again after reconnecting. Cache files can be removed manually **while the game/server is stopped**. Old server hashes remain available for the duration of each client session so in-flight downloads can finish across reloads.
- Server scanning/copying/hashing and client writing/hashing are synchronous and may cause pauses with large files. Production versions should move these operations to worker threads.
- The one-request-per-chunk protocol bounds memory usage but limits throughput on high-latency connections. Pipelining or a sliding window could improve this.
- The displayed download speed is an overall average. Resuming, parallel downloads, and a dedicated cancel button are not implemented; disconnecting interrupts the transfer.

Automated tests cover hashing, bounded copying, priority ordering, catalog/chunk/delta codec round trips, oversized packet lists, and delta computation/application (no changes, additions, modifications, removals, priority changes, and successive updates). They do not replace real client/server networking and graphical tests.
