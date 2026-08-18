# SoloMapling population configuration

All population knobs live in **`EnvironmentPopulation.yaml`**:

- Wave counts / `scale` / TrainingBot hubs
- Ambient towns under `waves.town_presence.towns` (formerly `TownPresence.yaml`)

`TownPresence.yaml` is a deprecated stub and is not loaded.

## Where the file is loaded (BeiDou host)

**Not from `BeiDou-boot.jar`.** Resolution order:

1. `solomapling.population-config` in BeiDou `application.yml` (optional path override)
2. Classpath resource inside `plugins/solomapling-plugin-*.jar`:  
   `soloMapling/Environment/EnvironmentPopulation.yaml`

Cosmic in-tree builds use the same relative path under the Cosmic working directory.

For the BeiDou plugin build and tests, see [beidou-plugin/src/main/java/soloMapling/Environment/CONFIG.md](../../../beidou-plugin/src/main/java/soloMapling/Environment/CONFIG.md).
