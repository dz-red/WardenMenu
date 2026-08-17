package fun.wardensmp.menu;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * WardenMenu — хаб-плагин: /menu с вложенными GUI. Config-driven (все меню/кнопки/ссылки в config.yml).
 * Кнопка = подменю / команда / кликабельная ссылка / своя статистика / топ.
 * Топы кешируются по таймеру (не грузят ТПС). Без текстурпака.
 */
public class WardenMenu extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<String, MenuDef> menus = new HashMap<>();
    private Economy econ;
    private int topsRefreshMin, topSize;
    private final Map<String, List<TopEntry>> tops = new HashMap<>(); // kills/playtime/deaths/balance

    static final class MenuDef { String title; int rows; final Map<Integer, ItemDef> items = new HashMap<>(); }
    static final class ItemDef { Material mat; String name; List<String> lore; String action; }
    record TopEntry(String name, double value) {}

    static final class MenuHolder implements InventoryHolder {
        final String id; Inventory inv;
        MenuHolder(String id) { this.id = id; }
        @Override public Inventory getInventory() { return inv; }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        loadMenus();
        hookVault();
        getCommand("menu").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        long every = Math.max(1, topsRefreshMin) * 60L * 20L;
        getServer().getScheduler().runTaskTimer(this, this::refreshTops, 100L, every);
        getLogger().info("WardenMenu on. меню=" + menus.size() + " vault=" + (econ != null));
    }

    private void loadMenus() {
        menus.clear();
        var cfg = getConfig();
        topsRefreshMin = cfg.getInt("tops.refresh-minutes", 10);
        topSize = cfg.getInt("tops.size", 10);
        ConfigurationSection ms = cfg.getConfigurationSection("menus");
        if (ms == null) return;
        for (String id : ms.getKeys(false)) {
            ConfigurationSection m = ms.getConfigurationSection(id);
            if (m == null) continue;
            MenuDef def = new MenuDef();
            def.title = m.getString("title", "Menu");
            def.rows = Math.max(1, Math.min(6, m.getInt("rows", 3)));
            ConfigurationSection items = m.getConfigurationSection("items");
            if (items != null) for (String slotKey : items.getKeys(false)) {
                int slot; try { slot = Integer.parseInt(slotKey); } catch (NumberFormatException ex) { continue; }
                ConfigurationSection it = items.getConfigurationSection(slotKey);
                if (it == null) continue;
                ItemDef d = new ItemDef();
                Material mat = Material.matchMaterial(it.getString("material", "STONE"));
                d.mat = mat != null ? mat : Material.STONE;
                d.name = it.getString("name", " ");
                d.lore = it.getStringList("lore");
                d.action = it.getString("action", null);
                def.items.put(slot, d);
            }
            menus.put(id, def);
        }
    }

    private void hookVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) econ = rsp.getProvider();
    }

    private static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }

    // ---------- open ----------
    private void open(Player p, String id) {
        MenuDef def = menus.get(id);
        if (def == null) { p.sendMessage("§cМеню не найдено: " + id); return; }
        MenuHolder h = new MenuHolder(id);
        Inventory inv = Bukkit.createInventory(h, def.rows * 9, color(def.title));
        h.inv = inv;
        for (var e : def.items.entrySet()) {
            if (e.getKey() < 0 || e.getKey() >= inv.getSize()) continue;
            inv.setItem(e.getKey(), build(e.getValue()));
        }
        p.openInventory(inv);
    }

    private ItemStack build(ItemDef d) {
        ItemStack it = new ItemStack(d.mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(d.name));
            if (d.lore != null && !d.lore.isEmpty()) {
                List<String> lore = new ArrayList<>();
                for (String l : d.lore) lore.add(color(l));
                meta.setLore(lore);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    // ---------- command ----------
    @Override public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Только игрок."); return true; }
        open(p, a.length > 0 ? a[0].toLowerCase() : "main");
        return true;
    }

    // ---------- clicks ----------
    @EventHandler public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder h)) return;
        e.setCancelled(true); // наше меню — ничего не вынести/переложить
        if (!(e.getClickedInventory() != null && e.getClickedInventory().getHolder() instanceof MenuHolder)) return;
        MenuDef def = menus.get(h.id);
        if (def == null) return;
        ItemDef it = def.items.get(e.getSlot());
        if (it == null || it.action == null) return;
        dispatch((Player) e.getWhoClicked(), it.action);
    }

    @EventHandler public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof MenuHolder) e.setCancelled(true);
    }

    private void dispatch(Player p, String action) {
        int i = action.indexOf(':');
        String type = i < 0 ? action : action.substring(0, i);
        String arg = i < 0 ? "" : action.substring(i + 1);
        switch (type.toLowerCase()) {
            case "submenu" -> open(p, arg.toLowerCase());
            case "command" -> { p.closeInventory(); p.performCommand(arg); }
            case "link" -> { p.closeInventory(); sendLink(p, arg); }
            case "stats" -> { p.closeInventory(); sendStats(p); }
            case "top" -> { p.closeInventory(); sendTop(p, arg.toLowerCase()); }
            case "close" -> p.closeInventory();
            default -> { /* text/none */ }
        }
    }

    private void sendLink(Player p, String url) {
        TextComponent msg = new TextComponent("§7» ");
        TextComponent link = new TextComponent("§b" + url);
        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        msg.addExtra(link);
        msg.addExtra(new TextComponent("§8  (жми чтобы открыть)"));
        p.spigot().sendMessage(msg);
    }

    // ---------- статистика игрока ----------
    private void sendStats(Player p) {
        long kills = p.getStatistic(Statistic.PLAYER_KILLS);
        long mobKills = p.getStatistic(Statistic.MOB_KILLS);
        long deaths = p.getStatistic(Statistic.DEATHS);
        long ticks = p.getStatistic(Statistic.PLAY_ONE_MINUTE);
        p.sendMessage("§8§m--------§r §6§lТвоя статистика §8§m--------");
        p.sendMessage("§7Убийства игроков: §f" + kills);
        p.sendMessage("§7Убийства мобов: §f" + mobKills);
        p.sendMessage("§7Смертей: §f" + deaths);
        p.sendMessage("§7К/Д: §f" + (deaths == 0 ? kills + ".0" : String.format("%.2f", (double) kills / deaths)));
        p.sendMessage("§7Наиграно: §f" + fmtTime(ticks));
    }

    private static String fmtTime(long ticks) {
        long minutes = ticks / 20 / 60;
        long h = minutes / 60, m = minutes % 60;
        return h + "ч " + m + "м";
    }

    // ---------- топы ----------
    private void sendTop(Player p, String kind) {
        List<TopEntry> list = tops.get(kind);
        String title = switch (kind) {
            case "kills" -> "§cТоп по убийствам игроков"; case "playtime" -> "§eТоп по онлайну";
            case "deaths" -> "§8Топ по смертям"; case "balance" -> "§6Топ по балансу"; default -> "§7Топ"; };
        p.sendMessage("§8§m------§r " + title + " §8§m------");
        if (list == null || list.isEmpty()) { p.sendMessage("§7Данные ещё считаются, загляни через минуту."); return; }
        int n = 1;
        for (TopEntry e : list) {
            String val = switch (kind) {
                case "playtime" -> fmtTime((long) e.value());
                case "balance" -> econ != null ? econ.format(e.value()) : String.valueOf((long) e.value());
                default -> String.valueOf((long) e.value()); };
            p.sendMessage(" §6#" + n++ + " §f" + e.name() + " §7— §f" + val);
        }
    }

    private void refreshTops() {
        List<TopEntry> kills = new ArrayList<>(), playtime = new ArrayList<>(), deaths = new ArrayList<>(), balance = new ArrayList<>();
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            String name = op.getName();
            if (name == null) continue;
            long pt;
            try { pt = op.getStatistic(Statistic.PLAY_ONE_MINUTE); } catch (Exception ex) { continue; }
            if (pt <= 0) continue; // никогда не играл — пропускаем
            long k = safeStat(op, Statistic.PLAYER_KILLS);
            long d = safeStat(op, Statistic.DEATHS);
            if (k > 0) kills.add(new TopEntry(name, k));
            if (d > 0) deaths.add(new TopEntry(name, d));
            playtime.add(new TopEntry(name, pt));
            if (econ != null) { double bal = econ.getBalance(op); if (bal > 0) balance.add(new TopEntry(name, bal)); }
        }
        tops.put("kills", trim(kills));
        tops.put("playtime", trim(playtime));
        tops.put("deaths", trim(deaths));
        tops.put("balance", trim(balance));
    }

    private long safeStat(OfflinePlayer op, Statistic s) { try { return op.getStatistic(s); } catch (Exception e) { return 0; } }

    private List<TopEntry> trim(List<TopEntry> l) {
        l.sort((a, b) -> Double.compare(b.value(), a.value()));
        return new ArrayList<>(l.subList(0, Math.min(topSize, l.size())));
    }
}
