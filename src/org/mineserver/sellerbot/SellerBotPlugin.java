package org.mineserver.sellerbot;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class SellerBotPlugin extends JavaPlugin implements Listener {

    private FakeNPC fakeNpc;
    private Economy economy;
    private int currentPage = 0;
    private long nextRotationTime;
    private static final long CYCLE_MS = 4L * 60 * 60 * 1000;

    private File dataFile;
    private FileConfiguration dataConfig;

    private final List<List<ShopItem>> pages = new ArrayList<>();
    private int[][] soldCounts = new int[4][9];
    private boolean balanceBookAvailable;
    private static final int BALANCE_BOOK_PAGE = 3;
    private static final int BALANCE_BOOK_SLOT = 4;

    // ==================== ENABLE / DISABLE ====================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        buildPages();
        loadData();

        if (!setupEconomy()) {
            getLogger().severe("Vault/Economy не найден! Плагин отключён.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        setupNpcInteractListener();
        getServer().getScheduler().runTaskLater(this, this::spawnOrFindBot, 10L);
        startCycleTimer();
        startRespawnTicker();
        getLogger().info("SellerBot включён. Текущая страница: " + (currentPage + 1));
    }

    @Override
    public void onDisable() {
        if (fakeNpc != null) fakeNpc.despawnForAll();
        saveData();
        getLogger().info("SellerBot отключён, данные сохранены.");
    }

    private void setupNpcInteractListener() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
            new PacketAdapter(this, ListenerPriority.NORMAL, PacketType.Play.Client.USE_ENTITY) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    if (fakeNpc == null || !fakeNpc.isCreated()) return;
                    int entityId = event.getPacket().getIntegers().read(0);
                    if (entityId != fakeNpc.getEntityId()) return;
                    event.setCancelled(true);
                    Player player = event.getPlayer();
                    getServer().getScheduler().runTask(SellerBotPlugin.this, () -> {
                        if (player.isOnline()) player.openInventory(buildGui());
                    });
                }
            });
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    // ==================== СТРАНИЦЫ ====================

    private void buildPages() {
        // --- Страница 1 ---
        List<ShopItem> p1 = new ArrayList<>();
        p1.add(new ShopItem(Material.SHULKER_SHELL,       "Панцирь шалкера",              2,   75.0,  4));
        p1.add(new ShopItem(Material.TURTLE_HELMET,       "Черепаший панцирь",             1,  600.0,  1));
        p1.add(new ShopItem(Material.TOTEM_OF_UNDYING,    "Тотем бессмертия",              1,   50.0,  4));
        p1.add(enchBook("Тягун III (Книга)",              Enchantment.LOYALTY,             3,  500.0,  1));
        p1.add(new ShopItem(Material.ZOMBIE_HEAD,         "Голова зомби",                  1,   40.0,  2));
        p1.add(new ShopItem(Material.SKELETON_SKULL,      "Череп скелета",                 1,   40.0,  2));
        p1.add(new ShopItem(Material.IRON_HORSE_ARMOR,    "Железная конская броня",        1,   80.0,  3));
        p1.add(enchBook("Острота V (Книга)",              Enchantment.DAMAGE_ALL,          5,  700.0,  1));
        p1.add(new ShopItem(Material.NETHER_STAR,         "Звезда Незера",                 1,  400.0,  2));
        pages.add(p1);

        // --- Страница 2 ---
        List<ShopItem> p2 = new ArrayList<>();
        p2.add(new ShopItem(Material.GOLDEN_HORSE_ARMOR,  "Золотая конская броня",         1,  100.0,  4));
        p2.add(new ShopItem(Material.CRYING_OBSIDIAN,     "Плачущий обсидиан",            16,  150.0,  4));
        p2.add(new ShopItem(Material.WITHER_SKELETON_SKULL, "Череп визер-скелета",         1,   40.0,  2));
        p2.add(enchBook("Ледоход II (Книга)",             Enchantment.FROST_WALKER,        2,  600.0,  1));
        p2.add(new ShopItem(Material.EXPERIENCE_BOTTLE,   "Пузырёк опыта",                32,   80.0,  6));
        p2.add(new ShopItem(Material.SADDLE,              "Седло",                         1,  100.0,  4));
        p2.add(new ShopItem(Material.CREEPER_HEAD,        "Голова крипера",                1,   40.0,  2));
        p2.add(enchBook("Громовержец (Книга)",            Enchantment.CHANNELING,          1,  400.0,  1));
        p2.add(new ShopItem(Material.HEART_OF_THE_SEA,    "Сердце моря",                   1,  250.0,  2));
        pages.add(p2);

        // --- Страница 3 ---
        List<ShopItem> p3 = new ArrayList<>();
        p3.add(new ShopItem(Material.END_CRYSTAL,         "Кристалл Энда",                 1,  700.0,  2));
        p3.add(new ShopItem(Material.DIAMOND_HORSE_ARMOR, "Алмазная конская броня",        1,  150.0,  2));
        p3.add(new ShopItem(Material.PIGLIN_HEAD,         "Голова пиглина",                1,  100.0,  2));
        p3.add(enchBook("Удача III (Книга)",              Enchantment.LOOT_BONUS_BLOCKS,   3, 1000.0,  1));
        p3.add(new ShopItem(Material.TRIDENT,             "Трезубец",                      1,  350.0,  1));
        p3.add(new ShopItem(Material.DRAGON_HEAD,         "Голова дракона",                1,  150.0,  1));
        p3.add(enchBook("Починка (Книга)",                Enchantment.MENDING,             1,  550.0,  1));
        p3.add(new ShopItem(Material.ELYTRA,              "Элитры",                        1,  750.0,  1));
        p3.add(new ShopItem(Material.ENCHANTED_GOLDEN_APPLE, "Зач. золотое яблоко",        2,   75.0,  5));
        pages.add(p3);

        // --- Страница 4 ---
        List<ShopItem> p4 = new ArrayList<>();
        p4.add(new ShopItem(Material.SPONGE,              "Губка",                        3,   50.0,  3));
        p4.add(new ShopItem("TS_HEATER_1", Material.IRON_INGOT,   "Грелка I",             1,   10.0,  5));
        p4.add(enchBook("Добыча III (Книга)",             Enchantment.LOOT_BONUS_MOBS,    3,  300.0,  1));
        p4.add(new ShopItem(Material.CHORUS_FRUIT,        "Плод хоруса",                  6,  120.0,  2));
        p4.add(new ShopItem("TS_BALANCE_BOOK", Material.ENCHANTED_BOOK, "Книга Равновесия", 1, 5000.0, 1));
        p4.add(new ShopItem("TS_THERMOS", Material.SHEARS,         "Термос",               1,   30.0,  6));
        p4.add(new ShopItem(Material.MUSIC_DISC_11,       "Пластинка C418-11",            1, 3000.0,  1));
        p4.add(enchBook("Шёлковое касание (Книга)",       Enchantment.SILK_TOUCH,         1, 1200.0,  1));
        p4.add(new ShopItem(Material.SCULK_SENSOR,        "Скалковый сенсор",             2,   15.0,  3));
        pages.add(p4);
    }

    /** Создаёт ShopItem-обёртку для зачарованной книги */
    private ShopItem enchBook(String displayName, Enchantment ench, int level,
                              double price, int maxBuys) {
        return new ShopItem(Material.ENCHANTED_BOOK, displayName, 1, price, maxBuys, ench, level);
    }

    // ==================== ДАННЫЕ ====================

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            currentPage = 0;
            nextRotationTime = getBuyerNextRotation() + 2L * 60 * 60 * 1000;
            soldCounts = new int[4][9];
            balanceBookAvailable = false;
            return;
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        currentPage = dataConfig.getInt("current-page", 0);
        nextRotationTime = dataConfig.getLong("next-rotation", 0L);
        balanceBookAvailable = dataConfig.getBoolean("balance-book-available", false);
        if (currentPage != BALANCE_BOOK_PAGE) balanceBookAvailable = false;
        if (nextRotationTime <= System.currentTimeMillis()) {
            nextRotationTime = getBuyerNextRotation() + 2L * 60 * 60 * 1000;
        }
        soldCounts = new int[4][9];
        for (int pg = 0; pg < 4; pg++) {
            for (int sl = 0; sl < 9; sl++) {
                soldCounts[pg][sl] = dataConfig.getInt("sold." + pg + "." + sl, 0);
            }
        }
    }

    private void saveData() {
        if (dataConfig == null) dataConfig = new YamlConfiguration();
        dataConfig.set("current-page", currentPage);
        dataConfig.set("next-rotation", nextRotationTime);
        dataConfig.set("balance-book-available", balanceBookAvailable);
        for (int pg = 0; pg < 4; pg++) {
            for (int sl = 0; sl < 9; sl++) {
                dataConfig.set("sold." + pg + "." + sl, soldCounts[pg][sl]);
            }
        }
        try { dataConfig.save(dataFile); } catch (IOException e) { e.printStackTrace(); }
    }

    // ==================== НПС ====================

    private void spawnOrFindBot() {
        String worldName = getConfig().getString("bot-location.world", "");
        if (worldName.isEmpty()) {
            getLogger().info("SellerBot: позиция не задана. Используйте /sellerbot spawn.");
            return;
        }
        World world = getServer().getWorld(worldName);
        if (world == null) {
            getLogger().warning("Мир '" + worldName + "' не найден! Используйте /sellerbot spawn.");
            return;
        }
        double x = getConfig().getDouble("bot-location.x", 0.5);
        double y = getConfig().getDouble("bot-location.y", 64);
        double z = getConfig().getDouble("bot-location.z", 0.5);
        float yaw = (float) getConfig().getDouble("bot-location.yaw", 0.0);
        String skinTex = getConfig().getString("skin.texture", null);
        String skinSig = getConfig().getString("skin.signature", null);
        createNPCAt(new Location(world, x, y, z, yaw, 0), skinTex, skinSig);
    }

    private void createNPCAt(Location loc, String skinTex, String skinSig) {
        if (fakeNpc != null) fakeNpc.despawnForAll();
        fakeNpc = new FakeNPC(this, loc);
        if (skinTex != null && skinSig != null) fakeNpc.setSkin(skinTex, skinSig);
        fakeNpc.create();
        fakeNpc.spawnForAll();
        getLogger().info("SellerBot: NPC создан на " + loc.getWorld().getName() +
            " x=" + (int)loc.getX() + " y=" + (int)loc.getY() + " z=" + (int)loc.getZ());
    }

    // ==================== РОТАЦИЯ ====================

    /** Читает наступающее время ротации BuyerBot из его data.yml. */
    private long getBuyerNextRotation() {
        File buyerData = new File(getDataFolder().getParentFile(), "BuyerBot/data.yml");
        if (buyerData.exists()) {
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(buyerData);
            long val = cfg.getLong("next-rotation", 0L);
            if (val > System.currentTimeMillis()) return val;
        }
        // BuyerBot ещё не сохранял данные или время устарело — оценка
        return System.currentTimeMillis() + CYCLE_MS;
    }

    private void startCycleTimer() {
        new BukkitRunnable() {
            @Override public void run() {
                if (System.currentTimeMillis() >= nextRotationTime) rotatePage();
            }
        }.runTaskTimer(this, 20L * 60, 20L * 60);
    }

    private void startRespawnTicker() {
        new BukkitRunnable() {
            @Override public void run() {
                if (fakeNpc == null || !fakeNpc.isCreated()) return;
                Location npcLoc = fakeNpc.getLocation();
                if (npcLoc.getWorld() == null) return;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!p.getWorld().getName().equals(fakeNpc.getLocationWorld())) {
                        fakeNpc.forgetPlayer(p.getUniqueId());
                        continue;
                    }
                    double distSq = p.getLocation().distanceSquared(npcLoc);
                    if (distSq <= 64 * 64) {
                        fakeNpc.spawnFor(p); // нет эффекта если уже заспавнен
                    } else if (distSq > 80 * 80) {
                        fakeNpc.forgetPlayer(p.getUniqueId()); // вышел за радиус — забыть
                    }
                }
            }
        }.runTaskTimer(SellerBotPlugin.this, 20L * 5, 20L * 5); // каждые 5 секунд
    }

    private void rotatePage() {
        currentPage = (currentPage + 1) % 4;
        soldCounts[currentPage] = new int[9];
        nextRotationTime = getBuyerNextRotation() + 2L * 60 * 60 * 1000;
        if (currentPage == BALANCE_BOOK_PAGE) {
            balanceBookAvailable = Math.random() < 0.33;
        } else {
            balanceBookAvailable = false;
        }
        saveData();

        String[] names = {"Страница 1 (Редкие предметы)", "Страница 2 (Ресурсы и зелья)",
                          "Страница 3 (Легендарные вещи)", "Страница 4 (Утилиты)"};
        getServer().broadcastMessage("");
        getServer().broadcastMessage(ChatColor.GOLD + "╔══════════════════════════════╗");
        getServer().broadcastMessage(ChatColor.GOLD + "║  " + ChatColor.YELLOW + "🛒 Продавец обновил каталог!  " + ChatColor.GOLD + "║");
        getServer().broadcastMessage(ChatColor.GOLD + "║  " + ChatColor.WHITE + "Активна: " + ChatColor.GREEN + names[currentPage] + ChatColor.GOLD + "  ║");
        getServer().broadcastMessage(ChatColor.GOLD + "║  " + ChatColor.GRAY + "Подойдите к Продавцу на спавне" + ChatColor.GOLD + "  ║");
        getServer().broadcastMessage(ChatColor.GOLD + "╚══════════════════════════════╝");
        if (currentPage == BALANCE_BOOK_PAGE && balanceBookAvailable) {
            getServer().broadcastMessage("");
            getServer().broadcastMessage(ChatColor.LIGHT_PURPLE + "✦ " + ChatColor.YELLOW + "РЕДКОСТЬ! "
                + ChatColor.WHITE + "Книга Равновесия" + ChatColor.GRAY + " появилась у Продавца!"
                + ChatColor.GRAY + " (шанс 33%)");
            getServer().broadcastMessage(ChatColor.LIGHT_PURPLE + "✦ " + ChatColor.GRAY + "Только 1 штука — спешите!");
        }
        getServer().broadcastMessage("");
        getLogger().info("SellerBot: страница сменена на " + (currentPage + 1)
            + ". Книга Равновесия: " + (currentPage == BALANCE_BOOK_PAGE
                ? (balanceBookAvailable ? "ДОСТУПНА" : "не появилась") : "н/д"));
    }

    // ==================== GUI ====================

    private Inventory buildGui() {
        String title = ChatColor.DARK_AQUA + "Продавец" + ChatColor.GRAY + " — Стр. " + (currentPage + 1) + "/4";
        Inventory inv = getServer().createInventory(null, 9, title);
        List<ShopItem> page = pages.get(currentPage);
        for (int i = 0; i < 9; i++) inv.setItem(i, buildSlot(page.get(i), i));
        return inv;
    }

    private ItemStack buildSlot(ShopItem item, int slot) {
        boolean isBalanceBook = (currentPage == BALANCE_BOOK_PAGE && slot == BALANCE_BOOK_SLOT);
        int remaining = item.maxBuys - soldCounts[currentPage][slot];
        ItemStack stack = createItemStack(item);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) meta = getServer().getItemFactory().getItemMeta(stack.getType());
        List<String> lore = new ArrayList<>();

        if (isBalanceBook && !balanceBookAvailable) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "✦ " + item.displayName + ChatColor.DARK_GRAY + " [СЕКРЕТ]");
            lore.add(ChatColor.RED + "🔒 Редкий товар");
            lore.add(ChatColor.GRAY + "Шанс появления: " + ChatColor.YELLOW + "33%" + ChatColor.GRAY + " за цикл");
            lore.add(ChatColor.GRAY + "Цена при появлении: " + ChatColor.YELLOW + "$" + (int)item.price);
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "Не появилась в этот цикл");
            lore.add(ChatColor.DARK_GRAY + "Следующая смена через 4ч");
        } else if (remaining > 0) {
            meta.setDisplayName(ChatColor.AQUA + item.displayName);
            lore.add(ChatColor.GRAY + "Количество: " + ChatColor.WHITE + item.amount + " шт.");
            lore.add(ChatColor.GRAY + "Цена: " + ChatColor.YELLOW + "$" + item.price);
            lore.add(ChatColor.GRAY + "Осталось: " + ChatColor.GREEN + remaining
                + ChatColor.DARK_GRAY + "/" + item.maxBuys);
            if (isBalanceBook) {
                lore.add("");
                lore.add(ChatColor.LIGHT_PURPLE + "✦ Редкий товар (шанс 33%/цикл)");
            }
            lore.add("");
            lore.add(ChatColor.GREEN + "▶  Нажмите чтобы купить");
        } else {
            meta.setDisplayName(ChatColor.RED + item.displayName + ChatColor.DARK_RED + " [РАСПРОДАНО]");
            lore.add(ChatColor.GRAY + "Количество: " + ChatColor.WHITE + item.amount + " шт.");
            lore.add(ChatColor.GRAY + "Цена: " + ChatColor.YELLOW + "$" + item.price);
            lore.add(ChatColor.RED + "✗ Распродано до следующей смены!");
            lore.add(ChatColor.GRAY + "Обновится через 4 часа");
        }
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    /** Создаёт ItemStack: ToughSurvival предметы через рефлексию, обычные и зачарованные книги напрямую. */
    private ItemStack createItemStack(ShopItem item) {
        if (item.tsItemType != null) {
            org.bukkit.plugin.Plugin tsPlugin = getServer().getPluginManager().getPlugin("ToughSurvival");
            if (tsPlugin != null) {
                try {
                    java.lang.reflect.Method method = null;
                    Object[] args = null;
                    switch (item.tsItemType) {
                        case "TS_HEATER_1":
                            method = tsPlugin.getClass().getDeclaredMethod("makeHeater", int.class);
                            args = new Object[]{1};
                            break;
                        case "TS_THERMOS":
                            method = tsPlugin.getClass().getDeclaredMethod("makeThermos", int.class, boolean.class);
                            args = new Object[]{0, false};
                            break;
                        case "TS_BALANCE_BOOK":
                            method = tsPlugin.getClass().getDeclaredMethod("makeBalanceBook");
                            args = new Object[]{};
                            break;
                    }
                    if (method != null) {
                        method.setAccessible(true);
                        ItemStack result = (ItemStack) method.invoke(tsPlugin, args);
                        if (result != null) return result.clone();
                    }
                } catch (Exception e) {
                    getLogger().warning("SellerBot: не удалось создать TS предмет '" + item.tsItemType + "': " + e.getMessage());
                }
            }
            return new ItemStack(item.material, item.amount);
        }
        ItemStack stack = new ItemStack(item.material, item.amount);
        if (item.material == Material.ENCHANTED_BOOK && item.enchantment != null) {
            EnchantmentStorageMeta esm = (EnchantmentStorageMeta) stack.getItemMeta();
            esm.addStoredEnchant(item.enchantment, item.enchantLevel, true);
            stack.setItemMeta(esm);
        }
        return stack;
    }

    // ==================== СОБЫТИЯ ====================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (fakeNpc == null || !fakeNpc.isCreated()) return;
        Player p = event.getPlayer();
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) fakeNpc.spawnFor(p);
        }, 20L);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (fakeNpc == null || !fakeNpc.isCreated()) return;
        Player p = event.getPlayer();
        fakeNpc.forgetPlayer(p.getUniqueId());
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) fakeNpc.spawnFor(p);
        }, 20L);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (fakeNpc == null || !fakeNpc.isCreated()) return;
        Player p = event.getPlayer();
        fakeNpc.forgetPlayer(p.getUniqueId());
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && p.getWorld().getName().equals(fakeNpc.getLocationWorld()))
                fakeNpc.spawnFor(p);
        }, 60L);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        String title = event.getView().getTitle();
        if (!title.contains("Продавец")) return;
        event.setCancelled(true);

        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(event.getView().getTopInventory())) return;

        int slot = event.getSlot();
        if (slot < 0 || slot >= 9) return;

        Player player = (Player) event.getWhoClicked();
        ShopItem item = pages.get(currentPage).get(slot);

        boolean isBalanceBook = (currentPage == BALANCE_BOOK_PAGE && slot == BALANCE_BOOK_SLOT);
        if (isBalanceBook && !balanceBookAvailable) {
            player.sendMessage(ChatColor.LIGHT_PURPLE + "✦ " + ChatColor.RED
                + "Книга Равновесия не появилась в этот цикл! Шанс — 33% каждые 4 часа.");
            return;
        }

        int remaining = item.maxBuys - soldCounts[currentPage][slot];
        if (remaining <= 0) {
            player.sendMessage(ChatColor.RED + "✗ Товар '" + item.displayName + "' распродан до смены каталога!");
            return;
        }

        if (!economy.has(player, item.price)) {
            player.sendMessage(ChatColor.RED + "✗ Недостаточно средств! Нужно: "
                + ChatColor.YELLOW + "$" + item.price
                + ChatColor.RED + ", у вас: "
                + ChatColor.YELLOW + "$" + String.format("%.2f", economy.getBalance(player)));
            return;
        }

        // Проверка места в инвентаре
        ItemStack toGive = createItemStack(item);
        if (!hasInventorySpace(player.getInventory(), toGive)) {
            player.sendMessage(ChatColor.RED + "✗ В вашем инвентаре нет места!");
            return;
        }

        economy.withdrawPlayer(player, item.price);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(toGive);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
        soldCounts[currentPage][slot]++;
        saveData();

        player.sendMessage(ChatColor.GREEN + "✔ Куплено: " + ChatColor.WHITE + item.amount + "x " + item.displayName
            + ChatColor.GREEN + " за " + ChatColor.YELLOW + "$" + item.price
            + ChatColor.GREEN + ". Баланс: " + ChatColor.YELLOW + "$"
            + String.format("%.2f", economy.getBalance(player)));

        // Обновить GUI
        List<ShopItem> page = pages.get(currentPage);
        for (int i = 0; i < 9; i++) {
            event.getView().getTopInventory().setItem(i, buildSlot(page.get(i), i));
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTitle().contains("Продавец")) event.setCancelled(true);
    }

    // ==================== УТИЛИТЫ ====================

    private boolean hasInventorySpace(PlayerInventory inv, ItemStack stack) {
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null) return true;
            if (s.isSimilar(stack) && s.getAmount() + stack.getAmount() <= s.getMaxStackSize()) return true;
        }
        return false;
    }

    // ==================== КОМАНДЫ ====================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("sellerbot")) return false;
        if (!sender.hasPermission("sellerbot.admin")) {
            sender.sendMessage(ChatColor.RED + "Нет прав.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "/sellerbot spawn — создать бота здесь");
            sender.sendMessage(ChatColor.YELLOW + "/sellerbot skin <ник> — установить скин");
            sender.sendMessage(ChatColor.YELLOW + "/sellerbot page <1-3> — переключить страницу");
            sender.sendMessage(ChatColor.YELLOW + "/sellerbot rotate — принудительно сменить");
            sender.sendMessage(ChatColor.YELLOW + "/sellerbot info — состояние");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "spawn":
                if (!(sender instanceof Player)) { sender.sendMessage("Только для игроков."); return true; }
                Player sp = (Player) sender;
                Location spLoc = sp.getLocation();
                getConfig().set("bot-location.world", spLoc.getWorld().getName());
                getConfig().set("bot-location.x", spLoc.getX());
                getConfig().set("bot-location.y", spLoc.getY());
                getConfig().set("bot-location.z", spLoc.getZ());
                getConfig().set("bot-location.yaw", (double) spLoc.getYaw());
                saveConfig();
                createNPCAt(spLoc,
                    getConfig().getString("skin.texture"),
                    getConfig().getString("skin.signature"));
                sender.sendMessage(ChatColor.GREEN + "Продавец создан! Установи скин: /sellerbot skin <ник>");
                return true;

            case "skin":
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "/sellerbot skin <ник>"); return true; }
                String skinTarget = args[1];
                if (!skinTarget.matches("[a-zA-Z0-9_]{1,16}")) {
                    sender.sendMessage(ChatColor.RED + "Неверный ник."); return true;
                }
                sender.sendMessage(ChatColor.YELLOW + "Загружаю скин игрока " + skinTarget + "...");
                fetchSkinAsync(sender, skinTarget);
                return true;

            case "page":
                if (args.length < 2) { sender.sendMessage("Укажите номер страницы 1-4"); return true; }
                try {
                    int pg = Integer.parseInt(args[1]) - 1;
                    if (pg < 0 || pg > 3) { sender.sendMessage("Страница 1-4"); return true; }
                    currentPage = pg;
                    nextRotationTime = getBuyerNextRotation() + 2L * 60 * 60 * 1000;
                    saveData();
                    sender.sendMessage(ChatColor.GREEN + "Страница переключена на " + (currentPage + 1));
                } catch (NumberFormatException e) { sender.sendMessage("Укажите число 1-3"); }
                return true;

            case "rotate":
                rotatePage();
                sender.sendMessage(ChatColor.GREEN + "Страница сменена принудительно.");
                return true;

            case "info":
                long ms = nextRotationTime - System.currentTimeMillis();
                long h = ms / 3600000, m = (ms % 3600000) / 60000;
                sender.sendMessage(ChatColor.YELLOW + "Страница: " + (currentPage + 1) + "/4");
                if (currentPage == BALANCE_BOOK_PAGE)
                    sender.sendMessage(ChatColor.YELLOW + "Книга Равновесия: " + (balanceBookAvailable ? ChatColor.GREEN + "ДОСТУПНА" : ChatColor.RED + "не появилась"));
                sender.sendMessage(ChatColor.YELLOW + "До смены: " + h + "ч " + m + "мин");
                List<ShopItem> page = pages.get(currentPage);
                for (int sl = 0; sl < 9; sl++) {
                    ShopItem it = page.get(sl);
                    int rem = it.maxBuys - soldCounts[currentPage][sl];
                    sender.sendMessage(ChatColor.GRAY + "  [" + sl + "] " + it.displayName + ": " + rem + "/" + it.maxBuys);
                }
                return true;
        }
        return false;
    }

    // ==================== SKIN FETCH ====================

    private void fetchSkinAsync(CommandSender sender, String username) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                URL url1 = new URL("https://api.mojang.com/users/profiles/minecraft/" + username);
                HttpURLConnection c1 = (HttpURLConnection) url1.openConnection();
                c1.setRequestProperty("Accept-Encoding", "identity");
                c1.setRequestProperty("User-Agent", "SellerBot/1.0");
                c1.setConnectTimeout(5000);
                c1.setReadTimeout(5000);
                if (c1.getResponseCode() != 200) {
                    runSync(sender, ChatColor.RED + "Игрок '" + username + "' не найден.");
                    c1.disconnect(); return;
                }
                String resp1 = new String(c1.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
                c1.disconnect();
                if (!resp1.contains("\"id\"")) {
                    runSync(sender, ChatColor.RED + "Не удалось получить UUID для '" + username + "'.");
                    return;
                }
                String rawUUID = resp1.split("\"id\"\\s*:\\s*\"")[1].split("\"")[0];
                String uuid = rawUUID.replaceAll("(.{8})(.{4})(.{4})(.{4})(.+)", "$1-$2-$3-$4-$5");

                URL url2 = new URL("https://sessionserver.mojang.com/session/minecraft/profile/"
                    + uuid + "?unsigned=false");
                HttpURLConnection c2 = (HttpURLConnection) url2.openConnection();
                c2.setRequestProperty("Accept-Encoding", "identity");
                c2.setRequestProperty("User-Agent", "SellerBot/1.0");
                c2.setConnectTimeout(5000);
                c2.setReadTimeout(5000);
                if (c2.getResponseCode() != 200) {
                    runSync(sender, ChatColor.RED + "Не удалось загрузить скин."); c2.disconnect(); return;
                }
                String resp2 = new String(c2.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
                c2.disconnect();
                if (!resp2.contains("\"value\"")) {
                    runSync(sender, ChatColor.RED + "У игрока '" + username + "' нет скина."); return;
                }
                String texture = resp2.split("\"value\"\\s*:\\s*\"")[1].split("\"")[0];
                String signature = resp2.contains("\"signature\"")
                    ? resp2.split("\"signature\"\\s*:\\s*\"")[1].split("\"")[0] : null;

                getServer().getScheduler().runTask(this, () -> {
                    getConfig().set("skin.texture", texture);
                    getConfig().set("skin.signature", signature);
                    saveConfig();
                    if (fakeNpc != null && fakeNpc.isCreated()) {
                        fakeNpc.setSkin(texture, signature);
                        sender.sendMessage(ChatColor.GREEN + "✔ Скин '" + username + "' установлен!");
                    } else {
                        sender.sendMessage(ChatColor.YELLOW + "Скин сохранён. Создайте NPC: /sellerbot spawn");
                    }
                });
            } catch (Exception e) {
                runSync(sender, ChatColor.RED + "Ошибка загрузки скина: " + e.getMessage());
            }
        });
    }

    private void runSync(CommandSender target, String msg) {
        getServer().getScheduler().runTask(this, () -> target.sendMessage(msg));
    }

    // ==================== ShopItem ====================

    public static class ShopItem {
        public final Material material;
        public final String displayName;
        public final int amount;
        public final double price;
        public final int maxBuys;
        public final Enchantment enchantment; // null для обычных предметов
        public final int enchantLevel;
        public final String tsItemType; // null = обычный; "TS_HEATER_1", "TS_THERMOS", "TS_BALANCE_BOOK"

        /** Обычный предмет */
        public ShopItem(Material material, String displayName, int amount, double price, int maxBuys) {
            this.material = material;
            this.displayName = displayName;
            this.amount = amount;
            this.price = price;
            this.maxBuys = maxBuys;
            this.enchantment = null;
            this.enchantLevel = 0;
            this.tsItemType = null;
        }

        /** Зачарованная книга */
        public ShopItem(Material material, String displayName, int amount, double price,
                        int maxBuys, Enchantment enchantment, int enchantLevel) {
            this.material = material;
            this.displayName = displayName;
            this.amount = amount;
            this.price = price;
            this.maxBuys = maxBuys;
            this.enchantment = enchantment;
            this.enchantLevel = enchantLevel;
            this.tsItemType = null;
        }

        /** ToughSurvival предмет (Material — иконка-фоллбэк если плагин недоступен) */
        public ShopItem(String tsItemType, Material fallback, String displayName, int amount, double price, int maxBuys) {
            this.material = fallback;
            this.displayName = displayName;
            this.amount = amount;
            this.price = price;
            this.maxBuys = maxBuys;
            this.enchantment = null;
            this.enchantLevel = 0;
            this.tsItemType = tsItemType;
        }
    }
}
