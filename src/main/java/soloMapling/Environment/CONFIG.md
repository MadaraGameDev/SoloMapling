# SoloMapling population configuration

One file drives world population:

**`EnvironmentPopulation.yaml`**

| Section | Role |
|---------|------|
| `scale` | Multiplies Henesys / FM / merchant / training counts |
| `waves.*` | Wave toggles and batch sizes |
| `waves.training.cohorts` | TrainingBot spawn hubs + level bands |
| `waves.town_presence.towns` | Ambient SocialBot / wanderer counts (was `TownPresence.yaml`) |

`TownPresence.yaml` is deprecated and no longer read (stub only).

## Where to put the file at runtime

**Do not put it in the host boot jar.** Resolution order:

1. Optional override: host config → `solomapling.population-config: <path>`
2. Classpath inside **`plugins/solomapling-plugin-*.jar`**:  
   `soloMapling/Environment/EnvironmentPopulation.yaml` (bundled default)

To override without rebuilding the plugin, set `solomapling.population-config` to an external YAML path.

## Live commands (GM ≥ 4)

```
!env population show|reload   # whole file (waves + towns)
!env townpresence reload      # same file; refreshes town plan + social map scope
```

`reload` re-reads YAML but does not despawn bots already online — restart (or a full environment load) to apply new counts.

## Verify

```bash
cd /path/to/SoloMapling
mvn test -Dtest=EnvironmentPopulationConfigTest -Dsurefire.failIfNoSpecifiedTests=false
```
