#!/usr/bin/env python3
"""Generate the current three-tier Citylines street and bridge assets for TLC 7.5.5.

python tools/generate_road_parts.py          # regenerate and verify
python tools/generate_road_parts.py --check  # read-only verification

The 33 canonical street pieces cover 110 directed keys. Geometric ports are
distinct from logical edge classes: local entrances use the collector-width port.
All sections fill 16 blocks: PRIMARY 1/1/12/1/1, SECONDARY 2/1/10/1/2,
TERTIARY 4/1/6/1/4 (walk/kerb/carriageway/kerb/walk).
The centre remains visible; only connected arms expose pavement at an edge.
A two-slice bridge deck uses the PRIMARY section and outer railings.

Part palettes control surfaces; the separate chunk damage palette supplies
full-height, non-gravity replacement blocks without changing building characters.
"""

from __future__ import annotations

import argparse
import itertools
import json
import sys
from pathlib import Path
from typing import Dict, Iterable, List, Sequence, Set, Tuple

REPO_ROOT = Path(__file__).resolve().parents[1]
OUT_ROOT = REPO_ROOT / "src/main/resources/data/citylines/lostcities"
REF_ROOT = REPO_ROOT / "reference/lostcities-src/src/main/resources/data/lostcities/lostcities"

SIZE = 16

# ---------------------------------------------------------------- road classes
# Must match the current RoadType encoding, in hierarchy order.
NONE, TERTIARY, SECONDARY, PRIMARY = 0, 1, 2, 3
CLASS_CHAR = {NONE: "0", TERTIARY: "t", SECONDARY: "s", PRIMARY: "p"}
CHAR_CLASS = {c: k for k, c in CLASS_CHAR.items()}
# The entrance transition: the first local cell's arm towards a high-class road must present
# that neighbour's own section, because the shared edge has to match cell for cell. A local
# cell's key therefore uses ``s``/``p`` on an entrance arm and ``t`` on a purely local arm --
# the *port* letters, which are what the asset has to render. The logical edge class stays
# TERTIARY (``min`` of the two ends); that is carried by the plan, not by the asset name.
# Canonical geometry uses hierarchy order.
CLASS_RANK = {NONE: 0, TERTIARY: 1, SECONDARY: 2, PRIMARY: 3}
DIRECTIONS = ("N", "E", "S", "W")  # Direction ordinals 0..3; never reorder
DIR_INDEX = {"N": 0, "E": 1, "S": 2, "W": 3}

# walk, kerb, carriageway per class (2*(walk+kerb) + carriageway == 16).
# Decision D4 (three-tier form): the arterial is the widest tier and the only one with a
# centre line, the collector keeps the form the arterial used to have, and the local tier
# keeps its narrow section - so the three levels differ in width, in markings and in surface.
SECTION = {TERTIARY: (4, 1, 6), SECONDARY: (2, 1, 10), PRIMARY: (1, 1, 12)}

CH_CARRIAGEWAY, CH_LOCAL, CH_CENTRE = "P", "R", "C"
CH_WALK, CH_KERB, CH_SKIP = "F", "K", "b"
MATERIALS = {
    CH_CARRIAGEWAY: "minecraft:gray_concrete",
    CH_LOCAL: "minecraft:smooth_stone",
    CH_CENTRE: "minecraft:white_concrete",
    CH_WALK: "minecraft:light_gray_concrete",
    CH_KERB: "minecraft:polished_andesite",
    CH_SKIP: "minecraft:structure_void",
}
#: driving-surface char per class (the local tier is the one with its own material)
LANE = {TERTIARY: CH_LOCAL, SECONDARY: CH_CARRIAGEWAY, PRIMARY: CH_CARRIAGEWAY}
#: every char that counts as driving surface, including the centre line
PAVED = {CH_CARRIAGEWAY, CH_LOCAL, CH_CENTRE}
PART_PALETTE = "citylines:street"
BRIDGE_DECK_FILE = "bridge_deck"
BRIDGE_DECK_ID = "citylines:" + BRIDGE_DECK_FILE
CH_RAILING = "R"
RAILING_BLOCK = "minecraft:iron_bars"
DAMAGE_PALETTE = "citylines:road_damage"
STYLE = "citylines:standard"
CITYSTYLE = "citylines:standard"
WORLDSTYLE = "citylines:standard"

#: damaged target: full block, no gravity, no random tick, no block entity
DAMAGE_BLOCK = "minecraft:andesite"
#: chars free in every built-in palette *and* every built-in part/building template
DAMAGE_CHARS = {
    CH_CARRIAGEWAY: "?",
    CH_LOCAL: "'",
    CH_CENTRE: ",",
    CH_WALK: "<",
    CH_KERB: ">",
}


# ------------------------------------------------------------------- geometry
def port_class_of(letter: str) -> int:
    """Road class whose section a port letter presents."""
    return CHAR_CLASS[letter]


