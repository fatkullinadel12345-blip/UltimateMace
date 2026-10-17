package ru.itzrealdead.ultimatemace;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public final class UltimateMace extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private NamespacedKey uniqueKey, splitKey, recipeKey;
    private final Map<UUID, UUID> tpaRequests = new HashMap<>();
    private long unlockAt;
    private BukkitTask announceTask;

    @Override public void onEnable() {
        saveDefaultConfig();
        uniqueKey = new NamespacedKey(this, "unique_dragon_mace");
        splitKey = new NamespacedKey(this, "split_mace");
        recipeKey = new NamespacedKey(this, "dragon_mace_recipe");
        loadUnlockTime();
        getServer().getPluginManager().registerEvents(this, this);
        for (String cmd : List.of("fly","spawn","ivent","tpa","tpaccept","mace","seed")) {
            PluginCommand c = getCommand(cmd);
            if (c != null) { c.setExecutor(this); c.setTabCompleter(this); }
        }
        if (getConfig().getBoolean("mace-recipe", true)) registerMaceRecipe();
        announceTask = getServer().getScheduler().runTaskTimer(this, () -> {
            if (System.currentTimeMillis() >= unlockAt) {
                if (getConfig().getBoolean("end-locked-message", false)) {
                    Bukkit.broadcastMessage("§aЭнд открыт!");
                }
                if (announceTask != null) announceTask.cancel();
            }
        }, 20L, 20L * 30);
        getLogger().info("UltimateMace enabled. End unlock: " + new Date(unlockAt));
    }

    @Override public void onDisable() { if (announceTask != null) announceTask.cancel(); }

    private void loadUnlockTime() {
        File f = new File(getDataFolder(), "end-unlock.txt");
        if (f.exists()) {
            try { unlockAt = Long.parseLong(Files.readString(f.toPath()).trim()); return; }
            catch (Exception ex) { getLogger().warning("Invalid end-unlock.txt; starting timer again."); }
        }
        unlockAt = System.currentTimeMillis() + getConfig().getLong("end-delay-hours", 72) * 3_600_000L;
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            Files.writeString(f.toPath(), Long.toString(unlockAt));
        } catch (IOException e) { getLogger().severe("Could not persist End timer: " + e.getMessage()); }
    }

    private boolean isAdmin(CommandSender sender) {
        return sender instanceof Player p && p.getName().equalsIgnoreCase(getConfig().getString("admin-name", "ItzRealDead"));
    }
    private boolean requireAdmin(CommandSender s) {
        if (!isAdmin(s)) { s.sendMessage("§cЭта команда доступна только ItzRealDead."); return false; }
        return true;
    }

    private ItemStack createMace(boolean split, boolean breach) {
        ItemStack item = new ItemStack(Material.MACE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(split ? (breach ? "§6§lВласть Дракона §7— Пробитие" : "§5§lВласть Дракона §7— Плотность")
                                  : "§6§lВЛАСТЬ ДРАКОНА");
        meta.setLore(split
                ? List.of("§7Осколок легендарной булавы", "§8Можно передавать игрокам")
                : List.of("§7Единственная легендарная булава сервера", "§eИспользуй §f/mace split §eдля разделения"));
        if (split) {
            meta.getPersistentDataContainer().set(splitKey, PersistentDataType.STRING, breach ? "breach" : "density");
        } else {
            meta.getPersistentDataContainer().set(uniqueKey, PersistentDataType.BYTE, (byte) 1);
        }
        if (!split || breach) meta.addEnchant(Enchantment.BREACH, 4, true);
        if (!split || !breach) meta.addEnchant(Enchantment.DENSITY, 5, true);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);
        meta.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
        if (!split || !breach) meta.addEnchant(Enchantment.WIND_BURST, 1, true);
        item.setItemMeta(meta);
        return item;
    }

    private void registerMaceRecipe() {
        NamespacedKey key = new NamespacedKey(this, "dragon_authority_mace");
        Bukkit.removeRecipe(key);
        ShapedRecipe recipe = new ShapedRecipe(key, createMace(false, false));
        recipe.shape("NNN", "NEN", "NNN");
        recipe.setIngredient('N', Material.NETHERITE_INGOT);
        recipe.setIngredient('E', Material.DRAGON_EGG);
        Bukkit.addRecipe(recipe);
    }

    @EventHandler public void onCraft(CraftItemEvent event) {
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType() != Material.MACE) return;
        ItemMeta m = result.getItemMeta();
        if (m != null && m.getPersistentDataContainer().has(uniqueKey, PersistentDataType.BYTE)) {
            // Crafting one is permitted only if no unique mace currently exists in inventories/worlds.
            if (hasUniqueMace()) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player p) p.sendMessage("§cУникальная булава уже существует на сервере!");
            }
        }
    }

    private boolean hasUniqueMace() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (containsUnique(p.getInventory().getContents()) || containsUnique(p.getEnderChest().getContents())) return true;
        }
        for (World w : Bukkit.getWorlds()) {
            for (org.bukkit.entity.Entity e : w.getEntities()) {
                if (e instanceof org.bukkit.entity.Item item && isUnique(item.getItemStack())) return true;
            }
        }
        // Persisted flag prevents a second craft if the original was put in a chest or lost.
        return getConfig().getBoolean("unique-mace-ever-crafted", false);
    }
    private boolean containsUnique(ItemStack[] items) {
        for (ItemStack i : items) if (isUnique(i)) return true;
        return false;
    }
    private boolean isUnique(ItemStack item) {
        if (item == null || item.getType() != Material.MACE || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(uniqueKey, PersistentDataType.BYTE);
    }

    @EventHandler public void onCrafted(CraftItemEvent event) {
        ItemStack result = event.getCurrentItem();
        if (result != null && isUnique(result)) {
            getConfig().set("unique-mace-ever-crafted", true);
            saveConfig();
        }
    }

    @EventHandler public void onPortal(PlayerPortalEvent event) {
        if (System.currentTimeMillis() < unlockAt && event.getTo() != null
                && event.getTo().getWorld().getEnvironment() == World.Environment.THE_END) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§5Энд закрыт ещё на §d" + formatRemaining(unlockAt - System.currentTimeMillis()) + "§5.");
        }
    }

    @EventHandler public void onChangedWorld(PlayerChangedWorldEvent event) {
        if (System.currentTimeMillis() < unlockAt && event.getPlayer().getWorld().getEnvironment() == World.Environment.THE_END) {
            event.getPlayer().teleport(event.getFrom().getSpawnLocation());
            event.getPlayer().sendMessage("§cЭнд пока закрыт.");
        }
    }

    private String formatRemaining(long ms) {
        long sec = Math.max(0, ms / 1000), d = sec / 86400, h = (sec % 86400) / 3600, m = (sec % 3600) / 60;
        return d + "д " + h + "ч " + m + "м";
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String c = command.getName().toLowerCase(Locale.ROOT);
        if (c.equals("tpa")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Только игрок."); return true; }
            if (args.length != 1) { p.sendMessage("§eИспользование: /tpa <игрок>"); return true; }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null || target.equals(p)) { p.sendMessage("§cИгрок не найден или указан ты сам."); return true; }
            tpaRequests.put(target.getUniqueId(), p.getUniqueId());
            target.sendMessage("§e" + p.getName() + " §fхочет телепортироваться к тебе. §a/tpaccept");
            p.sendMessage("§aЗапрос отправлен игроку " + target.getName() + ".");
            return true;
        }
        if (c.equals("tpaccept")) {
            if (!(sender instanceof Player p)) return true;
            UUID requesterId = tpaRequests.remove(p.getUniqueId());
            Player requester = requesterId == null ? null : Bukkit.getPlayer(requesterId);
            if (requester == null) { p.sendMessage("§cНет активных запросов."); return true; }
            requester.teleport(p.getLocation());
            requester.sendMessage("§aТелепортация выполнена.");
            p.sendMessage("§aЗапрос принят.");
            return true;
        }
        if (c.equals("mace")) {
            if (!(sender instanceof Player p)) return true;
            if (args.length != 1 || !args[0].equalsIgnoreCase("split")) { p.sendMessage("§eИспользование: /mace split"); return true; }
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (!isUnique(hand)) { p.sendMessage("§cДержи уникальную булаву в основной руке."); return true; }
            hand.setAmount(0);
            HashMap<Integer, ItemStack> extra = p.getInventory().addItem(createMace(true, true), createMace(true, false));
            for (ItemStack leftover : extra.values()) p.getWorld().dropItemNaturally(p.getLocation(), leftover);
            getConfig().set("unique-mace-ever-crafted", true);
            saveConfig();
            p.sendMessage("§aБулава разделена на две. Их можно передавать другим игрокам.");
            return true;
        }
        if (c.equals("seed")) {
            if (!requireAdmin(sender)) return true;
            if (sender instanceof Player p) p.sendMessage("§aSeed мира: §f" + p.getWorld().getSeed());
            return true;
        }
        if (c.equals("fly")) {
            if (!requireAdmin(sender)) return true;
            if (!(sender instanceof Player p)) { sender.sendMessage("Только игрок."); return true; }
            boolean enable = !p.getAllowFlight();
            p.setAllowFlight(enable); p.setFlying(enable);
            p.sendMessage(enable ? "§aПолёт включён." : "§cПолёт выключен.");
            return true;
        }
        if (c.equals("spawn")) {
            if (!requireAdmin(sender)) return true;
            if (!(sender instanceof Player p)) { sender.sendMessage("Только игрок."); return true; }
            p.teleport(p.getWorld().getSpawnLocation()); p.sendMessage("§aТелепортация на спавн.");
            return true;
        }
        if (c.equals("ivent")) {
            if (!requireAdmin(sender)) return true;
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig(); sender.sendMessage("§aUltimateMace: конфигурация перезагружена."); return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
                sender.sendMessage(System.currentTimeMillis() >= unlockAt ? "§aЭнд открыт." : "§5До открытия Энда: " + formatRemaining(unlockAt - System.currentTimeMillis()));
                return true;
            }
            sender.sendMessage("§e/ivent status §7— статус Энда; §e/ivent reload §7— перезагрузить конфиг.");
            return true;
        }
        return false;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("ivent") && args.length == 1 && isAdmin(sender)) return List.of("status", "reload");
        if (command.getName().equalsIgnoreCase("tpa") && args.length == 1) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        return Collections.emptyList();
    }
}
