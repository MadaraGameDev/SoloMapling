# SoloMapling population configuration

All population knobs live in **`EnvironmentPopulation.yaml`**:

- Wave counts / `scale` / TrainingBot hubs
- Ambient towns under `waves.town_presence.towns` (formerly `TownPresence.yaml`)

`TownPresence.yaml` is a deprecated stub and is not loaded.

Cosmic default YAML uses full TrainingBot population; BeiDou ships a modest Wave-8 YAML.

```bash
cd /path/to/BeiDou-Server
mvn -pl solomapling-plugin -am test -Dtest=EnvironmentPopulationConfigTest -Dsurefire.failIfNoSpecifiedTests=false
```