def band(cls: int) -> Tuple[int, int]:
    """Half-open carriageway band ``[lo, hi)`` perpendicular to a class's axis."""
    walk, kerb, carriageway = SECTION[cls]
    return walk + kerb, walk + kerb + carriageway


def lane(cls: int) -> str:
    """Driving-surface char of a class."""
    return LANE[cls]


def centre_from(cls: int) -> int:
    """First of the two centre cells; every tier's middle is the pair 7/8."""
    lo, _ = band(cls)
    return lo + SECTION[cls][2] // 2 - 1


def build_grid_for(centre: int, key: str) -> List[str]:
    """z-major 16x16 role grid of ``(centre, key)``.

    ``grid[z][x]`` with ``x`` growing east and ``z`` growing south; the centre is
    the piece's own road class and ``key`` lists the four shared edge classes.

    Painting order mirrors ``RoadSurfaceLayout.grid``: arms first (each in its own
    class's lane material), then the piece's centre square, then the arterial centre
    line, then kerbs and walk.
    """
    edges = [port_class_of(c) for c in key]
    if len(edges) != 4:
        raise ValueError("key must have four NESW classes: " + key)
    lo, hi = band(centre)

    surface: Dict[Tuple[int, int], str] = {}

    def paint(x0: int, x1: int, z0: int, z1: int, char: str) -> None:
        for x in range(max(0, x0), min(SIZE - 1, x1) + 1):
            for z in range(max(0, z0), min(SIZE - 1, z1) + 1):
                surface[(x, z)] = char

    for direction, cls in zip(DIRECTIONS, edges):
        if cls == NONE:
            continue
        a_lo, a_hi = band(cls)
        char = lane(cls)
        if direction == "N":  # band along X, from z=0 to the far side of the centre
            paint(a_lo, a_hi - 1, 0, hi - 1, char)
        elif direction == "S":
            paint(a_lo, a_hi - 1, lo, SIZE - 1, char)
        elif direction == "W":  # band along Z, from x=0 to the far side of the centre
            paint(0, hi - 1, a_lo, a_hi - 1, char)
        else:  # E
            paint(lo, SIZE - 1, a_lo, a_hi - 1, char)

    # The junction surface wins over every arm: it is the piece's own class.
    paint(lo, hi - 1, lo, hi - 1, lane(centre))

    n_line, e_line, s_line, w_line = (cls == PRIMARY for cls in edges)
    middle = centre_from(PRIMARY)
    if n_line:
        paint(middle, middle + 1, 0, lo - 1, CH_CENTRE)
    if s_line:
        paint(middle, middle + 1, hi, SIZE - 1, CH_CENTRE)
    if w_line:
        paint(0, lo - 1, middle, middle + 1, CH_CENTRE)
    if e_line:
        paint(hi, SIZE - 1, middle, middle + 1, CH_CENTRE)
    through_z = n_line and s_line
    through_x = e_line and w_line
    junction = (through_z and (edges[1] != NONE or edges[3] != NONE)) or (
        through_x and (edges[0] != NONE or edges[2] != NONE)
    )
    if centre == PRIMARY and through_z != through_x and not junction:
        if through_z:
            paint(middle, middle + 1, lo, hi - 1, CH_CENTRE)
        else:
            paint(lo, hi - 1, middle, middle + 1, CH_CENTRE)

    kerb: Set[Tuple[int, int]] = set()
    for x in range(SIZE):
        for z in range(SIZE):
            if (x, z) in surface:
                continue
            if (
                (x + 1, z) in surface
                or (x - 1, z) in surface
                or (x, z + 1) in surface
                or (x, z - 1) in surface
            ):
                kerb.add((x, z))

    return [
        "".join(
            surface.get((x, z), CH_KERB if (x, z) in kerb else CH_WALK)
            for x in range(SIZE)
        )
        for z in range(SIZE)
    ]


def port_section(port: str) -> str:
    """The section a *port letter* presents: ``t`` narrow, ``s``/``p`` the medium/large section.

    A local cell uses ``s``/``p`` on the arm that enters a high-class road, which is the
    "fixed entrance transition piece" while the local ends stay ``t``.
    """
    return section_sequence(port_class_of(port))


def section_sequence(cls: int) -> str:
    """The single role string an edge of class ``cls`` may present."""
    if cls == NONE:
        return CH_WALK * SIZE
    walk, kerb, carriageway = SECTION[cls]
    middle = centre_from(cls)
    cells = "".join(
        CH_CENTRE
        if cls == PRIMARY and walk + kerb + n in (middle, middle + 1)
        else lane(cls)
        for n in range(carriageway)
    )
    return CH_WALK * walk + CH_KERB * kerb + cells + CH_KERB * kerb + CH_WALK * walk


