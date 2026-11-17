package de.basefinder;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class BaseBuilder {
    public record Loot(Material material, int min, int max, double chance) {}

    private BaseBuilder() {}

    public static void build(World w, int cx, int cz, int floorY, int size, int height,
                             Material wall, Material floor, int chests, List<Loot> loot, Random r) {
        int half = size / 2;
        for (int dx = -half - 1; dx <= half + 1; dx++) {
            for (int dz = -half - 1; dz <= half + 1; dz++) {
                for (int y = floorY - 1; y <= floorY + height; y++) {
                    boolean inside = Math.abs(dx) <= half && Math.abs(dz) <= half;
                    boolean shell = !inside || y == floorY - 1 || y == floorY + height;
                    Material m = !shell ? Material.AIR : (y == floorY - 1 && inside ? floor : wall);
                    w.getBlockAt(cx + dx, y, cz + dz).setType(m, false);
                }
            }
        }
        w.getBlockAt(cx, floorY, cz).setType(Material.LANTERN, false);

        Set<Long> used = new HashSet<>();
        int placed = 0, tries = 0;
        while (placed < chests && tries++ < 100) {
            int dx = r.nextInt(size) - half, dz = r.nextInt(size) - half;
            if (dx == 0 && dz == 0) continue;
            if (!used.add(((long) dx << 32) ^ (dz & 0xffffffffL))) continue;
            Block b = w.getBlockAt(cx + dx, floorY, cz + dz);
            b.setType(Material.CHEST, false);
            if (b.getState() instanceof Chest chest) {
                Inventory inv = chest.getBlockInventory();
                for (Loot l : loot) {
                    if (r.nextDouble() >= l.chance()) continue;
                    int slot = r.nextInt(inv.getSize());
                    if (inv.getItem(slot) != null) continue;
                    int amt = l.min() + r.nextInt(Math.max(1, l.max() - l.min() + 1));
                    inv.setItem(slot, new ItemStack(l.material(), Math.max(1, Math.min(amt, l.material().getMaxStackSize()))));
                }
            }
            placed++;
        }
    }
}
