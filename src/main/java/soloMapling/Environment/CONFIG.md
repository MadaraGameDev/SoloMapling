# SoloMapling population configuration

Wave counts and TrainingBot spawn hubs: `EnvironmentPopulation.yaml`
(loaded by `EnvironmentPopulationConfig`). Town ambient counts: `TownPresence.yaml`.

- Cosmic default YAML uses the full TrainingBot population (~2170 in wave 8).
- BeiDou ships a modest Wave-8 YAML (~270) in its `solomapling-plugin` module.
- Set `scale: 0.1` (etc.) for a light pilot without editing every cohort.

## Verify (BeiDou plugin tests)

```bash
cd /path/to/BeiDou-Server
mvn -pl solomapling-plugin -am test -Dtest=EnvironmentPopulationConfigTest
```

See also BeiDou `solomapling-plugin/.../Environment/CONFIG.md` for host-specific paths and `!env population`.