def bridge_deck_surface() -> List[str]:
    """Slice 0 of the bridge deck: the PRIMARY cross-section across one axis.

    ``Bridges.generateBridge`` places a part as ``getPaletteChar(x, l, z)`` for
    ``Orientation.X`` and as ``getPaletteChar(z, l, x)`` for ``Orientation.Z`` (a
    transpose). A grid whose row ``z`` is the single role ``section(z)`` therefore
    presents the PRIMARY section on the two edges perpendicular to the road axis in
    BOTH orientations, and all-walk on its two long sides - exactly the port the Citylines
    PRIMARY street presents at the shore, at the same Y (the deck is placed at
    ``profile.GROUNDLEVEL`` while the Citylines street sits at
    ``getCityGroundLevel() == GROUNDLEVEL + cityLevel * FLOORHEIGHT`` and bridge
    shores are level-0 chunks by construction).
    """
    section = section_sequence(PRIMARY)
    return [section[z] * SIZE for z in range(SIZE)]


def bridge_deck_railing() -> List[str]:
    """Slice 1: railings above the two outer walk rows, empty everywhere else.

    Empty cells are spaces, exactly like Lost Cities' own multi-slice bridge parts:
    they resolve to no block and leave the world alone (``ChunkDriver.setBlock``
    ignores a null state). Under the Z transpose the railing rows become columns 0
    and 15, i.e. they stay on the two long sides of the span.
    """
    rows = [" " * SIZE for _ in range(SIZE)]
    rows[0] = CH_RAILING * SIZE
    rows[SIZE - 1] = CH_RAILING * SIZE
    return rows


def transpose(grid: Sequence[str]) -> List[str]:
    """The grid ``Orientation.Z`` actually reads: part ``(z, x)`` at world ``(x, z)``."""
    return ["".join(grid[x][z] for x in range(SIZE)) for z in range(SIZE)]


def edge_sequence(grid: Sequence[str], direction: str) -> str:
    """Roles on one edge; N/S read x = 0..15, E/W read z = 0..15."""
    if direction == "N":
        return grid[0]
    if direction == "S":
        return grid[SIZE - 1]
    if direction == "W":
        return "".join(grid[z][0] for z in range(SIZE))
    return "".join(grid[z][SIZE - 1] for z in range(SIZE))


def rot90_cw(grid: Sequence[str]) -> List[str]:
    """One clockwise quarter turn, exactly ``Transform.ROTATE_90``.

    ``generatePart`` maps a part cell ``(x, z)`` to ``(15 - z, x)``, so the role
    that was at ``(x, z)`` is at ``(15 - z, x)`` afterwards; the returned value is
    the z-major role grid of that rotated piece.
    """
    rotated: List[str] = [""] * SIZE
    for x in range(SIZE):  # old x becomes the new z coordinate
        rotated[x] = "".join(grid[SIZE - 1 - xp][x] for xp in range(SIZE))
    return rotated


def rotate_key_cw(key: str) -> str:
    """NESW key of the same piece after one clockwise quarter turn (N -> E)."""
    n, e, s, w = key
    return w + n + e + s


def canonical_key(key: str) -> str:
    """Hierarchy-lexicographic minimum over the four rotations (0 < t < s < p).

    Ordered by ``CLASS_RANK``, not by the character or the class ordinal: ``t`` must sort
    below ``s``. Sorting by ordinal would place TERTIARY last and silently pick a different
    (still valid, but differently named) representative for mixed keys.
    """
    rotations = [key]
    for _ in range(3):
        rotations.append(rotate_key_cw(rotations[-1]))
    return min(rotations, key=lambda k: tuple(CLASS_RANK[port_class_of(c)] for c in k))


def placement(centre: int, key: str) -> Tuple[str, int]:
    """``(canonical arm key, clockwise quarter turns)`` for a directed key.

    ``rotate_key_cw`` is the key-level form of ``Transform.ROTATE_90``, so
    ``key == rotate_key_cw^turns(canonical)`` and the renderer places the canonical
    piece with ``Transform`` ``ROTATE_NONE/90/180/270`` for ``turns`` 0..3.
    """
    canonical = canonical_key(key)
    rotated, steps = key, 0
    while rotated != canonical:
        rotated = rotate_key_cw(rotated)
        steps += 1
        if steps > 3:
            raise AssertionError("no canonical rotation found for " + key)
    return canonical, (4 - steps) % 4


def part_name(centre: int, key: str) -> str:
    """File stem of a piece.

    ``key`` here is the *canonical* key and may carry a rotation suffix when two different
    shapes canonicalise to the same arm string. That happens for a local centre: its four
    single-arm ends are four distinct pieces (the arm points a different way), yet all four
    canonicalise to the same one-arm string. They are therefore named by canonical key plus
    turns, e.g. ``road_t_000t`` and ``road_t_000t_r1``.
    """
    return "road_{}_{}".format(CLASS_CHAR[centre], key)


