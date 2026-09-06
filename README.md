# packmerge

[![build](https://github.com/SalyyS1/packmerge/actions/workflows/build.yml/badge.svg)](https://github.com/SalyyS1/packmerge/actions/workflows/build.yml)

packmerge puts several resource packs into one the way the client would, not the way `cp -r` does. Files the
game stacks are stacked — a font's providers, an atlas's sources, the rows of a `custom_model_data` table —
and files that can only have one owner are reported by name, with which pack won. Two plugin packs that both
extend `minecraft:item/paper` come out of it with all of their items instead of the second one's.

```
$ packmerge plugin-a plugin-b --out merged
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

3 error(s), 2 warning(s), 9 note(s)

Wrote 9 file(s) to merged
```

Those three errors are not caused by the merge. They were already true of running both packs together, and
they are the reason the merged pack would have looked broken in ways nobody could explain: two items on the
same custom model data value, and two glyphs on the same codepoint. The merge is the moment they become
visible, so it is the moment to print them.

## Why this exists

A server that runs two custom-item plugins has two packs, and the client can only be handed one. So somebody
copies one directory over the other, or drags both into the same zip, and the result is missing things.

The reason is that the client does not treat all files the same way. Enable two packs at once and it loads
*every* pack's copy of a font id and concatenates the providers, which is why a plugin can add glyphs to the
default font without restating vanilla's. It does the same with atlas sources. But for a model, a texture or
a blockstate there is exactly one winner: the pack higher in the list. A directory copy applies the second
rule to everything, so the lower pack's font providers, sound events and translation keys are not merged —
they are deleted.

The expensive case is the one that looks like neither. Both plugins hang their items off the same vanilla
item, so both write the same file: `models/item/paper.json` with an `overrides` list before 1.21.4, and
`items/paper.json` with a `custom_model_data` dispatch after it. Neither pack owns that file in any
meaningful sense — each contributes rows to a table — and overwriting it drops every row the other pack
contributed. Nothing errors. The items still exist in the plugin, still carry their custom model data, and
render as plain paper.

packmerge unions the rows, concatenates what stacks, and for everything else takes the later pack and says
so. It also tells you the things a union cannot fix: two packs claiming the same custom model data value on
one item, or the same codepoint in one font. Those need a renumber in a plugin's configuration, and every
item already handed out to a player needs to survive it, so they are printed one per line rather than
counted.

## Getting started

You need Java 21 or newer. Download `packmerge.jar` from the releases page. Packs have to be directories —
unzip them first.

```sh
packmerge base extra --out merged           # last pack wins where only one can
packmerge a b c --out merged --dry-run      # report, write nothing
packmerge a b --out merged --versions 1.21.4..26.2
```

The order is the order the client would apply them: the last pack given is the one that wins. Nothing that
goes in is modified — the output is a new directory.

| Option | What it does |
|---|---|
| `--out DIR` | Where the merged pack is written. Required. Refuses a directory that already has files in it unless `--force` |
| `--versions 1.21.4..26.2` | What `pack.mcmeta` should claim. By default it is worked out from what the packs themselves claim |
| `--description TEXT` | The description for the merged `pack.mcmeta`. Defaults to the first pack's |
| `--dry-run` | Report and write nothing |
| `--force` | Write into `--out` even if it is not empty |
| `--strict` | Exit non-zero on warnings as well as errors, for a CI step |

Exit codes are `0` for a clean merge, `1` when something was reported that will render wrong, and `2` when
the command itself could not proceed.

## What gets merged, and how

| File | Rule | Why |
|---|---|---|
| `font/*.json` | `providers` concatenated in pack order | The client loads every pack's copy of a font id and concatenates them, which is the whole reason a plugin's glyphs coexist with vanilla's |
| `atlases/*.json` | `sources` concatenated | The client applies sources in order; more of them is additive |
| `sounds.json` | Key union | Independent event definitions. A key two packs define differently is a warning, because one pack's sound stops existing |
| `lang/*.json` | Key union | Independent keys. A key defined twice is a note: overriding a translation is usually the point |
| `models/**.json` with `overrides` | `overrides` lists unioned, in pack order | Each pack contributes rows to one table. Ties are reported: the last matching override is the one the client picks |
| `items/**.json` on `custom_model_data` | `entries` unioned by threshold, then sorted | Same table, new format. A threshold claimed twice is reported; the later pack renders |
| `pack.mcmeta` | Rebuilt from the reconciled range | See below |
| Everything else | The later pack wins, and the report names both | Textures, shaders, blockstates, sounds, particle definitions: one owner, and you should know when there was a fight |

Identical files are not a conflict. Two plugin packs shipping the same copy of a vanilla model, or the same
`icons.png`, is normal; reporting it would bury the lines that matter. Bytes are compared, so "identical"
means identical.

Two things are deliberately *not* silently reconciled:

- **A codepoint claimed by two fonts.** Only one glyph can render there. Which one is not something this
  tool asserts — it prints the codepoint and the packs, because the fix is a plugin configuration change and
  a decision about items players already hold.
- **A custom model data value claimed by two items.** Same reasoning: one of the two items is invisible from
  the moment the packs are merged, and no arrangement of the file changes that.

`reference`, `ttf` and `unihex` font providers are passed through in order and counted, but their coverage
comes from a font or an archive at load time, so they cannot be checked for collisions. The report says that
rather than implying the check was clean.

## pack.mcmeta

The merged pack can only honestly claim the versions all of its inputs claim, so the range is the
intersection: the highest floor and the lowest stated ceiling. Packs written by plugins often state a bare
`pack_format` and no ceiling at all, so when nobody states one the newest release this tool knows is used and
a note says so. When the ranges do not overlap at all you get an error and the last pack's range, and
`--versions` is there to settle it yourself.

The file is then written to the rules the client enforces, which changed at resource pack format 64 — 1.21.8,
the last format without minor versions — and are unforgiving on both sides of that line:

- from 65 up, `min_format` and `max_format` are mandatory, must both be present, and `supported_formats` is
  *rejected*, with the client telling you to delete it;
- reaching back below 65 means also carrying `supported_formats` and `pack_format`, with
  `supported_formats` starting on exactly `min_format`'s major and ending on either `max_format`'s major or
  64, and `pack_format` inside the range and at least 15;
- a pack that states only `pack_format` cannot claim support for anything above 64 at all.

Get one wrong by hand and the pack is listed as incompatible or refuses to load, rather than degrading
politely.

## What it does not do

- **No plugin config merging.** It merges packs, not the YAML of the plugins that generated them. A
  renumbering it suggests has to be done in the plugin, then the pack regenerated.
- **No zip handling.** Unzip, merge, zip. One less thing to get wrong about entry order and timestamps.
- **No renumbering of its own.** It could reassign a colliding custom model data value or codepoint in the
  pack, and the item in the plugin's config and in every chest that holds one would still point at the old
  number. A merge tool that silently renumbers is worse than one that reports.
- **No format migration.** If one pack still uses `overrides` and another uses item model definitions, they
  are different files and both are kept — the game reads the definitions and ignores the overrides on 1.21.4
  and later. Converting the old one is
  [packmigrate](https://github.com/SalyyS1/packmigrate)'s job, and doing it first gives a better merge.
- **No pack optimisation.** [PackSquash](https://github.com/ComunidadAylas/PackSquash) does that properly;
  run it afterwards.

## Building it

```sh
./gradlew build          # tests, then the shaded jar in build/libs
```

Kotlin 2.4.10 on a Java 21 toolchain, Gradle 9.7.1, one shaded jar with Clikt and kotlinx.serialization
inside. Other people's JSON is read leniently, because real packs have trailing commas and comments in them.
What is written back goes through this project's own emitter, with one exception that matters: a number is
copied through as the literal it was, so `8` does not come back as `8.0` and a threshold you wrote as `1` is
still `1` after the merge.

The tests are two fixture packs that collide in every way the tool has a rule for — the same item table, the
same font codepoint, the same sound event, the same translation key, the same texture, and a
`pack.mcmeta` pair with different ranges — plus one file both ship byte for byte, which has to produce no
report at all.

## Credits

The `NOTICE` file lists the dependencies and the sources each rule was checked against.

The merge rules were checked against the vanilla files published by
[misode/mcmeta](https://github.com/misode/mcmeta): what a font file, an atlas, `sounds.json` and an item
model definition actually contain, and what the client's own `pack.mcmeta` looks like across the format-64
boundary. [minecraft.wiki](https://minecraft.wiki) documents the pack layout and the provider types.

Oraxen, Nexo and ItemsAdder are the packs this tool exists to combine. None of their code is here and none
of their configuration formats is read or written.

packmerge is an independent project and is not affiliated with, endorsed by or sponsored by Mojang or
Microsoft. Minecraft is a trademark of Mojang Synergies AB.

## Licence

Apache-2.0. See `LICENSE`.


