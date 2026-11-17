package de.basefinder;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class BaseFinderPlugin extends JavaPlugin implements Listener, TabExecutor {

    static final class Base {
        String world;
        int x, y, z, half, height;
        boolean skipped;
        String foundBy;
    }

    private final Map<String, Base> bases = new HashMap<>();
    private final Set<String> pending = new HashSet<>();
    private final Map<UUID, Long> relogCd = new HashMap<>();
    private final List<BaseBuilder.Loot> loot = new ArrayList<>();
    private Set<String> worlds = new HashSet<>();
    private BaseLocator loc;
    private ProtectionListener prot;
    private File dataFile;
    private int minY, maxY, minDepth, size, height, chests;
    private boolean onlyNew;
    private Material wallMat, floorMat;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "bases.yml");
        loadBases();
        loadSettings();
        prot = new ProtectionListener(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(prot, this);
        PluginCommand cmd = getCommand("basefinder");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        long period = Math.max(10, getConfig().getInt("discovery.check-ticks", 40));
        getServer().getScheduler().runTaskTimer(this, this::checkDiscovery, 100L, period);
    }

    @Override
    public void onDisable() {
        saveBases();
    }

    // ---------- Einstellungen ----------

    private void loadSettings() {
        FileConfiguration c = getConfig();
        worlds = new HashSet<>(c.getStringList("worlds"));
        int s = Math.max(5, Math.min(13, c.getInt("base.size", 9)));
        if (s % 2 == 0) s++;
        size = s;
        height = Math.max(3, Math.min(12, c.getInt("base.height", 5)));
        chests = Math.max(0, c.getInt("base.chests", 3));
        minY = c.getInt("generation.min-y", -48);
        maxY = c.getInt("generation.max-y", 24);
        minDepth = Math.max(1, c.getInt("generation.min-depth-below-surface", 12));
        onlyNew = c.getBoolean("generation.only-new-chunks", true);
        loc = new BaseLocator(c.getInt("generation.cell-size", 350), c.getInt("generation.min-radius", 500),
                c.getInt("generation.max-radius", 3000), c.getDouble("generation.chance", 0.4), size,
                c.getLong("generation.salt", 0));
        String wm = c.getString("base.wall-material", "AUTO");
        Material w = wm.equalsIgnoreCase("AUTO") ? null : Material.matchMaterial(wm);
        wallMat = (w != null && w.isBlock()) ? w : null;
        Material f = Material.matchMaterial(c.getString("base.floor-material", "POLISHED_DEEPSLATE"));
        floorMat = (f != null && f.isBlock()) ? f : Material.STONE_BRICKS;
        loot.clear();
        for (String line : c.getStringList("base.loot")) {
            String[] p = line.split(";");
            Material m = p.length == 4 ? Material.matchMaterial(p[0].trim()) : null;
            if (m == null) {
                getLogger().warning("Ungueltiger Loot-Eintrag: " + line);
                continue;
            }
            try {
                loot.add(new BaseBuilder.Loot(m, Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()),
                        Double.parseDouble(p[3].trim())));
            } catch (NumberFormatException ex) {
                getLogger().warning("Ungueltiger Loot-Eintrag: " + line);
            }
        }
    }

    private static String key(String world, int chx, int chz) {
        return world + ";" + chx + ";" + chz;
    }

    private static Component col(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    // ---------- Speicher ----------

    private void loadBases() {
        bases.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (String k : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(k);
            if (s == null) continue;
            Base b = new Base();
            b.world = s.getString("world", "world");
            b.x = s.getInt("x");
            b.y = s.getInt("y");
            b.z = s.getInt("z");
            b.half = s.getInt("half", 4);
            b.height = s.getInt("height", 5);
            b.skipped = s.getBoolean("skipped");
            b.foundBy = s.getString("found");
            bases.put(key(b.world, b.x >> 4, b.z >> 4), b);
        }
    }

    private void saveBases() {
        YamlConfiguration y = new YamlConfiguration();
        int i = 0;
        for (Base b : bases.values()) {
            String p = "b" + (i++) + ".";
            y.set(p + "world", b.world);
            y.set(p + "x", b.x);
            y.set(p + "y", b.y);
            y.set(p + "z", b.z);
            y.set(p + "half", b.half);
            y.set(p + "height", b.height);
            y.set(p + "skipped", b.skipped);
            y.set(p + "found", b.foundBy);
        }
        try {
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("bases.yml konnte nicht gespeichert werden: " + e.getMessage());
        }
    }

    // ---------- Generierung ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        World w = e.getWorld();
        if (!worlds.contains(w.getName())) return;
        if (onlyNew && !e.isNewChunk()) return;
        int chx = e.getChunk().getX(), chz = e.getChunk().getZ();
        int[] p = loc.forChunk(w.getSeed(), chx, chz);
        if (p == null) return;
        String k = key(w.getName(), chx, chz);
        if (bases.containsKey(k) || !pending.add(k)) return;
        getServer().getScheduler().runTask(this, () -> {
            pending.remove(k);
            if (w.isChunkLoaded(chx, chz) && !bases.containsKey(k)) generate(w, k, p);
        });
    }

    private void generate(World w, String k, int[] p) {
        Base b = new Base();
        b.world = w.getName();
        b.x = p[0];
        b.z = p[1];
        b.half = size / 2;
        b.height = height;
        int h = b.half;
        int[][] pts = {{0, 0}, {-h, -h}, {h, -h}, {-h, h}, {h, h}};
        int surf = Integer.MAX_VALUE;
        for (int[] d : pts) {
            surf = Math.min(surf, w.getHighestBlockYAt(p[0] + d[0], p[1] + d[1], HeightMap.OCEAN_FLOOR));
        }
        int lo = Math.max(minY, w.getMinHeight() + 7);
        int hi = Math.min(maxY, surf - minDepth - height);
        if (hi < lo) {
            b.skipped = true;
        } else {
            Random r = new Random(w.getSeed() ^ (p[0] * 31L + p[1] * 17L));
            b.y = lo + r.nextInt(hi - lo + 1);
            Material wall = wallMat != null ? wallMat : (b.y < 0 ? Material.DEEPSLATE : Material.STONE);
            BaseBuilder.build(w, p[0], p[1], b.y, size, height, wall, floorMat, chests, loot, r);
        }
        bases.put(k, b);
        saveBases();
    }

    // ---------- Entdeckung ----------

    private void checkDiscovery() {
        FileConfiguration c = getConfig();
        if (!c.getBoolean("discovery.enabled", true)) return;
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            Location l = p.getLocation();
            Base b = bases.get(key(l.getWorld().getName(), l.getBlockX() >> 4, l.getBlockZ() >> 4));
            if (b == null || b.skipped || b.foundBy != null) continue;
            if (Math.abs(l.getBlockX() - b.x) > b.half || Math.abs(l.getBlockZ() - b.z) > b.half
                    || l.getBlockY() < b.y - 1 || l.getBlockY() > b.y + b.height) continue;
            b.foundBy = p.getName();
            saveBases();
            if (c.getBoolean("discovery.announce", true)) {
                getServer().broadcast(col(c.getString("discovery.message", "&6%player% &ehat eine Basis gefunden!")
                        .replace("%player%", p.getName())));
            }
            for (String cmd : c.getStringList("discovery.reward-commands")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
            }
        }
    }

    // ---------- Relog-Methode ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!getConfig().getBoolean("relog.enabled", true)) return;
        Player p = e.getPlayer();
        getServer().getScheduler().runTaskLater(this, () -> relogHint(p), getConfig().getLong("relog.delay-ticks", 40));
    }

    private void relogHint(Player p) {
        FileConfiguration c = getConfig();
        if (!p.isOnline() || !worlds.contains(p.getWorld().getName())) return;
        long now = System.currentTimeMillis();
        Long last = relogCd.get(p.getUniqueId());
        if (last != null && now - last < c.getLong("relog.cooldown-seconds", 5) * 1000L) return;
        relogCd.put(p.getUniqueId(), now);

        int radius = Math.max(16, c.getInt("relog.radius", 160));
        Location l = p.getLocation();
        int[] n = loc.nearest(p.getWorld().getSeed(), l.getBlockX(), l.getBlockZ(), radius);
        if (n == null) {
            String none = c.getString("relog.none", "");
            if (!none.isEmpty()) p.sendActionBar(col(none));
            return;
        }
        double dx = n[0] - l.getX(), dz = n[1] - l.getZ();
        double d = Math.hypot(dx, dz), ratio = d / radius;
        String msg = ratio < 0.33 ? c.getString("relog.close", "") : ratio < 0.66 ? c.getString("relog.near", "")
                : c.getString("relog.far", "");
        msg = msg.replace("%dir%", compass(dx, dz)).replace("%dist%", String.valueOf(Math.round(d / 10.0) * 10));
        if (!msg.isEmpty()) p.sendActionBar(col(msg));
        String snd = c.getString("relog.sound", "");
        if (!snd.isEmpty()) {
            try {
                p.playSound(net.kyori.adventure.sound.Sound.sound(Key.key(snd),
                        net.kyori.adventure.sound.Sound.Source.AMBIENT, (float) (1.0 - ratio * 0.8),
                        (float) (0.6 + (1.0 - ratio) * 0.8)), net.kyori.adventure.sound.Sound.Emitter.self());
            } catch (RuntimeException ex) {
                getLogger().warning("Ungueltiger Sound in relog.sound: " + snd);
            }
        }
    }

    private static String compass(double dx, double dz) {
        String[] names = {"N", "NO", "O", "SO", "S", "SW", "W", "NW"};
        double a = Math.toDegrees(Math.atan2(dx, -dz));
        if (a < 0) a += 360;
        return names[(int) Math.round(a / 45.0) % 8];
    }

    // ---------- Befehle ----------

    private List<Base> active() {
        List<Base> l = new ArrayList<>();
        for (Base b : bases.values()) if (!b.skipped) l.add(b);
        l.sort(Comparator.comparing((Base b) -> b.world).thenComparingInt(b -> b.x).thenComparingInt(b -> b.z));
        return l;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage("/bf reload | list | tp <nr> | near");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();
                loadSettings();
                prot.reload();
                s.sendMessage("BaseFinder: Config neu geladen.");
            }
            case "list" -> {
                List<Base> l = active();
                s.sendMessage("Generierte Basen: " + l.size());
                for (int i = 0; i < Math.min(l.size(), 25); i++) {
                    Base b = l.get(i);
                    s.sendMessage((i + 1) + ": " + b.world + " " + b.x + " " + b.y + " " + b.z
                            + (b.foundBy != null ? " (gefunden von " + b.foundBy + ")" : ""));
                }
            }
            case "tp" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage("Nur im Spiel nutzbar.");
                    return true;
                }
                List<Base> l = active();
                try {
                    Base b = l.get(Integer.parseInt(a[1]) - 1);
                    World w = Bukkit.getWorld(b.world);
                    if (w == null) throw new IllegalArgumentException();
                    p.teleport(new Location(w, b.x + 0.5, b.y, b.z + 0.5));
                } catch (RuntimeException ex) {
                    s.sendMessage("Nutzung: /bf tp <nr> (siehe /bf list)");
                }
            }
            case "near" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage("Nur im Spiel nutzbar.");
                    return true;
                }
                Base best = null;
                double bd = Double.MAX_VALUE;
                for (Base b : active()) {
                    if (!b.world.equals(p.getWorld().getName())) continue;
                    double d = Math.hypot(b.x - p.getLocation().getX(), b.z - p.getLocation().getZ());
                    if (d < bd) {
                        bd = d;
                        best = b;
                    }
                }
                s.sendMessage(best == null ? "Keine generierte Basis in dieser Welt."
                        : "Naechste Basis: " + best.x + " " + best.y + " " + best.z + " (" + Math.round(bd) + " Bloecke)");
            }
            default -> s.sendMessage("/bf reload | list | tp <nr> | near");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (a.length != 1) return List.of();
        List<String> out = new ArrayList<>();
        for (String o : List.of("reload", "list", "tp", "near")) if (o.startsWith(a[0].toLowerCase())) out.add(o);
        return out;
    }
}