# -------------------------------------------------------- key space / assets
def logical_classes(centre: int) -> Tuple[int, ...]:
    """Logical **edge** classes a centre can carry, in hierarchy order.

    A shared edge is ``min(centre, neighbour)``, so it can never be stronger than the centre:
    a SECONDARY centre only ever carries ``{0, s}`` arms (never a primary arm), and a TERTIARY
    centre only ``{0, t}``. This mirrors ``RoadPartTable.classesFor`` and must stay in step
    with it.

    Note the V3 *geometry* alias is deliberately **not** applied here. A local-road arm at a
    higher centre reuses the medium port, which is an existing ``s`` arm of that centre's own
    alphabet -- so the alias adds no asset. Applying it here instead would invent impossible
    keys such as a SECONDARY centre with a PRIMARY arm.
    """
    if centre == PRIMARY:
        return (NONE, SECONDARY, PRIMARY)
    if centre == SECONDARY:
        return (NONE, SECONDARY)
    return (NONE, TERTIARY)


def directed_keys() -> List[Tuple[int, str]]:
    """Every reachable ``(centre, NESW)`` port key that needs a part.

    Three families, matching ``RoadPartTable``:

    * ``SECONDARY`` centre: ``{0, s}`` ports, minus the isolated ``0000`` (an isolated
      collector is forbidden by the planner invariants and its ports would be all walk, so
      the renderer declines and V1 covers that chunk defensively);
    * ``PRIMARY`` centre: ``{0, s, p}`` ports, including the isolated cap ``0000``;
    * ``TERTIARY`` centre: fourteen reachable port keys (four ends, two pure throughs, four
      entrance+local and four pure corners), which reduce to four canonical pieces.

    The isolated collector is excluded because the planner never publishes such a cell and its
    ports would be all walk. Total 110 directed keys: TERTIARY 14, SECONDARY 15, PRIMARY 81.
    """
    keys: List[Tuple[int, str]] = []
    for centre in (SECONDARY, PRIMARY):
        for combo in itertools.product(logical_classes(centre), repeat=4):
            key = "".join(CLASS_CHAR[c] for c in combo)
            if key == "0000" and centre == SECONDARY:
                continue
            keys.append((centre, key))
    # At higher centres a local arm uses the medium geometric port. Local centres have four
    # reachable shape families: end, straight through, entrance+local, and pure-local corner.
    # The transitionMask marks the entrance arm while the logical shared edge stays TERTIARY.
    #
    # These are directed keys; canonical_assets()/placement() reduce them to four pieces.
    #   end            : 000t, 00t0, 0t00, t000          -> road_t_000t
    #   pure through   : 0t0t, t0t0                      -> road_t_0t0t
    #   entrance+local : 0t0s, s0t0, 0s0t, t0s0          -> road_t_0s0t
    # 0s0s, 000s and 000p are NOT produced by the planner, so no defensive assets are made.
    for key in ("000t", "00t0", "0t00", "t000"):
        keys.append((TERTIARY, key))
    for key in ("0t0t", "t0t0"):
        keys.append((TERTIARY, key))
    for key in ("0t0s", "s0t0", "0s0t", "t0s0"):
        keys.append((TERTIARY, key))
    for key in ("00tt", "0tt0", "tt00", "t00t"):
        keys.append((TERTIARY, key))
    return keys


def canonical_assets() -> List[Tuple[int, str]]:
    """The generated files, sorted for a stable run order.

    A piece is identified by ``(centre, canonical key, turns)``. For the class-based centres the
    turns are implied by the key (every rotation of one shape shares a canonical key and only one
    of them needs a file), but a local centre's four single-arm ends canonicalise to the same arm
    string while being genuinely different pieces, so the rotation has to be part of the identity.
    """
    assets = set()
    for centre, key in directed_keys():
        canonical, turns = placement(centre, key)
        assets.add((centre, canonical))
    return sorted(assets, key=lambda ck: (ck[0], ck[1]))



def part_object(centre: int, key: str) -> dict:
    return {
        "xsize": SIZE,
        "zsize": SIZE,
        "refpalette": PART_PALETTE,
        "slices": [build_grid_for(centre, key)],
    }


def bridge_deck_object() -> dict:
    """The single Citylines deck part (two slices: surface + railing)."""
    return {
        "xsize": SIZE,
        "zsize": SIZE,
        "refpalette": PART_PALETTE,
        "palette": [{"char": CH_RAILING, "block": RAILING_BLOCK}],
        "slices": [bridge_deck_surface(), bridge_deck_railing()],
    }


def street_palette_object() -> dict:
    return {
        "palette": [
            {"char": char, "block": MATERIALS[char]}
            for char in (CH_CARRIAGEWAY, CH_LOCAL, CH_CENTRE, CH_WALK, CH_KERB, CH_SKIP)
        ]
    }


def damage_palette_object() -> dict:
    return {
        "palette": [
            {
                "char": DAMAGE_CHARS[ch],
                "block": MATERIALS[ch],
                "damaged": DAMAGE_BLOCK,
            }
            for ch in (CH_CARRIAGEWAY, CH_LOCAL, CH_CENTRE, CH_WALK, CH_KERB)
        ]
    }


