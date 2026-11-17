package de.basefinder;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Sperrt Spectator-Tricks und erkennt X-Ray-Verhalten (nur Bukkit/Paper-API, Aternos-tauglich). */
final class ProtectionListener implements Listener {
    private static final class Hist {
        final ArrayDeque<long[]> q = new ArrayDeque<>();
        int total, rare;
        long lastAlert;
    }

    private final BaseFinderPlugin pl;
    private final Set<Material> rare = new HashSet<>();
    private final Map<UUID, Hist> hist = new HashMap<>();
    private boolean blockSpec, xray;
    private long windowMs, alertCdMs;
    private int minRare;
    private double ratio;
    private String punish;

    ProtectionListener(BaseFinderPlugin pl) {
        this.pl = pl;
        reload();
    }

    void reload() {
        FileConfiguration c = pl.getConfig();
        blockSpec = c.getBoolean("protection.block-spectator", true);
        xray = c.getBoolean("protection.xray-detection", true);
        windowMs = c.getInt("protection.window-seconds", 300) * 1000L;
        alertCdMs = c.getInt("protection.alert-cooldown-seconds", 60) * 1000L;
        minRare = c.getInt("protection.min-rare", 5);
        ratio = c.getDouble("protection.ratio", 0.10);
        punish = c.getString("protection.punish-command", "");
        rare.clear();
        for (String s : c.getStringList("protection.xray-materials")) {
            Material m = Material.matchMaterial(s);
            if (m != null) rare.add(m);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        if (blockSpec && e.getNewGameMode() == GameMode.SPECTATOR && !e.getPlayer().hasPermission("basefinder.bypass")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (blockSpec && e.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE
                && !e.getPlayer().hasPermission("basefinder.bypass")) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (blockSpec && p.getGameMode() == GameMode.SPECTATOR && !p.hasPermission("basefinder.bypass")) {
            p.setGameMode(GameMode.SURVIVAL);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        hist.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!xray) return;
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL || p.hasPermission("basefinder.bypass")) return;
        long now = System.currentTimeMillis();
        Hist h = hist.computeIfAbsent(p.getUniqueId(), k -> new Hist());
        while (!h.q.isEmpty() && now - h.q.peekFirst()[0] > windowMs) {
            long[] o = h.q.pollFirst();
            h.total--;
            if (o[1] == 1) h.rare--;
        }
        boolean isRare = rare.contains(e.getBlock().getType());
        h.q.addLast(new long[]{now, isRare ? 1 : 0});
        h.total++;
        if (isRare) h.rare++;
        if (!isRare || h.rare < minRare || (double) h.rare / h.total < ratio || now - h.lastAlert < alertCdMs) return;
        h.lastAlert = now;
        String msg = "§c[BaseFinder] §f" + p.getName() + " §7hat " + h.rare + " seltene Erze bei " + h.total
                + " abgebauten Blöcken (X-Ray-Verdacht).";
        for (Player a : Bukkit.getOnlinePlayers()) {
            if (a.hasPermission("basefinder.alert")) a.sendMessage(msg);
        }
        pl.getLogger().warning(msg.replaceAll("§.", ""));
        if (punish != null && !punish.isBlank()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), punish.replace("%player%", p.getName()));
        }
    }
}
