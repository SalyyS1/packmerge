# packmerge

[![build](https://github.com/SalyyS1/packmerge/actions/workflows/build.yml/badge.svg)](https://github.com/SalyyS1/packmerge/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/SalyyS1/packmerge?style=flat-square)](https://github.com/SalyyS1/packmerge/releases)
[![license](https://img.shields.io/github/license/SalyyS1/packmerge?style=flat-square)](LICENSE)

packmerge puts several Minecraft resource packs (directories or `.zip` archives) into one the way the client would, not the way `cp -r` does. Files the game stacks are stacked (a font's providers, an atlas's sources, the rows of a `custom_model_data` table), and files that can only have one owner are reported by name along with which pack won. Two plugin packs that both extend `minecraft:item/paper` come out with all of their items instead of only the second pack's.

```text
$ packmerge plugin-a.zip plugin-b.zip --out merged --zip server-pack.zip
== plugin-a + plugin-b -> merged
   plugin-a                     9 file(s)
   plugin-b                     9 file(s)

   9 file(s), pack.mcmeta claiming 46..88
   1 item model definition(s) unioned
   1 legacy model(s) with their overrides unioned
   1 font(s) with their providers concatenated
   1 sounds.json merged
   1 translation file(s) merged
   1 atlas file(s) with their sources concatenated
   1 single-owner file(s) where a later pack won

   error   assets/minecraft/font/default.json
           U+F010 is claimed by plugin-a and plugin-b. Only one glyph can render there; the other is silently unreachable.
   error   assets/minecraft/items/bow.json
           custom_model_data 1 is claimed by both plugin-a and plugin-b. Only plugin-b's model renders; the other item is invisible.
   error   assets/minecraft/models/item/paper.json
           custom_model_data=2 is claimed by plugin-a (a:item/note) and plugin-b (b:item/map). Only b:item/map renders, because the last matching override wins; the other item is invisible.
   warning assets/minecraft/sounds.json
           1 key(s) are defined differently by more than one pack; the later pack's value is the one kept: shared.hit (plugin-a -> plugin-b)
   warning assets/minecraft/textures/item/paper.png
           2 packs ship this file with different content and it has one owner; plugin-b's copy is the one written.
   note    pack.mcmeta
           plugin-a claims from format 34 but plugin-b needs 46, so the merged pack starts at 46. plugin-a on its own still worked below that.

3 error(s), 2 warning(s), 1 note(s)

Wrote 9 file(s) to merged
Zipped 9 file(s) to server-pack.zip (2418 bytes)
resource-pack-sha1=9f4a8b...
```

## Why this exists

A server that runs multiple custom-item or HUD plugins produces multiple resource packs, while `server.properties` can hand the client only one URL (or a fixed stack where unmerged `models/item/*.json` and `items/*.json` overwrite each other). Copying one directory over another applies single-owner semantics to everything: the lower pack's font providers, sound events, translation keys, and `custom_model_data` rows are silently deleted.

packmerge unions item tables, concatenates additive arrays, reconciles dual-era `pack.mcmeta` ranges, emits byte-deterministic `.zip` archives with ready-to-paste `resource-pack-sha1` digests, and surfaces real codepoint or `custom_model_data` collisions as explicit errors.

## Getting started

Requires Java 21 or newer. Download `packmerge.jar` from [**Releases**](https://github.com/SalyyS1/packmerge/releases). Inputs can be unpacked directories, `.zip` archives, or a mix of both:

```sh
packmerge oraxen.zip modelengine.zip hud-pack/ --zip server-pack.zip
packmerge base extra --out merged --zip server-pack.zip
packmerge a.zip b.zip --dry-run --json > merge-report.json
packmerge a.zip b.zip --zip server-pack.zip --versions 1.21.4..26.2 --strict
```

| Option | Description |
|---|---|
| `--out DIR` | Directory to write the merged unpacked files into (refuses non-empty directories unless `--force`) |
| `--zip FILE` | Write a byte-reproducible `.zip` archive (sorted entries, fixed epoch timestamp) and print `resource-pack-sha1=<sha1>` |
| `--versions 1.21.4..26.2` | Explicit `pack.mcmeta` version range (defaults to the intersection of the input packs' ranges) |
| `--description TEXT` | Description for the merged `pack.mcmeta` (defaults to the first pack's description) |
| `--dry-run` | Compute the full merge plan and collision report without writing files |
| `--json` | Emit machine-readable JSON report (`zipSha1`, `zipSha256`, `combinedCounts`, `issues`) to stdout |
| `--force` | Overwrite an existing `--out` directory or `--zip` file |
| `--strict` | Exit `1` on warnings as well as errors in CI pipelines |

Exit codes: `0` for a clean merge, `1` when collisions or `--strict` warnings are found, and `2` on invalid CLI arguments or unreadable archives.

### GitHub Action

```yaml
- uses: SalyyS1/packmerge@v0.2.0
  with:
    args: packs/oraxen.zip packs/modelengine.zip packs/ui.zip --zip dist/server-pack.zip --strict
```

## Merge rules

| File pattern | Merge behavior | Rationale |
|---|---|---|
| `font/*.json` | `providers` concatenated in pack order | Vanilla loads every pack's copy of a font ID and concatenates providers; duplicate codepoints across packs are reported as errors |
| `atlases/*.json` | `sources` concatenated in pack order | Vanilla applies atlas sources additively |
| `sounds.json` | Key union | Independent sound events merge cleanly; conflicting definitions warn and keep the later pack |
| `lang/*.json` | Key union | Translation keys merge cleanly; overrides are recorded as notes |
| `models/**.json` with `overrides` | `overrides` lists unioned in pack order | Legacy `custom_model_data` rows combine into one table; duplicate predicates are reported as errors |
| `items/**.json` on `custom_model_data` | `entries` unioned by threshold and sorted | 1.21.4+ item model dispatch tables combine cleanly; duplicate thresholds are reported as errors |
| `pack.mcmeta` | Reconciled intersection range | Emits consistent dual-era fields (`pack_format`, `supported_formats`, `min_format`, `max_format`) across format 64/65 |
| Single-owner assets | Later pack wins with warning | Textures, shaders, blockstates, and OGG audio files warn when two packs ship different bytes; byte-identical copies are silent |

## Building from source

```sh
./gradlew build --no-daemon    # runs JUnit 5 suite and builds build/libs/packmerge.jar
```

## Related Minecraft Engineering Tools by Salyvn

- [**glyphsmith**](https://github.com/SalyyS1/glyphsmith): Deterministic bitmap font glyph solver, lockfile compiler, and resource-pack font auditor.
- [**packmigrate**](https://github.com/SalyyS1/packmigrate): Automated resource pack migrator across Minecraft format versions and 1.21.4+ item definitions.
- [**shadersmith**](https://github.com/SalyyS1/shadersmith): Vanilla core-shader and post-effect pipeline validator and builder for Minecraft resource packs.
- [**foliascan**](https://github.com/SalyyS1/foliascan): Static bytecode analyzer with SARIF call-graph taint flows for Paper and Folia server plugins.
- [**pluginscope**](https://github.com/SalyyS1/pluginscope): Plugin JAR dependency, shadow-relocation, and Paper/Spigot compatibility auditor.
- [**serverdoctor**](https://github.com/SalyyS1/serverdoctor): Live config, JVM flag, and crash/timings diagnostic CLI for Minecraft servers.
- [**mcstage**](https://github.com/SalyyS1/mcstage): Reproducible staging environment builder for Paper, Folia, and Velocity server networks.
- [**minecraftkit**](https://github.com/SalyyS1/minecraftkit): MCP server and engineering knowledge kit for Minecraft plugin and pack development.

## Licence

Independent project; not affiliated with Mojang or Microsoft. Licensed under [Apache-2.0](LICENSE).