def read_reference(name: str) -> dict:
    path = REF_ROOT / name
    if not path.is_file():
        raise SystemExit(
            "missing upstream reference asset {}; the generator copies the built-in "
            "standard style/world style so the Citylines chain tracks it".format(path)
        )
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def namespace(name: str) -> str:
    return name if ":" in name else "lostcities:" + name


def style_object() -> dict:
    doc = read_reference("styles/standard.json")
    groups = [
        [{"factor": entry["factor"], "palette": namespace(entry["palette"])} for entry in group]
        for group in doc["randompalettes"]
    ]
    groups.append([{"factor": 1.0, "palette": DAMAGE_PALETTE}])
    return {"randompalettes": groups}


def citystyle_object() -> dict:
    """The Citylines city style.

    ``lostcities:citystyle_common`` defines ``largebridges = [bridge_large_open]``
    (the 14-wide V1 deck) and CityStyle inheritance is ADDITIVE
    (``largeBridgeSelector.addAll(...)``), so the inherited entry cannot be removed
    here. The explicit Citylines entry keeps ``largeBridgeType`` resolvable if that upstream
    selector ever changes; which part is actually rendered is decided in code, where
    a Citylines dimension substitutes the Citylines deck for whatever bridge part the selector
    picked (see ``HierarchicalBridgePlannerMixin`` / ``BuildingInfoMixin``).
    """
    return {
        "style": STYLE,
        "inherit": "lostcities:citystyle_common",
        "selectors": {
            "largebridges": [{"factor": 1.0, "value": BRIDGE_DECK_ID}],
        },
    }


def worldstyle_object() -> dict:
    doc = read_reference("worldstyles/standard.json")
    doc["outsidestyle"] = namespace(doc["outsidestyle"])
    doc["citystyles"] = [{"factor": 1.0, "citystyle": CITYSTYLE}]
    return doc


