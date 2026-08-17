# SoloMapling population configuration

All population knobs live in **`EnvironmentPopulation.yaml`**:

- Wave counts / `scale` / TrainingBot hubs
- Ambient towns under `waves.town_presence.towns` (formerly `TownPresence.yaml`)

`TownPresence.yaml` is a deprecated stub and is not loaded.

## Where the file is loaded (BeiDou host)

**Not from `BeiDou-boot.jar`.** Resolution order:

1. `solomapling.population-config` in `application.yml` (optional path override)
2. CWD file when the process runs from `gms-server/`:  
   `src/main/java/soloMapling/Environment/EnvironmentPopulation.yaml`
3. Classpath resource inside `plugins/solomapling-plugin-*.jar`:  
   `soloMapling/Environment/EnvironmentPopulation.yaml`

FS file (2) wins over the plugin jar (3), so you can tune counts without rebuilding the plugin.

Cosmic in-tree builds use the same relative path under the Cosmic working directory.
Cosmic default YAML uses full TrainingBot population; BeiDou ships a modest Wave-8 YAML.

```bash
cd /path/to/BeiDou-Server
mvn -pl solomapling-plugin -am test -Dtest=EnvironmentPopulationConfigTest -Dsurefire.failIfNoSpecifiedTests=false
```
