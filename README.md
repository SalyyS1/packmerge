# packmerge

[![build](https://github.com/SalyyS1/packmerge/actions/workflows/build.yml/badge.svg)](https://github.com/SalyyS1/packmerge/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/SalyyS1/packmerge?style=flat-square)](https://github.com/SalyyS1/packmerge/releases)
[![web matrix](https://img.shields.io/badge/web_tool-conflict_matrix-38bdf8?style=flat-square)](https://salyys1.github.io/packmerge/)
[![license](https://img.shields.io/github/license/SalyyS1/packmerge?style=flat-square)](LICENSE)

packmerge puts several Minecraft resource packs (directories or `.zip` archives) into one the way the client would, not the way `cp -r` does. Files the game stacks are stacked (a font's providers, an atlas's sources, the rows of a `custom_model_data` dispatch table, and assets inside declared `overlays.entries` directories), and files that can only have one owner are reported by name along with which pack won.

Try the zero-upload [**Browser Multi-Pack Conflict Matrix & Merger**](https://salyys1.github.io/packmerge/) to inspect collisions, run 64-bit 2D DCT perceptual image hash (`pHash`) and dual-era overlay deduplication, prune shadowed model override trees, simulate cross-namespace texture atlas packing, audit Minecraft `26.3` sound event atlases and OGG Vorbis audio streams, export OASIS SARIF v2.1.0 diagnostics, and compile deterministic SHA-1 `.zip` packs directly in your browser.

```text
$ packmerge plugin-a.zip plugin-b.zip --out merged --zip server-pack.zip --sarif packmerge.sarif
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

   error   [PM001] assets/minecraft/font/default.json
           U+F010 is claimed by plugin-a and plugin-b. Only one glyph can render there; the other is silently unreachable.
   error   [PM003] assets/minecraft/items/bow.json
           custom_model_data 1 is claimed by both plugin-a and plugin-b. Only plugin-b's model renders; the other item is invisible.
   error   [PM002] assets/minecraft/models/item/paper.json
           custom_model_data=2 is claimed by plugin-a (a:item/note) and plugin-b (b:item/map). Only b:item/map renders, because the last matching override wins; the other item is invisible.
   warning [PM007] assets/minecraft/sounds.json
           1 key(s) are defined differently by more than one pack; the later pack's value is the one kept: shared.hit (plugin-a -> plugin-b)
   warning [PM006] assets/minecraft/textures/item/paper.png
           2 packs ship this file with different content and it has one owner; plugin-b's copy is the one written.
   note    [PM012] pack.mcmeta
           plugin-a claims from format 34 but plugin-b needs 46, so the merged pack starts at 46. plugin-a on its own still worked below that.

3 error(s), 2 warning(s), 6 note(s)

Wrote 9 file(s) to merged
Zipped 9 file(s) to server-pack.zip (2418 bytes)
resource-pack-sha1=9f4a8b...
Wrote SARIF v2.1.0 report to packmerge.sarif
```

## Why this exists

A server that runs multiple custom-item or HUD plugins produces multiple resource packs, while `server.properties` can hand the client only one URL (or a fixed stack where unmerged `models/item/*.json` and `items/*.json` overwrite each other). Copying one directory over another applies single-owner semantics to everything: the lower pack's font providers, sound events, translation keys, `overlays.entries` manifests, and `custom_model_data` rows are silently deleted.

packmerge unions 1.21.4 to 26.3 item dispatch tables (`range_dispatch` floats, `select` strings with codec-safe duplicate `when` pruning, and hybrid `select` -> `range_dispatch` fallback chaining), prunes shadowed legacy overrides and redundant 26.3 dispatch knots (`--prune-overrides`), deduplicates dual-era overlay files and re-encoded PNG textures with 64-bit 2D DCT perceptual image hashing (`--dedupe-overlays`, `--phash`), simulates texture atlas `single`/`sprite` packing to detect cross-namespace sprite collisions and missing sprite files (`--audit-atlas`), validates Minecraft `26.3` `sounds.json` atlases and OGG Vorbis I container codecs/sample rates (`--audit-sounds`, `--audit-audio`), reconciles `[major, minor]` `pack.mcmeta` ranges through format `97`, emits byte-deterministic `.zip` archives with `resource-pack-sha1` digests, and exports OASIS SARIF v2.1.0 reports (`PM001`..`PM022`).

## Getting started

Requires Java 21 or newer. Download `packmerge.jar` from [**Releases**](https://github.com/SalyyS1/packmerge/releases). Inputs can be unpacked directories, `.zip` archives, or a mix of both:

```sh
packmerge oraxen.zip nexo.zip hud-pack/ --zip server-pack.zip
packmerge oraxen.zip nexo.zip --zip server-pack.zip --dedupe-overlays --phash --prune-overrides --audit-sounds --audit-atlas --audit-audio
packmerge base extra --out merged --zip server-pack.zip
packmerge a.zip b.zip --sarif packmerge.sarif
packmerge a.zip b.zip --dry-run --json > merge-report.json
packmerge a.zip b.zip --zip server-pack.zip --versions 1.21.4..26.3 --strict
```

| Option | Description |
|---|---|
| `--out DIR` | Directory to write the merged unpacked files into (refuses non-empty directories unless `--force`) |
| `--zip FILE` | Write a byte-reproducible `.zip` archive (sorted entries, fixed epoch timestamp) and print `resource-pack-sha1=<sha1>` |
| `--sarif FILE` | Write an OASIS SARIF v2.1.0 diagnostic report (`PM001`..`PM022`) for GitHub Code Scanning |
| `--versions 1.21.4..26.3` | Explicit `pack.mcmeta` version or `[major, minor]` format range (defaults to the intersection of the input packs' ranges) |
| `--description TEXT` | Description for the merged `pack.mcmeta` (defaults to the first pack's description) |
| `--dedupe-overlays` | Prune redundant assets inside `overlays.entries` directories that match underlying base or lower-overlay files across their format range (`PM014`) |
| `--phash` | Run 64-bit 2D DCT perceptual image hash (`pHash`) and decoded RGBA pixel deduplication across PNG textures (`PM013`) |
| `--phash-threshold INT` | Maximum 64-bit `pHash` Hamming distance (`0..64`, default `4`) flagged as a near-duplicate texture |
| `--prune-overrides` | Prune shadowed legacy `overrides` predicates, redundant `range_dispatch` step knots, fallback-equivalent `select` cases, and tautological `condition` branches (`PM015`) |
| `--audit-sounds` | Verify `sounds.json` `.ogg` audio file existence and custom `type=event` targets across root `assets/` and `26.3` overlays (`PM016`..`PM018`) |
| `--audit-atlas` | Simulate texture atlas packing and verify `single` sprite source textures exist across merged root and overlay directories (`PM019`..`PM020`) |
| `--audit-audio` | Inspect `.ogg` BOS bitstream headers under `sounds/` for non-Vorbis codecs (`Opus`, `FLAC`, `Speex`) and out-of-range Vorbis I sample rates or channel counts (`PM021`..`PM022`) |
| `--dry-run` | Compute the full merge plan and collision report without writing files |
| `--json` | Emit machine-readable JSON report (`zipSha1`, `zipSha256`, `overlays`, `dedupedOverlayFiles`, `prunedOverrides`, `perceptualDuplicates`, `issues`) to stdout |
| `--force` | Overwrite an existing `--out` directory or `--zip` file |
| `--strict` | Exit `1` on warnings as well as errors in CI pipelines |

Exit codes: `0` for a clean merge, `1` when collisions or `--strict` warnings are found, and `2` on invalid CLI arguments or unreadable archives.

### GitHub Action & Code Scanning

```yaml
- uses: SalyyS1/packmerge@v0.5.0
  continue-on-error: true
  with:
    args: packs/oraxen.zip packs/nexo.zip packs/ui.zip --zip dist/server-pack.zip --dedupe-overlays --phash --prune-overrides --audit-sounds --audit-atlas --audit-audio --strict
    sarif: packmerge.sarif

- uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: packmerge.sarif
```

## Merge & diagnostic rules

| File / Scope | Merge & optimization behavior | Diagnostic codes |
|---|---|---|
| `font/*.json` | `providers` concatenated in pack order | `PM001` codepoint collision, `PM012` runtime coverage note |
| `items/**.json` (`range_dispatch` / `select` / `condition`) | Float thresholds unioned, `select` string cases unioned & deduplicated, mixed `select` + `range_dispatch` chained via `fallback`, `oversized_in_gui` reconciled, step knots & tautologies pruned (`--prune-overrides`) | `PM003` threshold/case collision, `PM004` unsupported root, `PM008` property conflict, `PM015` dispatch branch pruned |
| `models/**.json` with `overrides` | `overrides` lists unioned in pack order; dominated/shadowed predicates & no-op knots pruned (`--prune-overrides`) | `PM002` predicate collision, `PM008` base model conflict, `PM015` shadowed override pruned |
| `sounds.json` & `sounds/**.ogg` | Key union across packs + Minecraft `26.3` schema validation, `.ogg` extension guard, `type=event` cycle DFS, `replace=true` wipe check (`--audit-sounds`), and OGG container codec & Vorbis I header audit (`--audit-audio`) | `PM007` conflicting sound event overwrite, `PM016` invalid sound schema, `PM017` event reference cycle, `PM018` `replace=true` wipe or missing `.ogg`, `PM021` non-Vorbis OGG codec, `PM022` Vorbis sample-rate/channel warning |
| `lang/*.json` | Key union across packs | `PM011` additive summary |
| `atlases/*.json` | `sources` concatenated in pack order + cross-namespace sprite ID conflict resolver and missing `single` sprite texture check (`--audit-atlas`) | `PM004` missing array, `PM011` additive summary, `PM019` cross-namespace sprite conflict, `PM020` missing sprite texture |
| `pack.mcmeta` & `<overlay>/assets/**` | Minor-aware intersection across `1.20`..`26.3` (`97` / `[97, 1]`), `overlays.entries` preserved, redundant overlay files pruned with format-coverage verification (`--dedupe-overlays`) | `PM005` disjoint pack formats, `PM009` invalid overlay dir, `PM010` disjoint overlay bounds, `PM014` redundant overlay file pruned |
| Single-owner assets & PNG textures | Later pack wins when digests differ; `--phash` coalesces re-encoded RGBA-identical PNGs to smallest encoding and detects cross-namespace near-duplicates via 64-bit 2D DCT `pHash` | `PM006` single-owner overwrite warning, `PM013` perceptual PNG duplicate |

## Building from source

```sh
./gradlew build --no-daemon    # runs JUnit 5 suite and builds build/libs/packmerge.jar
```

## Related Minecraft Engineering Tools by Salyvn

- [**glyphsmith**](https://github.com/SalyyS1/glyphsmith): Deterministic bitmap & TrueType font glyph solver, NAF shift optimizer, and [browser font auditor](https://salyys1.github.io/glyphsmith/).
- [**packmigrate**](https://github.com/SalyyS1/packmigrate): Automated resource pack migrator across Minecraft format versions and 1.21.4+ item definitions with [browser migrator](https://salyys1.github.io/packmigrate/).
- [**shadersmith**](https://github.com/SalyyS1/shadersmith): Vanilla core-shader, std430 SSBO layout, and post-effect pipeline validator with [browser GLSL inspector](https://salyys1.github.io/shadersmith/).
- [**foliascan**](https://github.com/SalyyS1/foliascan): Static bytecode analyzer with SARIF call-graph taint flows and [browser JAR scanner](https://salyys1.github.io/foliascan/).
- [**pluginscope**](https://github.com/SalyyS1/pluginscope): Plugin JAR dependency, cross-plugin ASM dataflow leak, and Paper/Spigot [browser compatibility auditor](https://salyys1.github.io/pluginscope/).
- [**serverdoctor**](https://github.com/SalyyS1/serverdoctor): Offline Spark flamegraph, Folia stall, JVM flag, and Paper 26.3 diagnostic CLI and [browser log analyzer](https://salyys1.github.io/serverdoctor/).
- [**mcstage**](https://github.com/SalyyS1/mcstage): Reproducible staging environment and integration test runner for Paper, Folia, and Purpur server networks.
- [**minecraftkit**](https://github.com/SalyyS1/minecraftkit): MCP server and engineering knowledge kit for Minecraft plugin and pack development.

## Licence

Independent project; not affiliated with Mojang or Microsoft. Licensed under [Apache-2.0](LICENSE).