# ---------------------------------------------------------------- file output
def write_json(path: Path, obj: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(obj, handle, indent=2)
        handle.write("\n")


def outputs() -> Dict[Path, dict]:
    files: Dict[Path, dict] = {}
    for centre, key in canonical_assets():
        files[OUT_ROOT / "parts" / (part_name(centre, key) + ".json")] = part_object(centre, key)
    files[OUT_ROOT / "parts" / (BRIDGE_DECK_FILE + ".json")] = bridge_deck_object()
    files[OUT_ROOT / "palettes" / "street.json"] = street_palette_object()
    files[OUT_ROOT / "palettes" / "road_damage.json"] = damage_palette_object()
    files[OUT_ROOT / "styles" / "standard.json"] = style_object()
    files[OUT_ROOT / "citystyles" / "standard.json"] = citystyle_object()
    files[OUT_ROOT / "worldstyles" / "standard.json"] = worldstyle_object()
    return files


def write_all() -> Dict[Path, dict]:
    files = outputs()
    for path, obj in files.items():
        write_json(path, obj)
    return files


# ---------------------------------------------------------------- self-check
class Report:
    def __init__(self) -> None:
        self.failures: List[str] = []
        self.checks = 0

    def check(self, ok: bool, message: str) -> bool:
        self.checks += 1
        if not ok:
            self.failures.append(message)
        return ok


def check_file_set(report: Report, files: Dict[Path, dict]) -> None:
    expected = {
        OUT_ROOT / "parts" / (part_name(centre, key) + ".json")
        for centre, key in canonical_assets()
    }
    found = set(files)
    report.check(expected <= found, "missing files: " + repr(sorted(expected - found)))
    actual = set((OUT_ROOT / "parts").glob("road_*.json"))
    report.check(
        actual == expected,
        "unexpected road_* files on disk: " + repr(sorted(p.name for p in actual - expected)),
    )
    report.check(len(expected) == 33, "expected 33 part files, got {}".format(len(expected)))
    deck = OUT_ROOT / "parts" / (BRIDGE_DECK_FILE + ".json")
    report.check(deck in found, "missing bridge deck part " + deck.name)


def check_key_space(report: Report) -> None:
    keys = directed_keys()
    report.check(len(keys) == 110, "expected 110 directed keys, got {}".format(len(keys)))
    per_centre = {c: sum(1 for cc, _ in keys if cc == c) for c in (TERTIARY, SECONDARY, PRIMARY)}
    report.check(
        per_centre == {TERTIARY: 14, SECONDARY: 15, PRIMARY: 81},
        "directed key split wrong: {}".format(per_centre),
    )
    assets = canonical_assets()
    report.check(len(assets) == 33, "expected 33 representatives, got {}".format(len(assets)))
    # every canonical asset must be reachable and be its own canonical form
    for centre, key in assets:
        report.check(
            canonical_key(key) == key,
            "asset {} is not canonical".format(part_name(centre, key)),
        )


def check_structure(report: Report, files: Dict[Path, dict]) -> None:
    for path, obj in sorted(files.items()):
        if "parts" not in path.parts:
            continue
        report.check(obj.get("xsize") == SIZE, "{}: xsize".format(path.name))
        report.check(obj.get("zsize") == SIZE, "{}: zsize".format(path.name))
        report.check(obj.get("refpalette") == PART_PALETTE, "{}: refpalette".format(path.name))
        slices = obj.get("slices")
        # The 33 street pieces are single-slice; the bridge deck stacks its railing
        # slice on top of the road surface and is checked separately below.
        want = 2 if path.name == BRIDGE_DECK_FILE + ".json" else 1
        report.check(isinstance(slices, list) and len(slices) == want,
                     "{}: expected {} slice(s)".format(path.name, want))
        if not isinstance(slices, list) or len(slices) != want:
            continue
        known = set(MATERIALS)
        if path.name == BRIDGE_DECK_FILE + ".json":
            known |= {CH_RAILING, " "}
        for index, rows in enumerate(slices):
            report.check(len(rows) == SIZE, "{}: slice {} has {} rows".format(path.name, index, len(rows)))
            for z, row in enumerate(rows):
                report.check(len(row) == SIZE, "{}: slice {} row {} length".format(path.name, index, z))
                report.check(
                    set(row) <= known,
                    "{}: slice {} row {} has unknown chars {}".format(
                        path.name, index, z, sorted(set(row) - known)),
                )


def check_geometry(report: Report, files: Dict[Path, dict]) -> None:
    for centre, key in canonical_assets():
        path = OUT_ROOT / "parts" / (part_name(centre, key) + ".json")
        rows = files[path]["slices"][0]
        report.check(
            rows == build_grid_for(centre, key),
            "{}: cell grid differs from the generator model".format(path.name),
        )


def check_ports(report: Report, files: Dict[Path, dict]) -> None:
    """Every directed key, once rotated, presents the pure section of each edge."""
    for centre, key in directed_keys():
        canonical, turns = placement(centre, key)
        path = OUT_ROOT / "parts" / (part_name(centre, canonical) + ".json")
        grid = files[path]["slices"][0]
        for _ in range(turns):
            grid = rot90_cw(grid)
        for direction in DIRECTIONS:
            cls = port_class_of(key[DIR_INDEX[direction]])
            expected = section_sequence(cls)
            actual = edge_sequence(grid, direction)
            report.check(
                actual == expected,
                "{}/{} turn{} direction {} = {} (want {})".format(
                    part_name(centre, canonical), key, turns, direction, actual, expected
                ),
            )
            if cls == NONE:
                leaked = sorted(set(actual) & PAVED)
                report.check(
                    not leaked,
                    "{}/{}: surface leaks through a NONE edge: {}".format(
                        part_name(centre, canonical), turns, leaked
                    ),
                )


def check_pairwise(report: Report, files: Dict[Path, dict]) -> None:
    """Any two keys that can share an edge agree cell for cell after rotation."""
    keys = directed_keys()
    grids: Dict[Tuple[int, str], List[str]] = {}
    for centre, key in keys:
        canonical, turns = placement(centre, key)
        grid = files[OUT_ROOT / "parts" / (part_name(centre, canonical) + ".json")]["slices"][0]
        for _ in range(turns):
            grid = rot90_cw(grid)
        grids[(centre, key)] = grid

    for centre, key in keys:
        for direction in DIRECTIONS:
            cls = port_class_of(key[DIR_INDEX[direction]])
            mine = edge_sequence(grids[(centre, key)], direction)
            other_edge = DIRECTIONS[(DIR_INDEX[direction] + 2) % 4]
            for other_centre, other_key in keys:
                other_cls = port_class_of(other_key[DIR_INDEX[other_edge]])
                if other_cls != cls:
                    continue
                theirs = edge_sequence(grids[(other_centre, other_key)], other_edge)
                report.check(
                    mine == theirs,
                    "port mismatch {}{} {} vs {}{} {}: {} != {}".format(
                        CLASS_CHAR[centre], key, direction,
                        CLASS_CHAR[other_centre], other_key, other_edge, mine, theirs,
                    ),
                )


def check_centre_visibility(report: Report, files: Dict[Path, dict]) -> None:
    """The frozen design: the centre class must be visible in the middle."""
    for centre, key in canonical_assets():
        rows = files[OUT_ROOT / "parts" / (part_name(centre, key) + ".json")]["slices"][0]
        o, hi = band(centre)
        allowed = {lane(centre)}
        if centre == PRIMARY:
            allowed.add(CH_CENTRE)
        for x in range(o, hi):
            for z in range(o, hi):
                report.check(
                    rows[z][x] in allowed,
                    "{}: centre square cell ({},{}) is {} (want {})".format(
                        part_name(centre, key), x, z, rows[z][x], sorted(allowed)
                    ),
                )
    # a PRIMARY centre with only collector arms must differ from the collector piece
    differing = 0
    for key in ("000s", "00ss", "0s0s", "0sss", "ssss"):
        p_rows = files[OUT_ROOT / "parts" / (part_name(PRIMARY, key) + ".json")]["slices"][0]
        s_rows = files[OUT_ROOT / "parts" / (part_name(SECONDARY, key) + ".json")]["slices"][0]
        if p_rows != s_rows:
            differing += 1
    report.check(
        differing == 5,
        "primary/collector centre variants collapse for {} of 5 collector-only shapes".format(5 - differing),
    )


def check_palettes(report: Report, files: Dict[Path, dict]) -> None:
    used: Set[str] = set()
    for path, obj in files.items():
        if "parts" in path.parts and path.name != BRIDGE_DECK_FILE + ".json":
            for row in obj["slices"][0]:
                used |= set(row)
    deck = files.get(OUT_ROOT / "parts" / (BRIDGE_DECK_FILE + ".json"))
    if report.check(deck is not None, "parts/{}.json is missing".format(BRIDGE_DECK_FILE)):
        local = {entry["char"]: entry["block"] for entry in deck.get("palette", [])}
        report.check(local.get(CH_RAILING) == RAILING_BLOCK,
                     "the deck must define its railing char {} -> {}".format(CH_RAILING, RAILING_BLOCK))
        for rows in deck["slices"]:
            for row in rows:
                for char in set(row):
                    # ' ' is Lost Cities' "write nothing" cell (its own multi-slice
                    # bridge parts use it); every other char must resolve.
                    report.check(
                        char in MATERIALS or char in local or char == " ",
                        "deck char '{}' is defined neither in street nor in the part palette".format(char),
                    )
    street = files.get(OUT_ROOT / "palettes" / "street.json")
    damage = files.get(OUT_ROOT / "palettes" / "road_damage.json")
    if not report.check(street is not None, "palettes/street.json is missing"):
        return
    if not report.check(damage is not None, "palettes/road_damage.json is missing"):
        return
    defined = {entry["char"] for entry in street["palette"]}
    report.check(used <= defined, "part chars missing from street: " + repr(sorted(used - defined)))
    report.check(
        defined == set(MATERIALS),
        "street palette must define exactly " + repr(sorted(MATERIALS)),
    )
    damage_entries = damage["palette"]
    report.check(
        [entry["damaged"] for entry in damage_entries] == [DAMAGE_BLOCK] * len(damage_entries),
        "damage palette must map every Citylines state to " + DAMAGE_BLOCK,
    )
    report.check(
        {entry["block"] for entry in damage_entries}
        == {MATERIALS[c] for c in (CH_CARRIAGEWAY, CH_LOCAL, CH_CENTRE, CH_WALK, CH_KERB)},
        "damage palette states must be the Citylines road states",
    )
    # the damage chars must not override a built-in palette character
    taken: Set[str] = set()
    for ref in REF_ROOT.glob("palettes/*.json"):
        with ref.open(encoding="utf-8") as handle:
            for entry in json.load(handle)["palette"]:
                taken.add(entry["char"][0])
    report.check(
        not (set(DAMAGE_CHARS.values()) & taken),
        "damage chars collide with built-in palette chars: "
        + repr(sorted(set(DAMAGE_CHARS.values()) & taken)),
    )


def check_bridge_deck(report: Report, files: Dict[Path, dict]) -> None:
    """The deck must be flush with the Citylines PRIMARY street in both orientations."""
    path = OUT_ROOT / "parts" / (BRIDGE_DECK_FILE + ".json")
    deck = files.get(path)
    if not report.check(deck is not None, "parts/{}.json is missing".format(BRIDGE_DECK_FILE)):
        return
    surface, railing = deck["slices"]
    section = section_sequence(PRIMARY)
    # every row is one role repeated; reading the first column gives the section
    report.check(
        all(len(set(row)) == 1 for row in surface),
        "the deck surface must be constant along the road axis (one role per cross-section cell)",
    )
    report.check(
        "".join(row[0] for row in surface) == section,
        "the deck cross-section is {}, want {}".format("".join(row[0] for row in surface), section),
    )
    # ports: perpendicular edges carry the street section, long edges are all walk
    for direction in ("W", "E"):
        report.check(
            edge_sequence(surface, direction) == section,
            "deck {} edge must be the PRIMARY section".format(direction),
        )
    for direction in ("N", "S"):
        report.check(
            edge_sequence(surface, direction) == CH_WALK * SIZE,
            "deck {} edge must be the outer walk band".format(direction),
        )
    # Orientation.Z reads the transpose, so the Z deck must present the section on N/S
    z_surface = transpose(surface)
    for direction in ("N", "S"):
        report.check(
            edge_sequence(z_surface, direction) == section,
            "transposed deck {} edge must be the PRIMARY section".format(direction),
        )
    for direction in ("W", "E"):
        report.check(
            edge_sequence(z_surface, direction) == CH_WALK * SIZE,
            "transposed deck {} edge must be the outer walk band".format(direction),
        )
    # railings sit above the two outer walk rows, nowhere else, and stay on the long sides
    report.check(
        railing[0] == CH_RAILING * SIZE and railing[SIZE - 1] == CH_RAILING * SIZE,
        "the deck railing slice must line rows 0 and {} completely".format(SIZE - 1),
    )
    report.check(
        all(set(railing[z]) == {" "} for z in range(1, SIZE - 1)),
        "the deck railing slice must be empty between the two railing rows",
    )
    report.check(
        surface[0][0] == CH_WALK and surface[SIZE - 1][0] == CH_WALK,
        "railings must stand on walk cells, not on the carriageway",
    )
    z_railing = transpose(railing)
    report.check(
        all(z_railing[z][0] == CH_RAILING and z_railing[z][SIZE - 1] == CH_RAILING for z in range(SIZE)),
        "after the Z transpose the railing must still run along both long sides",
    )


def check_chain(report: Report, files: Dict[Path, dict]) -> None:
    style = files.get(OUT_ROOT / "styles" / "standard.json")
    citystyle = files.get(OUT_ROOT / "citystyles" / "standard.json")
    worldstyle = files.get(OUT_ROOT / "worldstyles" / "standard.json")
    if not report.check(
        style is not None and citystyle is not None and worldstyle is not None,
        "styles/citystyles/worldstyles standard.json must all exist",
    ):
        return
    groups = style["randompalettes"]
    report.check(len(groups) >= 2, "style must keep the built-in groups and add the damage group")
    report.check(
        groups[-1] == [{"factor": 1.0, "palette": DAMAGE_PALETTE}],
        "the damage palette must be the last style group so its rules win",
    )
    available = {p.stem for p in REF_ROOT.glob("palettes/*.json")} | {"road_damage"}
    for group in groups:
        for entry in group:
            name = namespace(entry["palette"])
            report.check(
                name.split(":", 1)[1] in available,
                "style references unknown palette " + name,
            )
    report.check(citystyle["style"] == STYLE, "citystyle must point at the Citylines style")
    report.check(
        citystyle["inherit"] == "lostcities:citystyle_common",
        "citystyle must inherit the common street/border blocks",
    )
    report.check(
        (REF_ROOT / "citystyles" / "citystyle_common.json").is_file(),
        "inherited citystyle_common must exist upstream",
    )
    large_bridges = citystyle.get("selectors", {}).get("largebridges", [])
    report.check(
        large_bridges == [{"factor": 1.0, "value": BRIDGE_DECK_ID}],
        "the citystyle must select the Citylines deck in largebridges; got " + repr(large_bridges),
    )
    report.check(
        worldstyle["citystyles"] == [{"factor": 1.0, "citystyle": CITYSTYLE}],
        "worldstyle must select the Citylines citystyle",
    )
    report.check(
        (REF_ROOT / "styles" / (namespace(worldstyle["outsidestyle"]).split(":", 1)[1] + ".json")).is_file(),
        "outside style must exist upstream",
    )


def run_checks(files: Dict[Path, dict]) -> bool:
    report = Report()
    check_file_set(report, files)
    check_key_space(report)
    check_structure(report, files)
    check_geometry(report, files)
    check_ports(report, files)
    check_pairwise(report, files)
    check_centre_visibility(report, files)
    check_bridge_deck(report, files)
    check_palettes(report, files)
    check_chain(report, files)
    if report.failures:
        print("SELF-CHECK FAILED ({} failing of {}):".format(len(report.failures), report.checks))
        for failure in report.failures:
            print("  - " + failure)
        return False
    print("self-check OK: {} assertions, 33 street parts + 1 bridge deck, 110 directed keys".format(report.checks))
    return True


def load_from_disk() -> Dict[Path, dict]:
    files: Dict[Path, dict] = {}
    for path in sorted(OUT_ROOT.rglob("*.json")):
        with path.open(encoding="utf-8") as handle:
            files[path] = json.load(handle)
    return files


def print_grid(name: str) -> int:
    path = OUT_ROOT / "parts" / (name + ".json")
    if not path.is_file():
        print("no such part: " + name, file=sys.stderr)
        return 1
    with path.open(encoding="utf-8") as handle:
        for row in json.load(handle)["slices"][0]:
            print(row)
    return 0


def main(argv: Sequence[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="verify the committed assets only")
    parser.add_argument("--print-grid", metavar="PART", help="print one generated grid and exit")
    args = parser.parse_args(argv)

    if args.print_grid:
        return print_grid(args.print_grid)

    if args.check:
        files = load_from_disk()
    else:
        files = write_all()
        print("wrote {} files under {}".format(len(files), OUT_ROOT))
    return 0 if run_checks(files) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
