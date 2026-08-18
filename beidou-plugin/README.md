# SoloMapling ↔ BeiDou plugin

Branch: `feat/beidou-solomapling-plugin`

## Where the plugin lives

The **production SoloMapling plugin for BeiDou** is built from this directory:

| Path | Role |
|------|------|
| `beidou-plugin/` (this module) | Full `soloMapling/**` framework ported to `org.gms.*` + `SoloMaplingExtension` SPI |
| BeiDou `gms-server/plugins/` | Drop the built `solomapling-plugin-*.jar` here at deploy time |
| BeiDou `org.gms.extension.runtime` | Host `ExtensionLoader` / `BeiDouHostRuntime` |
| [maple-extension-api](https://github.com/zmzeng/maple-extension-api) | Host-neutral SPI (`dev.maple.extension:extension-api`) |
| `src/main/java/soloMapling/` (repo root) | Cosmic in-tree copy (`client.*` / `net.*` imports) — separate from this plugin tree |

When updating framework behavior, apply changes to the Cosmic tree first, then port the same logic into `beidou-plugin/src/main/java/soloMapling/` (BeiDou imports).

## How plugin + host cooperate

1. BeiDou implements `HostRuntime` and discovers `ServerExtension` jars via `ServiceLoader` under `plugins/`.
2. `SoloMaplingExtension` registers GM commands and subscribes to `ServerReadyEvent`.
3. On `onServerReady`, it initializes the headless `BotClient`, then (if configured) runs `EnvironmentManager` waves to populate the world.
4. Bots are real BeiDou `Character` objects; map/trade/merchant hooks in gms-server remain required.

## Build & run

```bash
# 0. Neutral SPI
cd /path/to/maple-extension-api && mvn install

# 1. Install BeiDou host artifacts (thin gms-server jar)
cd /path/to/BeiDou-Server
mvn -pl gms-server -am install -DskipTests
mvn -pl gms-server package -DskipTests

# 2. Build plugin (this module)
cd /path/to/SoloMapling/beidou-plugin
mvn package -DskipTests
cp target/solomapling-plugin-*-SNAPSHOT.jar /path/to/BeiDou-Server/gms-server/plugins/

# 3. Run BeiDou (cwd must be gms-server)
cd /path/to/BeiDou-Server/gms-server
java -Xmx4g -Dspring.config.location=src/main/resources/application.yml \
  -jar target/BeiDou-boot.jar
```

Runnable BeiDou artifact is **`BeiDou-boot.jar`**. Thin `BeiDou.jar` is the compile-time dependency for this module.

## Runtime config (BeiDou `application.yml`)

```yaml
solomapling:
  plugins-enabled: true
  plugins-dir: plugins
  spawn-bots-on-startup: true   # EnvironmentManager waves on ServerReady
  # population-config: /optional/path/to/EnvironmentPopulation.yaml
```

## In-game (GM ≥ 4)

Commands registered via HostCommandRegistry: `!env`, `!bot`, `!move`, `!fmbot`, `!gcmove`, `!smping`.

Bot clone template is the character named **`fmbot`** (Flyway `V1.9.3` on BeiDou).

## Population configuration

Bot wave counts and TrainingBot spawn hubs are YAML-driven via
`EnvironmentPopulation.yaml` (see `src/main/java/soloMapling/Environment/CONFIG.md` in this module).

| Knob | Where |
|------|--------|
| Wave toggles / FM / Henesys / merchant counts / `scale` | `EnvironmentPopulation.yaml` |
| TrainingBot hub map + count + level band | `waves.training.cohorts` |
| Town ambient SocialBot / wanderers | `waves.town_presence.towns` (same file; was `TownPresence.yaml`) |
| Master on/off at boot | BeiDou `application.yml` → `solomapling.spawn-bots-on-startup` |

In-game: `!env population show|reload` (GM ≥ 4).

### Where the YAML lives at runtime

Load order:

1. `solomapling.population-config` (optional path in BeiDou `application.yml`)
2. Classpath inside `plugins/solomapling-plugin-*.jar` (bundled default)
3. External file override wins when `population-config` is set

## Tests

```bash
mvn test -Dtest=EnvironmentPopulationConfigTest -Dsurefire.failIfNoSpecifiedTests=false
```

See also the root [README.md](../README.md) section **Host Integration: Cosmic vs BeiDou**.
