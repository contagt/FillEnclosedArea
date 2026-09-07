# Fill Enclosed Area — JOSM plugin

Adds a map mode (toolbar toggle button, "Fill Enclosed Area") that turns a
click into a new **closed way**, but only when the click point sits inside a
region that is **genuinely enclosed by existing, connected way geometry**.

## What it does and does not do

- ✅ Only fills a region if the surrounding OSM geometry forms a **closed
  boundary made of already-shared nodes**.
- ✅ **No gap bridging** — if two ways don't actually share a node, the
  boundary is not considered closed there.
- ✅ **No snapping or guessing** — coordinates are never invented, moved, or
  approximated.
- ✅ **No freehand approximation** — the new way is built from the exact
  existing node sequence of the enclosing boundary.
- ✅ If the clicked point is not inside a closed boundary, nothing happens
  and you get a short notification.
- ✅ **Reuses existing nodes/ways** — the new way references the same `Node`
  objects already in your dataset; if an identical closed way already
  exists, that way is selected instead of creating a duplicate.
- ✅ The result is a completely normal `Way`, added via JOSM's command
  stack (`AddCommand`), so it's fully undoable with Ctrl+Z like any other
  edit.

## How it works

1. Every non-deleted way in the current edit layer is decomposed into a line
   string using each node's real projected (EastNorth) coordinates.
2. All line strings are handed to a [JTS](https://locationtech.github.io/jts/)
   `Polygonizer`, which computes the faces of the planar arrangement formed
   by that geometry. A face only closes where segments share an **exact**
   endpoint coordinate — i.e. an actual shared OSM node. Ways that merely
   run close together, cross without a shared node, or leave a gap are never
   joined by this step.
3. Among the resulting faces, the smallest one that contains the clicked
   point is chosen (the innermost enclosing region).
4. Every coordinate of that face's boundary ring is mapped back to the
   original OSM node it came from. If any point can't be matched to a real
   node (which shouldn't happen, since the polygonizer never invents
   coordinates), the plugin aborts and creates nothing rather than guess.
5. A new `Way` is built from that exact node sequence and added to the
   dataset via `AddCommand`.

## Building (Ant)

Requires a JDK and [Apache Ant](https://ant.apache.org/) — that's it, no
build-tool version matrix to fight.

1. Get the two dependency jars in place — see `lib/README.txt` for exactly
   where to get each one:
   - your local JOSM install's own jar (e.g. `josm-tested.jar`)
   - `jts-core-1.19.0.jar` (used for the enclosed-face detection)
2. Optionally copy `build.properties.sample` to `build.properties` and
   adjust the paths/Java version for your machine (not required if you just
   drop both jars into `lib/` under the default names).
3. Build:

   ```bash
   ant dist
   ```

   The plugin jar is written to `dist/FillEnclosedArea.jar`.

4. Optionally, build straight into your local JOSM plugins folder:

   ```bash
   ant install
   ```

   (adjust `josm.plugins.dir` in `build.properties` first if you're not on
   the Linux default path).

Other useful targets: `ant clean` (wipe build output), `ant compile`
(compile only, useful for a quick syntax check).

**Note on the bundled JTS dependency:** since JOSM plugins are loaded as a
single self-contained jar (no external classpath resolution at runtime),
the build merges JTS's classes into the plugin jar itself rather than
shading/relocating them (Gradle's `shadowJar` did the relocation before;
plain Ant doesn't have an equivalent built in without pulling in an extra
tool like `jarjar`). In the unlikely case another installed plugin also
bundles a different JTS version, you could hit a class conflict — let me
know if you want the `jarjar` relocation step added.

## Installing

1. Copy the built jar into your JOSM plugins directory:
   - Linux: `~/.local/share/JOSM/plugins/`
   - Windows: `%APPDATA%\JOSM\plugins\`
   - macOS: `~/Library/JOSM/plugins/`
2. Restart JOSM, or enable it via *Edit → Preferences → Plugins*.

## Usage

1. Click the **"Fill Enclosed Area"** toggle button in the map-mode toolbar
   (left side of the JOSM window).
2. Click anywhere inside a region bounded by connected way geometry.
3. If the region is genuinely closed, a new closed way is created and
   selected. If not, you'll get a short "not inside a closed boundary"
   notification and nothing is changed.

## Known limitations

- The whole edit layer's ways are scanned on every click; for very large
  loaded areas this may take a moment.
- If the enclosing face contains holes (e.g. an island fully inside the
  boundary, itself formed by connected geometry), only the outer ring is
  turned into a way — inner rings are not currently assembled into a
  multipolygon relation. This could be added as a follow-up if needed.
- Ways with missing/incomplete node coordinates are skipped, as they can't
  contribute exact geometry.
