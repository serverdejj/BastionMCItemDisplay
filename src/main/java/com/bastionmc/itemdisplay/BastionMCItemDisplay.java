package com.bastionmc.itemdisplay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import com.google.common.collect.Multimap;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.*;

public final class BastionMCItemDisplay extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private NamespacedKey rarityKey;
    private final Map<UUID, TextDisplay> labels = new HashMap<>();
    private final Map<UUID, Boolean> oldGlow = new HashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private static final List<String> RARITIES = List.of("comun", "pococomun", "raro", "epico", "legendario", "mitico");
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacySection();

    @Override public void onEnable() {
        saveDefaultConfig();
        rarityKey = new NamespacedKey(this, "rarity");
        getServer().getPluginManager().registerEvents(this, this);
        for (String name : List.of("rareza", "itemdisplay")) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }
        }
        startTasks();
        getLogger().info("BastionMCItemDisplay beta habilitado. Probar en Purpur 1.21.11.");
    }
    @Override public void onDisable() { stopTasks(); clearLabels(); }
    private void stopTasks() { tasks.forEach(BukkitTask::cancel); tasks.clear(); }
    private void startTasks() {
        stopTasks();
        long labelsPeriod = Math.max(2L, getConfig().getLong("display.update-ticks", 5));
        long targetPeriod = Math.max(2L, getConfig().getLong("target-info.update-ticks", 5));
        long effectsPeriod = Math.max(5L, getConfig().getLong("effects.update-ticks", 20));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, this::updateLabels, 1L, labelsPeriod));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, this::updateTargets, 3L, targetPeriod));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, this::updateEffects, 10L, effectsPeriod));
    }
    private Component colored(String input) {
        Matcher matcher = HEX.matcher(input == null ? "" : input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder seq = new StringBuilder("§x");
            for (char c : hex.toCharArray()) seq.append('§').append(c);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(seq.toString()));
        }
        matcher.appendTail(buffer);
        return serializer.deserialize(ChatColor.translateAlternateColorCodes('&', buffer.toString()));
    }
    private void send(CommandSender sender, String path) { sender.sendMessage(colored(getConfig().getString("messages.prefix", "") + getConfig().getString("messages." + path, ""))); }
    private String customRarity(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        String value = stack.getItemMeta().getPersistentDataContainer().get(rarityKey, PersistentDataType.STRING);
        return RARITIES.contains(value) ? value : null;
    }
    private String rarity(ItemStack stack) {
        String custom = customRarity(stack);
        if (custom != null) return custom;
        // Reflectively read the native rarity if exposed by the current Paper API.
        for (Object source : new Object[]{stack, stack.getItemMeta(), stack.getType()}) {
            if (source == null) continue;
            for (String method : List.of("getRarity", "getItemRarity")) {
                try {
                    Method m = source.getClass().getMethod(method);
                    Object result = m.invoke(source);
                    if (result != null) {
                        String r = result.toString().toLowerCase(Locale.ROOT).replace("_", "");
                        if (r.equals("common")) return "comun";
                        if (r.equals("uncommon")) return "pococomun";
                        if (r.equals("rare")) return "raro";
                        if (r.equals("epic")) return "epico";
                    }
                } catch (ReflectiveOperationException | SecurityException ignored) { }
            }
        }
        // Fallback for servers whose API does not expose the rarity component.
        return switch (stack.getType()) {
            case TOTEM_OF_UNDYING, NETHER_STAR, ENCHANTED_GOLDEN_APPLE -> "pococomun";
            case END_CRYSTAL -> "raro";
            case DRAGON_EGG -> "epico";
            default -> "comun";
        };
    }
    private Component itemName(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) return meta.displayName();
        // Native item translation with vanilla rarity color, independent of custom rarity.
        String nativeRarity = nativeRarity(stack);
        NamedTextColor color = switch (nativeRarity) {
            case "pococomun" -> NamedTextColor.YELLOW;
            case "raro" -> NamedTextColor.AQUA;
            case "epico" -> NamedTextColor.LIGHT_PURPLE;
            default -> NamedTextColor.WHITE;
        };
        return Component.translatable(stack.getType().translationKey()).color(color);
    }
    private String nativeRarity(ItemStack stack) {
        // Custom PDC rarity must never alter the original item's name color.
        ItemStack clone = stack.clone();
        if (clone.hasItemMeta()) {
            ItemMeta meta = clone.getItemMeta();
            meta.getPersistentDataContainer().remove(rarityKey);
            clone.setItemMeta(meta);
        }
        return rarity(clone);
    }
    private Component label(ItemStack stack) {
        Component name = itemName(stack);
        if (getConfig().getBoolean("display.show-amount", true) && stack.getAmount() > 1)
            name = name.append(colored(getConfig().getString("display.amount-format", " &7x%amount%").replace("%amount%", Integer.toString(stack.getAmount()))));
        return name;
    }
    private boolean isNearby(Item item, double range) {
        double maxSq = range * range;
        for (Player player : item.getWorld().getPlayers())
            if (player.getLocation().distanceSquared(item.getLocation()) <= maxSq) return true;
        return false;
    }
    private void updateLabels() {
        if (!getConfig().getBoolean("display.enabled", true)) { clearLabels(); return; }
        Set<UUID> active = new HashSet<>();
        double range = getConfig().getDouble("display.max-distance", 32);
        for (World world : Bukkit.getWorlds()) for (Item item : world.getEntitiesByClass(Item.class)) {
            if (!item.isValid() || !isNearby(item, range)) continue;
            UUID id = item.getUniqueId(); active.add(id);
            TextDisplay display = labels.get(id);
            if (display == null || !display.isValid()) {
                display = world.spawn(item.getLocation(), TextDisplay.class, entity -> {
                    entity.setPersistent(false);
                    entity.setBillboard(Display.Billboard.CENTER);
                    entity.setShadowed(getConfig().getBoolean("display.shadow", true));
                    entity.setSeeThrough(getConfig().getBoolean("display.see-through", false));
                    entity.setDefaultBackground(false);
                    entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    entity.setInvulnerable(true);
                });
                labels.put(id, display);
            }
            display.teleport(item.getLocation().add(0, getConfig().getDouble("display.height-offset", 0.55), 0));
            display.text(label(item.getItemStack()));
        }
        for (UUID id : new HashSet<>(labels.keySet())) if (!active.contains(id)) removeLabel(id);
    }
    private void removeLabel(UUID id) {
        TextDisplay display = labels.remove(id);
        if (display != null && display.isValid()) display.remove();
    }
    private void clearLabels() { for (UUID id : new HashSet<>(labels.keySet())) removeLabel(id); }
    @EventHandler public void onItemDespawn(ItemDespawnEvent event) { removeLabel(event.getEntity().getUniqueId()); }
    @EventHandler public void onItemPickup(EntityPickupItemEvent event) { Bukkit.getScheduler().runTask(this, () -> {
        Item item = event.getItem(); if (!item.isValid()) removeLabel(item.getUniqueId());
    }); }
    private void updateEffects() {
        if (!getConfig().getBoolean("effects.enabled", true)) { restoreGlow(); return; }
        double range = getConfig().getDouble("effects.max-distance", 16);
        int count = Math.max(0, Math.min(20, getConfig().getInt("effects.particle-count", 3)));
        Set<UUID> seen = new HashSet<>();
        for (World world : Bukkit.getWorlds()) for (Item item : world.getEntitiesByClass(Item.class)) {
            if (!item.isValid()) continue;
            UUID id = item.getUniqueId();
            String key = rarity(item.getItemStack());
            boolean glow = getConfig().getBoolean("rarities." + key + ".glowing", false);
            if (glow) {
                seen.add(id);
                if (!oldGlow.containsKey(id)) oldGlow.put(id, item.isGlowing());
                item.setGlowing(true);
            } else if (oldGlow.containsKey(id)) restoreGlow(item);
            if (!isNearby(item, range) || count == 0 || !getConfig().getBoolean("rarities." + key + ".particles", false)) continue;
            try {
                String hex = getConfig().getString("rarities." + key + ".color", "#FFFFFF").replace("#", "");
                java.awt.Color rgb = java.awt.Color.decode("#" + hex);
                Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(rgb.getRed(), rgb.getGreen(), rgb.getBlue()), 1.0f);
                world.spawnParticle(Particle.DUST, item.getLocation().add(0, 0.18, 0), count, 0.18, 0.16, 0.18, 0, dust);
            } catch (IllegalArgumentException ignored) { }
        }
        for (UUID id : new HashSet<>(oldGlow.keySet())) if (!seen.contains(id)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof Item item) restoreGlow(item);
            else oldGlow.remove(id);
        }
    }
    private void restoreGlow(Item item) { Boolean prior = oldGlow.remove(item.getUniqueId()); if (prior != null && item.isValid()) item.setGlowing(prior); }
    private void restoreGlow() { for (UUID id : new HashSet<>(oldGlow.keySet())) {
        Entity e = Bukkit.getEntity(id); if (e instanceof Item item) restoreGlow(item); else oldGlow.remove(id);
    } }
    private void updateTargets() {
        if (!getConfig().getBoolean("target-info.enabled", true)) return;
        double maxDistance = Math.max(1, getConfig().getDouble("target-info.max-distance", 5));
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location eye = player.getEyeLocation(); Vector direction = eye.getDirection();
            RayTraceResult hit = player.getWorld().rayTraceEntities(eye, direction, maxDistance, 0.35,
                entity -> entity instanceof Item && entity.isValid());
            if (hit == null || !(hit.getHitEntity() instanceof Item item)) continue;
            ItemStack stack = item.getItemStack();
            Component info = Component.empty();
            boolean first = true;
            for (Component part : infoParts(stack)) {
                if (!first) info = info.append(colored(" &8| "));
                info = info.append(part); first = false;
            }
            if (!first) player.sendActionBar(info);
        }
    }
    private List<Component> infoParts(ItemStack stack) {
        List<Component> parts = new ArrayList<>();
        if (getConfig().getBoolean("target-info.show-name", true)) parts.add(itemName(stack));
        String r = rarity(stack);
        if (getConfig().getBoolean("target-info.show-rarity", true)) parts.add(colored(getConfig().getString("rarities." + r + ".label", r)));
        if (getConfig().getBoolean("target-info.show-enchantments", true) && !stack.getEnchantments().isEmpty()) {
            int max = Math.max(1, getConfig().getInt("target-info.max-enchantments", 2));
            StringJoiner join = new StringJoiner(", ");
            stack.getEnchantments().entrySet().stream().limit(max).forEach(e -> join.add(e.getKey().getKey().getKey() + " " + e.getValue()));
            if (stack.getEnchantments().size() > max) join.add("+" + (stack.getEnchantments().size() - max));
            parts.add(colored("&b" + join));
        }
        if (getConfig().getBoolean("target-info.show-combat-stats", true) && stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            Multimap<Attribute, AttributeModifier> modifiers = meta.getAttributeModifiers();
            if (modifiers != null && !modifiers.isEmpty()) {
                StringJoiner stats = new StringJoiner(", ");
                modifiers.entries().stream().limit(2).forEach(e -> stats.add(e.getKey().getKey().getKey() + " " + String.format(Locale.US, "%+.1f", e.getValue().getAmount())));
                parts.add(colored("&c" + stats));
            }
        }
        if (getConfig().getBoolean("target-info.show-amount", true)) parts.add(colored("&7x" + stack.getAmount()));
        return parts;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("bastionitemdisplay.admin")) { sender.sendMessage(colored("&cSin permiso.")); return true; }
        if (command.getName().equalsIgnoreCase("itemdisplay")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig(); restoreGlow(); clearLabels(); startTasks(); send(sender, "reloaded");
            } else sender.sendMessage(colored("&e/itemdisplay reload"));
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("item")) {
            sender.sendMessage(colored("&e/rareza item <comun|pococomun|raro|epico|legendario|mitico|reset>")); return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("Solo jugadores."); return true; }
        ItemStack stack = player.getInventory().getItemInMainHand();
        if (stack.getType().isAir()) { send(sender, "no-item"); return true; }
        String choice = args[1].toLowerCase(Locale.ROOT);
        if (!choice.equals("reset") && !RARITIES.contains(choice)) { send(sender, "invalid-rarity"); return true; }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) { send(sender, "no-item"); return true; }
        if (choice.equals("reset")) meta.getPersistentDataContainer().remove(rarityKey);
        else meta.getPersistentDataContainer().set(rarityKey, PersistentDataType.STRING, choice);
        stack.setItemMeta(meta);
        if (choice.equals("reset")) send(sender, "rarity-reset");
        else sender.sendMessage(colored(getConfig().getString("messages.prefix", "") + getConfig().getString("messages.rarity-set", "").replace("%rarity%", getConfig().getString("rarities." + choice + ".label", choice))));
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("bastionitemdisplay.admin")) return List.of();
        List<String> options;
        if (command.getName().equalsIgnoreCase("itemdisplay")) options = args.length == 1 ? List.of("reload") : List.of();
        else if (args.length == 1) options = List.of("item");
        else if (args.length == 2 && args[0].equalsIgnoreCase("item")) {
            options = new ArrayList<>(RARITIES); options.add("reset");
        } else options = List.of();
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.startsWith(typed)).toList();
    }
}
