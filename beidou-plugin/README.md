# SoloMapling ↔ BeiDou plugin

Branch: `feat/beidou-solomapling-plugin`

## Where the real plugin lives

The **buildable, full SoloMapling plugin** for BeiDou is hosted in the BeiDou repo:

| Path | Role |
|------|------|
| **BeiDou** `solomapling-plugin/` | Full `soloMapling/**` framework + `SoloMaplingExtension` SPI |
| **BeiDou** `gms-server/plugins/` | Drop the built `solomapling-plugin-*.jar` here |
| **BeiDou** `gms-server/src/main/java/soloMapling/` | Host helpers + runtime FS resources (yaml/csv/bin) opened by relative path |
| **BeiDou** `extension-api` + `org.gms.extension.runtime` | Host SPI / `ExtensionLoader` |
| This dir (`SoloMapling/beidou-plugin/`) | Thin SPI stub / documentation pointer — **not** the production plugin jar |

## How plugin + host cooperate

1. BeiDou implements `HostRuntime` and discovers `ServerExtension` jars via `ServiceLoader` under `plugins/`.
2. `SoloMaplingExtension` registers GM commands and subscribes to `ServerReadyEvent`.
3. On `onServerReady`, it initializes the headless `BotClient`, then (if configured) runs `EnvironmentManager` waves to populate the world.
4. Bots are real BeiDou `Character` objects; map/trade/merchant hooks in gms-server remain required.

Upstream SoloMapling framework sources stay in this repo under `src/main/java/soloMapling/`. When shipping a BeiDou plugin build, sync those sources into BeiDou `solomapling-plugin/` (package imports already use `org.gms.*` on the BeiDou side).

## Build & run (BeiDou)

```bash
cd /path/to/BeiDou-Server
mvn -pl extension-api,gms-server,solomapling-plugin -am package -DskipTests
cp solomapling-plugin/target/solomapling-plugin-*-SNAPSHOT.jar gms-server/plugins/

cd gms-server
java -Xmx4g -Dspring.config.location=src/main/resources/application.yml \
  -jar target/BeiDou-boot.jar
```

Main artifact `BeiDou.jar` is the **thin** jar (for plugin compile). Runnable fat jar is **`BeiDou-boot.jar`**.

## Runtime config (`gms-server/.../application.yml`)

```yaml
solomapling:
  plugins-enabled: true
  plugins-dir: plugins
  spawn-bots-on-startup: true   # EnvironmentManager waves on ServerReady
```

## In-game (GM ≥ 4)

Commands registered via HostCommandRegistry: `!env`, `!bot`, `!move`, `!fmbot`, `!gcmove`, `!smping`.

Bot clone template is the character named **`fmbot`** (Flyway `V1.9.3` on BeiDou).

## Status

- Full bot world population works on BeiDou when the plugin jar is present and spawn-on-startup is enabled.
- Keep SoloMapling upstream sources in `SoloMapling/src/main/java/soloMapling`; port/sync into BeiDou `solomapling-plugin` when updating the framework.
- This `beidou-plugin/` tree is intentionally a stub so the SoloMapling branch documents the BeiDou host integration without shipping a second incomplete jar.

See also the root [README.md](../README.md) section **Host Integration: Cosmic vs BeiDou**.
