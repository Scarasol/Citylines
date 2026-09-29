"""Read-only JFR execution-sample report, for comparing profiler snapshots before/after a change.

Usage:
    jfr print --events jdk.ExecutionSample --stack-depth 48 <snapshot.jfr> | python3 tools/analysis/jfr_report.py

Prints stack membership, not exclusive CPU cost. A wrapper includes time in its native callees.
LC2H also uses non-Worker-Main executors: worker shares are not whole-worldgen shares.
Compare equivalent workloads and sampling settings, not arbitrary recording percentages.
"""
import collections
import re
import sys

AREAS = [
    ("citylines.terrain", r"\bcom\.scarasol\.citylines\.terrain\."),
    ("  -> TerrainFlattening other", r"\bTerrainFlattening\."),
    ("heightmap repair scan", r"ChunkHeightmapRepair\$Commit\.(rebuild|applyAsInt)|ColumnHeights\.scan"),
    ("LC2H CityShiftField", r"\bCityShiftField(?:\$[\w$]+)?\."),
    ("LC2H NaturalHeightSampler", r"\bNaturalHeightSampler(?:\$[\w$]+)?\."),
    ("citylines.road/tlc", r"\bcom\.scarasol\.citylines\.road\.tlc\."),
    ("citylines.road/core", r"\bcom\.scarasol\.citylines\.road\.core\."),
    ("citylines mixin hooks", r"citylines\$"),
    ("lostcities", r"\bmcjty\.lostcities\."),
    ("minecraft", r"\bnet\.minecraft\."),
    ("forge", r"\bnet\.minecraftforge\."),
]


UNION = [
    ("citylines (whole mod)", r"com\.scarasol\.citylines"),
    ("  terrain (flattening)", r"com\.scarasol\.citylines\.terrain"),
    ("  road/tlc", r"com\.scarasol\.citylines\.road\.tlc"),
    ("  road/core", r"com\.scarasol\.citylines\.road\.core"),
    ("  mixin hooks", r"citylines\$"),
    ("lostcities", r"mcjty\.lostcities"),
    ("minecraft (vanilla)", r"net\.minecraft\."),
    ("minecraftforge", r"net\.minecraftforge\."),
    ("java.util.concurrent", r"java\.util\.concurrent"),
]


# Optional argv[1]: a regex. Only samples whose stack matches it are counted, and the union
# shares become conditional on that scope (e.g. "only chunk-generation samples"). This is the
# honest denominator for "is X a main cost of chunk generation".
SCOPE = sys.argv[1] if len(sys.argv) > 1 else None


def main():
    total = 0
    worker = 0
    area = collections.Counter()
    worker_area = collections.Counter()
    deepest_city = collections.Counter()
    deepest_city_worker = collections.Counter()
    caller_city = collections.Counter()
    leaf = collections.Counter()
    union_all = collections.Counter()
    union_worker_all = collections.Counter()
    scope_total = [0]
    thread = "?"
    stack = None
    for line in sys.stdin:
        line = line.rstrip("\n")
        found_thread = re.search(r'sampledThread = "([^"]+)"', line)
        if found_thread:
            thread = found_thread.group(1)
        if "stackTrace = [" in line:
            stack = []
            continue
        if stack is None:
            continue
        if re.match(r"\s*\]", line):
            total += 1
            is_worker = thread.startswith("Worker-Main")
            if is_worker:
                worker += 1
            if stack:
                leaf[stack[0]] += 1
            if SCOPE and not any(re.search(SCOPE, frame) for frame in stack):
                continue
            scope_total[0] += 1
            for name, pattern in UNION:
                if any(re.search(pattern, frame) for frame in stack):
                    union_all[name] += 1
                    if is_worker:
                        union_worker_all[name] += 1
            hit_any = False
            for name, pattern in AREAS:
                if any(re.search(pattern, frame) for frame in stack):
                    area[name] += 1
                    if is_worker:
                        worker_area[name] += 1
                    hit_any = True
            for index, frame in enumerate(stack):
                if "com.scarasol.citylines" in frame:
                    deepest_city[frame] += 1
                    if is_worker:
                        deepest_city_worker[frame] += 1
                    if index + 1 < len(stack):
                        caller_city[f"{stack[index]}  <=  {stack[index + 1]}"] += 1
                    break
            stack = None
            continue
        text = line.strip()
        if text:
            stack.append(text)

    # Whole-mod union: sub-areas overlap heavily (a mixin hook frame encloses the call into the
    # road planner), so summing them over-counts. This is the number to quote for "how much of
    # chunk generation is this mod".
    union = collections.Counter()
    union_worker = collections.Counter()
    for name, pattern, in UNION:
        union[name] = area[name] if name in area else 0
    print(f"total samples: {total}   worker samples: {worker} ({100.0 * worker / max(1, total):.1f}%)")
    if SCOPE:
        print(f"\n== scope filter: {SCOPE} ==")
        print(f"  samples in scope: {scope_total[0]}"
              f"  ({100.0 * scope_total[0] / max(1, total):.1f}% of all,"
              f" {100.0 * scope_total[0] / max(1, worker):.1f}% of worker)")
        for name, pattern in UNION:
            hits = union_all[name]
            if hits:
                print(f"    {name:24s} {hits:7d}  {100.0 * hits / max(1, scope_total[0]):5.2f}% of scope")

    print("\n== union shares (overlap counted once) ==")
    for name, pattern in UNION:
        all_hits = union_all[name]
        worker_hits = union_worker_all[name]
        print(f"  {name:24s} {all_hits:7d}  {100.0 * all_hits / max(1, total):5.2f}% of all"
              f"   {100.0 * worker_hits / max(1, worker):5.2f}% of worker")

    print("\n== area shares (overlapping) ==")
    for name, _ in AREAS:
        print(f"  {name:28s} {area[name]:7d}  {100.0 * area[name] / max(1, total):5.2f}% of all"
              f"   {100.0 * worker_area[name] / max(1, worker):5.2f}% of worker")
    print("\n== deepest citylines frame (all / worker) ==")
    for frame, count in deepest_city.most_common(8):
        print(f"  {count:7d} ({100.0 * count / max(1, total):5.2f}%)  worker {deepest_city_worker[frame]:7d}"
              f"  {frame[:110]}")
    print("\n== callers of the hottest citylines frame ==")
    for frame, count in caller_city.most_common(5):
        print(f"  {count:7d}  {frame[:170]}")
    print("\n== top leaf frames ==")
    for frame, count in leaf.most_common(10):
        print(f"  {count:7d}  {100.0 * count / max(1, total):5.2f}%  {frame[:110]}")


if __name__ == "__main__":
    main()
