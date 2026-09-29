package com.scarasol.citylines.road.core;

import java.nio.charset.StandardCharsets;

/**
 * Deterministic hashing for planning decisions.
 *
 * <p>Everything here is plain long arithmetic (splitmix64 / FNV-1a): no
 * {@code Object.hashCode}, no identity hashes, no JVM-dependent behaviour. The
 * same world seed, dimension id, algorithm version and coordinates must produce
 * the same value on every JVM and in every thread, independent of query order.
 */
public final class Hash {

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final long FNV_OFFSET = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;

    private Hash() {
    }

    /** splitmix64 finaliser. */
    public static long mix(long value) {
        long z = value + GOLDEN;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Stable hash of a string (FNV-1a 64 over UTF-8 bytes). */
    public static long string(String value) {
        long h = FNV_OFFSET;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xFFL);
            h *= FNV_PRIME;
        }
        return h;
    }

    /** Ordered combination of two values. */
    public static long combine(long a, long b) {
        return mix(a ^ mix(b + GOLDEN));
    }

    public static long combine(long a, long b, long c) {
        return combine(combine(a, b), c);
    }

    public static long combine(long a, long b, long c, long d) {
        return combine(combine(a, b), combine(c, d));
    }

    /** Hash of a world seed plus dimension id. */
    public static long dimension(long seed, String dimensionId) {
        return combine(seed, string(dimensionId));
    }

    /** Hash of two coordinates. */
    public static long coords(long salt, int x, int z) {
        return combine(salt, combine(x, z));
    }

    /**
     * Uniform double in {@code [0,1)} from a 53-bit slice; used for chance
     * thresholds such as optional primary lines and bridge probability.
     */
    public static double unit(long value) {
        return (mix(value) >>> 11) * 0x1.0p-53;
    }

    /** True with probability {@code chance}. */
    public static boolean chance(long value, double chance) {
        if (chance <= 0.0) {
            return false;
        }
        if (chance >= 1.0) {
            return true;
        }
        return unit(value) < chance;
    }
}
