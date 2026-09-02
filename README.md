# habits

A habit tracker that renders as a GitHub-style activity grid, for a wall-mounted
kiosk. Ktor + kotlinx.html + SQLite, packaged as a flake.

## Model

One table of habits, one of completions. A completion is `(habit, day, value)`.

`target` is what a full-intensity square means. A binary habit has `target = 1`,
so it is on or off. A counter has `target = n` and the square shades by
`value / target`. Streaks count any day with `value >= 1`; `target` only affects
colour. Nothing derived is stored — no streak columns, no totals — so backfilling
a forgotten day is just another insert.

## Layout

`src/main/kotlin/rip/tek/habits/`

- `Db.kt` — schema, seed data, queries. One guarded connection; sqlite-jdbc is
  not thread-safe and Netty is multithreaded.
- `Main.kt` — Ktor server. Routing and rendering live here.

## Develop

```sh
nix develop            # gradle + jdk21 + kotlin-language-server
gradle installDist     # build
./build/install/habits/bin/habits
```

Or through Nix, exactly as it will be built on the server:

```sh
nix build
./result/bin/habits
```

`HABITS_PORT` (default 8095) and `HABITS_DB` (default `habits.db` in the working
directory) configure it.

## Dependencies

After changing `dependencies` in `build.gradle.kts`, run `./update-deps.sh`.
The Nix build has no network, so it replays `deps.json` through a MITM cache;
an unlisted artifact is a build failure, not a silent fetch.

## Deploying

The flake exposes `nixosModules.default`:

```nix
services.habits = {
  enable = true;
  port = 8095;
};
```

State lives in `/var/lib/habits`. That SQLite file is the only thing here that
cannot be rebuilt from source — back it up.

## Labels

Habits are seeded with icons only — no readable names, because this repo is
public and the display is meant to be legible only to its owner. The `name`
column exists but is seeded with the slug. To set real labels, do it in the
database on the server, where they stay private:

```sh
sqlite3 /var/lib/habits/habits.db \
  "update habits set name = 'gym' where slug = 'gym';"
```

## Icons

`src/main/resources/icons.woff2` is Material Symbols Outlined, subset to the
eight glyphs this app uses — 11 MB down to under 7 KB. It is vendored rather
than fetched so the page needs no external request, and served same-origin at
`/icons.woff2`.

Glyphs are addressed by codepoint, not by ligature name, which is what allows
the subset to drop its layout tables. To change an icon you need both a new
codepoint in `GLYPHS` and a re-subset:

```sh
pyftsubset "MaterialSymbolsOutlined[FILL,GRAD,opsz,wght].ttf" \
  --unicodes=f08c,e51c,e518,e86b,eb39,ead5,f525,f097 \
  --layout-features= --flavor=woff2 --output-file=src/main/resources/icons.woff2
```

Material Symbols is Apache-2.0, which permits redistribution.
