package de.basefinder;

import java.util.Random;

/** Berechnet Basis-Positionen deterministisch aus Weltseed (kein Speicher noetig). */
public final class BaseLocator {
    private final int cell, minR, maxR, size;
    private final double chance;
    private final long salt;

    public BaseLocator(int cell, int minR, int maxR, double chance, int size, long salt) {
        this.cell = Math.max(64, cell);
        this.minR = minR;
        this.maxR = maxR;
        this.chance = chance;
        this.size = size;
        this.salt = salt;
    }

    private Random rnd(long seed, int cx, int cz) {
        long h = seed ^ (cx * 341873128712L) ^ (cz * 132897987541L) ^ (salt * 0x9E3779B97F4A7C15L);
        h *= 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return new Random(h);
    }

    /** Raumzentrum {x,z} der Basis in Zelle (cx,cz) oder null. */
    public int[] inCell(long seed, int cx, int cz) {
        Random r = rnd(seed, cx, cz);
        if (r.nextDouble() >= chance) return null;
        int half = size / 2;
        int bx = cx * cell + r.nextInt(cell);
        int bz = cz * cell + r.nextInt(cell);
        int lx = half + 1 + r.nextInt(14 - 2 * half);
        int lz = half + 1 + r.nextInt(14 - 2 * half);
        bx = Math.floorDiv(bx, 16) * 16 + lx;
        bz = Math.floorDiv(bz, 16) * 16 + lz;
        double d = Math.hypot(bx, bz);
        if (d < minR || d > maxR) return null;
        return new int[]{bx, bz};
    }

    /** Basis-Zentrum {x,z}, falls sie im Chunk (chx,chz) liegt. */
    public int[] forChunk(long seed, int chx, int chz) {
        int x0 = Math.floorDiv(chx * 16, cell), x1 = Math.floorDiv(chx * 16 + 15, cell);
        int z0 = Math.floorDiv(chz * 16, cell), z1 = Math.floorDiv(chz * 16 + 15, cell);
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                int[] p = inCell(seed, cx, cz);
                if (p != null && (p[0] >> 4) == chx && (p[1] >> 4) == chz) return p;
            }
        }
        return null;
    }

    /** Naechste Basis (horizontal) innerhalb des Radius oder null. */
    public int[] nearest(long seed, int px, int pz, int radius) {
        int x0 = Math.floorDiv(px - radius - 16, cell), x1 = Math.floorDiv(px + radius + 16, cell);
        int z0 = Math.floorDiv(pz - radius - 16, cell), z1 = Math.floorDiv(pz + radius + 16, cell);
        int[] best = null;
        double bd = Double.MAX_VALUE;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                int[] p = inCell(seed, cx, cz);
                if (p == null) continue;
                double d = Math.hypot(p[0] - px, p[1] - pz);
                if (d <= radius && d < bd) {
                    bd = d;
                    best = p;
                }
            }
        }
        return best;
    }
}
