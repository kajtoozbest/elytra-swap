package me.spearpearl;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SpearPearlLimiter extends JavaPlugin implements Listener {

    private final Map<UUID, Long> combat = new HashMap<>();
    private final Map<UUID, Long> lungeReady = new HashMap<>();
    private long tagMillis, lungeMillis;
    private int pearlTicks;
    private Enchantment lunge;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tagMillis = getConfig().getInt("tag-seconds", 15) * 1000L;
        lungeMillis = getConfig().getInt("lunge-cooldown-seconds", 10) * 1000L;
        pearlTicks = getConfig().getInt("pearl-cooldown-seconds", 10) * 20;
        lunge = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("lunge"));
        if (lunge == null) getLogger().warning("Lunge enchantment not found - spear limiting disabled.");
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private boolean inCombat(Player p) {
        Long until = combat.get(p.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    private void tag(Player p) {
        // put() overwrites, so every hit renews the full timer
        combat.put(p.getUniqueId(), System.currentTimeMillis() + tagMillis);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player p) attacker = p;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) attacker = p;
        if (attacker == null || attacker.equals(victim)) return;
        tag(victim);
        tag(attacker);
    }

    // ---- Ender pearls: 10s cooldown always ----
    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player p)) return;
        if (p.hasPermission("spearpearl.bypass")) return;
        // vanilla sets its own short cooldown after launch, so override next tick
        Bukkit.getScheduler().runTask(this, () -> p.setCooldown(Material.ENDER_PEARL, pearlTicks));
    }

    // ---- Lunge spear: unlimited outside combat, 1 per 10s in combat ----
    private boolean hasLungeSpear(Player p) {
        if (lunge == null) return false;
        ItemStack it = p.getInventory().getItemInMainHand();
        return it.getType().name().endsWith("_SPEAR") && it.getEnchantmentLevel(lunge) > 0;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwing(PlayerArmSwingEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("spearpearl.bypass")) return;
        if (!hasLungeSpear(p) || !inCombat(p)) return;

        long now = System.currentTimeMillis();
        Long ready = lungeReady.get(p.getUniqueId());
        if (ready == null || now >= ready) {
            lungeReady.put(p.getUniqueId(), now + lungeMillis);
            // gray draining square over the spear for the cooldown time
            p.setCooldown(p.getInventory().getItemInMainHand(), (int) (lungeMillis / 50));
            return;
        }

        e.setCancelled(true);
        long left = (ready - now + 999) / 1000;
        p.sendActionBar(Component.text("Lunge on cooldown: " + left + "s", NamedTextColor.RED));

        // backup: cancel any dash that still happened (horizontal velocity only)
        Vector before = p.getVelocity();
        for (int delay = 1; delay <= 2; delay++) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Vector v = p.getVelocity();
                p.setVelocity(new Vector(before.getX(), v.getY(), before.getZ()));
            }, delay);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        combat.remove(e.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        combat.remove(e.getPlayer().getUniqueId());
        lungeReady.remove(e.getPlayer().getUniqueId());
    }
}
