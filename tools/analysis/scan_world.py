"""Read-only surface-material summary of an already saved world.

Usage: python tools/analysis/scan_world.py <save-folder> [--ground 71]

A surface road-material match is only a candidate, not proof of TLC city membership.
Use /citylines chunk for authoritative live city and heightmap diagnostics.
The NBT helpers also support read-only coordinate probes.
"""
import argparse
import collections
import os
import struct
import sys
import zlib

def rnbt(data, i=0):
    """Minimal binary NBT reader: returns ((name, value), next_index)."""
    tag = data[i]
    i += 1
    if tag == 0:
        return None, i
    length = struct.unpack_from('>H', data, i)[0]
    i += 2
    name = data[i:i + length].decode('utf-8', 'replace')
    i += length
    value, i = rp(data, i, tag)
    return (name, value), i


def rp(data, i, tag):
    if tag == 1:
        return struct.unpack_from('>b', data, i)[0], i + 1
    if tag == 2:
        return struct.unpack_from('>h', data, i)[0], i + 2
    if tag == 3:
        return struct.unpack_from('>i', data, i)[0], i + 4
    if tag == 4:
        return struct.unpack_from('>q', data, i)[0], i + 8
    if tag == 5:
        return struct.unpack_from('>f', data, i)[0], i + 4
    if tag == 6:
        return struct.unpack_from('>d', data, i)[0], i + 8
    if tag == 7:
        n = struct.unpack_from('>i', data, i)[0]
        i += 4
        return data[i:i + n], i + n
    if tag == 8:
        n = struct.unpack_from('>H', data, i)[0]
        i += 2
        return data[i:i + n].decode('utf-8', 'replace'), i + n
    if tag == 9:
        element = data[i]
        i += 1
        n = struct.unpack_from('>i', data, i)[0]
        i += 4
        out = []
        for _ in range(n):
            value, i = rp(data, i, element)
            out.append(value)
        return out, i
    if tag == 10:
        out = {}
        while True:
            inner = data[i]
            if inner == 0:
                i += 1
                return out, i
            (name, value), i = rnbt(data, i)
            out[name] = value
    if tag == 11:
        n = struct.unpack_from('>i', data, i)[0]
        i += 4
        return list(struct.unpack_from('>%di' % n, data, i)), i + 4 * n
    if tag == 12:
        n = struct.unpack_from('>i', data, i)[0]
        i += 4
        return list(struct.unpack_from('>%dq' % n, data, i)), i + 8 * n
    raise ValueError('unknown NBT tag %d' % tag)

ROAD = {'minecraft:gray_concrete', 'minecraft:light_gray_concrete',
        'minecraft:polished_andesite', 'minecraft:white_concrete'}
SKIP = {
    'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air', 'minecraft:water',
    'minecraft:lava', 'minecraft:short_grass', 'minecraft:tall_grass', 'minecraft:grass',
    'minecraft:fern', 'minecraft:dead_bush', 'minecraft:vine', 'minecraft:snow',
    'minecraft:oak_leaves', 'minecraft:acacia_leaves', 'minecraft:birch_leaves',
    'minecraft:spruce_leaves', 'minecraft:jungle_leaves', 'minecraft:dark_oak_leaves',
    'minecraft:poppy', 'minecraft:dandelion', 'minecraft:torch', 'minecraft:wall_torch',
}
TOP_SECTION = 127
BOTTOM_SECTION = -128


def read_chunk(path, slot):
    with open(path, 'rb') as handle:
        header = handle.read(4096)
        entry = struct.unpack_from('>I', header, slot * 4)[0]
        if not entry:
            return None
        handle.seek((entry >> 8) * 4096)
        length = struct.unpack('>I', handle.read(4))[0]
        comp = handle.read(1)[0]
        if comp != 2:
            return None
        raw = zlib.decompress(handle.read(length - 1))
    node, _ = rnbt(raw)
    return node[1]


def section_blocks(section):
    """{(x, z): (y, name)} for the highest non-skip block per column in this section."""
    index = section.get('Y')
    states = section.get('block_states')
    if not states or 'palette' not in states:
        return index, {}
    names = [entry.get('Name') for entry in states['palette']]
    if all(name in SKIP for name in names):
        return index, {}
    data = states.get('data')
    best = {}
    if data is None:
        name = names[0]
        if name in SKIP:
            return index, {}
        for y in range(16):
            for z in range(16):
                for x in range(16):
                    best[(x, z)] = (index * 16 + y, name)
        return index, best
    bits = max(4, (len(names) - 1).bit_length())
    per_long = 64 // bits
    mask = (1 << bits) - 1
    for long_index, value in enumerate(data):
        base = long_index * per_long
        for k in range(per_long):
            flat = base + k
            if flat >= 4096:
                break
            entry = (value >> (k * bits)) & mask
            if entry >= len(names):
                continue
            name = names[entry]
            if name in SKIP:
                continue
            y = index * 16 + (flat >> 8)
            key = (flat & 15, (flat >> 4) & 15)
            current = best.get(key)
            if current is None or y > current[0]:
                best[key] = (y, name)
    return index, best


def chunk_tops(root):
    tops = {}
    sections = sorted((s for s in root.get('sections', []) if isinstance(s, dict)),
                      key=lambda s: s.get('Y', -99), reverse=True)
    for section in sections:
        index = section.get('Y')
        if index is None or index > TOP_SECTION or index < BOTTOM_SECTION:
            continue
        _, found = section_blocks(section)
        for key, value in found.items():
            tops.setdefault(key, value)
        if len(tops) >= 256:
            break
    return tops


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('save')
    parser.add_argument('--ground', type=int, default=71)
    args = parser.parse_args()
    region_dir = os.path.join(args.save, 'region')
    candidates = {}
    for name in sorted(os.listdir(region_dir)):
        if not name.endswith('.mca'):
            continue
        path = os.path.join(region_dir, name)
        for slot in range(1024):
            root = read_chunk(path, slot)
            if root is None or root.get('xPos') is None:
                continue
            counts = collections.Counter(y for y, material in chunk_tops(root).values() if material in ROAD)
            if counts:
                height, count = counts.most_common(1)[0]
                if count >= 16:
                    candidates[(root['xPos'], root['zPos'])] = height
    print(f'save: {args.save}')
    print(f'road-material candidate chunks: {len(candidates)} (not an authoritative city mask)')
    print(f'dominant surface Y distribution: {dict(sorted(collections.Counter(candidates.values()).items()))}')
    print(f'candidates off requested ground {args.ground}: {sum(y != args.ground for y in candidates.values())}')
    differences = collections.Counter()
    for (x, z), height in candidates.items():
        for dx, dz in ((1, 0), (0, 1)):
            other = candidates.get((x + dx, z + dz))
            if other is not None:
                differences[abs(other - height)] += 1
    print(f'adjacent candidate height differences: {dict(sorted(differences.items()))}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
