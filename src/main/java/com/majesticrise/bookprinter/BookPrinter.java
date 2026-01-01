package com.majesticrise.bookprinter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;

public final class BookPrinter extends JavaPlugin implements CommandExecutor, TabCompleter {

    private static final String LATEST_CONFIG_VERSION = "2.0";
    private LanguageManager languageManager;

    @Override
    public void onEnable() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        ensureLanguageFilesExist();
        saveDefaultConfig();
        checkConfigUpdate();
        reloadConfig();

        this.languageManager = new LanguageManager(this);
        languageManager.load();

        PluginCommand cmd = getCommand("bookprinter");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        getLogger().info(languageManager.getRaw("log_plugin_enabled"));
    }

    private void ensureLanguageFilesExist() {
        if (new File(getDataFolder(), "Language-zh_CN.yml").exists()) {
            saveResource("Language-zh_CN.yml", true);
        } else {
            saveResource("Language-zh_CN.yml", false);
        }

        if (new File(getDataFolder(), "Language-en_US.yml").exists()) {
            saveResource("Language-en_US.yml", true);
        } else {
            saveResource("Language-en_US.yml", false);
        }
    }

    private void checkConfigUpdate() {
        File currentConfigFile = new File(getDataFolder(), "config.yml");
        if (!currentConfigFile.exists()) return;

        try {
            FileConfiguration currentConfig = YamlConfiguration.loadConfiguration(currentConfigFile);
            String currentVersion = currentConfig.getString("config_version", "1.0");

            if (!LATEST_CONFIG_VERSION.equals(currentVersion)) {
                File backupFile = new File(getDataFolder(), "config_old_" + System.currentTimeMillis() + ".yml");
                if (currentConfigFile.renameTo(backupFile)) {
                    saveDefaultConfig();
                    String msg = languageManager.getRaw("log_config_updated");
                    getLogger().warning(msg);
                    reloadConfig();
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to check config version", e);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String @NotNull [] args) {

        if (args.length >= 1) {
            if (args[0].equalsIgnoreCase("reload")) {
                if (!checkPermission(sender, "bookprinter.reload")) return true;
                reloadConfig();
                checkConfigUpdate();
                languageManager.reload();
                sender.sendMessage(languageManager.get("reload_success"));
                return true;
            }

            if (args[0].equalsIgnoreCase("info")) {
                if (!checkPermission(sender, "bookprinter.info")) return true;
                sendInfo(sender);
                return true;
            }
        }

        if (!checkPermission(sender, "bookprinter.use")) return true;

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        final ConfigurationSection config = getConfig();
        final String mode = config.getString("Switch-mode", "classic").toLowerCase(Locale.ROOT);
        final long maxSizeBytes = config.getLong("max_file_bytes", 2097152);
        final boolean isClassic = "classic".equals(mode);

        String rawInput = args[0];
        final String fileName = rawInput.toLowerCase(Locale.ROOT).endsWith(".txt")
                ? rawInput
                : rawInput + ".txt";

        if (fileName.contains("../")) {
            sender.sendMessage(languageManager.get("path_invalid"));
            return true;
        }

        if (isClassic) {
            ConfigurationSection classicCfg = config.getConfigurationSection("classic");
            boolean allowSubdirs = classicCfg != null && classicCfg.getBoolean("allow_subdirs", false);
            if (!allowSubdirs && (fileName.contains("/") || fileName.contains("\\"))) {
                sender.sendMessage(languageManager.get("path_no_subdir"));
                return true;
            }
        }

        String potentialAuthor = null;
        UUID potentialUuid = null;

        if (args.length >= 2) {
            potentialAuthor = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        } else if (sender instanceof Player p) {
            potentialAuthor = p.getName();
            potentialUuid = p.getUniqueId();
        }

        if (potentialAuthor == null) {
            sender.sendMessage(languageManager.get("usage_main"));
            return true;
        }

        final String author = potentialAuthor.replaceAll("[\r\n]", " ").trim();
        final String finalAuthor = author.length() > 32 ? author.substring(0, 32) : author;
        final UUID playerUuid = potentialUuid;

        File userFile = new File(fileName);
        if (!userFile.isAbsolute()) {
            userFile = new File(getDataFolder(), fileName);
        } else {
            boolean allowAbs = isClassic && config.getConfigurationSection("classic") != null &&
                    config.getConfigurationSection("classic").getBoolean("allow_absolute_paths", false);
            if (!allowAbs) {
                sender.sendMessage(languageManager.get("path_no_absolute"));
                return true;
            }
        }

        final File targetFile;
        try {
            File dataFolderCanonical = getDataFolder().getCanonicalFile();
            File targetFileCanonical = userFile.getCanonicalFile();

            if (!targetFileCanonical.toPath().startsWith(dataFolderCanonical.toPath())) {
                sender.sendMessage(languageManager.get("path_invalid"));
                String path = userFile.getPath();
                String logMsg = languageManager.getRaw("log_path_denied", Map.of("path", path != null ? path : "unknown"));
                getLogger().warning(logMsg);
                return true;
            }
            targetFile = targetFileCanonical;
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Path parsing error", e);
            sender.sendMessage(languageManager.get("path_invalid"));
            return true;
        }

        sender.sendMessage(languageManager.get("start_generating"));

        Bukkit.getAsyncScheduler().runNow(this, (task) -> {
            try {
                handleGenerationAsync(sender, targetFile, finalAuthor, mode, maxSizeBytes);
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, languageManager.getRaw("log_task_exception"), e);
                scheduleGlobal(() -> sender.sendMessage(languageManager.get("internal_error")));
            }
        });

        return true;
    }

    private void sendInfo(CommandSender sender) {
        ConfigurationSection config = getConfig();
        long maxBytes = config.getLong("max_file_bytes", 2097152);
        // 字节转MB显示
        double mb = maxBytes / (1024.0 * 1024.0);
        String sizeStr = String.format("%.2f MB", mb);

        String mode = config.getString("Switch-mode", "classic");
        String lang = config.getString("language", "zh_CN");
        String version = Bukkit.getServer().getName().contains("Folia") ? (getDescription().getVersion() + " (Folia)") : getDescription().getVersion();

        sender.sendMessage(languageManager.get("info_header"));
        sender.sendMessage(languageManager.get("info_mode", Map.of("mode", mode)));
        sender.sendMessage(languageManager.get("info_lang", Map.of("lang", lang)));
        sender.sendMessage(languageManager.get("info_max_bytes", Map.of("size", sizeStr)));
        sender.sendMessage(languageManager.get("info_version", Map.of("version", version)));
        sender.sendMessage(languageManager.get("info_footer"));
    }

    private void handleGenerationAsync(CommandSender sender, File file, String author, String mode, long limit) {
        try {
            if (!file.exists() || !file.isFile()) {
                scheduleGlobal(() -> sender.sendMessage(languageManager.get("file_not_found")));
                return;
            }

            long size = Files.size(file.toPath());
            if (size > limit) {
                var map = Map.of("size", String.valueOf(size), "limit", String.valueOf(limit));
                scheduleGlobal(() -> sender.sendMessage(languageManager.get("file_too_large", map)));
                return;
            }

            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            List<Component> pages;

            try {
                pages = "modern".equals(mode)
                        ? TextUtils.parseModernMode(content, getConfig())
                        : TextUtils.parseClassicMode(content, getConfig());
            } catch (Exception e) {
                String fname = file.getName();
                String msg = languageManager.getRaw("log_parse_error", Map.of("mode", mode, "file", fname != null ? fname : "unknown"));
                getLogger().severe(msg);
                getLogger().log(Level.SEVERE, "Parse details", e);
                scheduleGlobal(() -> sender.sendMessage(languageManager.get("internal_error")));
                return;
            }

            if (sender instanceof Player player) {
                Location loc = player.getLocation();
                Bukkit.getRegionScheduler().execute(this, loc, () -> giveBookToPlayer(player, file.getName(), author, pages));
            } else {
                String pageCount = String.valueOf(pages.size());
                scheduleGlobal(() -> sender.sendMessage(languageManager.get("console_generated", Map.of("pages", pageCount))));
            }

        } catch (IOException e) {
            String fname = file.getName();
            String msg = languageManager.getRaw("log_io_error", Map.of("file", fname != null ? fname : "unknown"));
            getLogger().log(Level.WARNING, msg, e);
            scheduleGlobal(() -> sender.sendMessage(languageManager.get("io_error")));
        }
    }

    private void giveBookToPlayer(Player player, String fileName, String author, List<Component> pages) {
        if (!player.isOnline()) {
            String name = player.getName();
            getLogger().info(languageManager.getRaw("log_player_offline", Map.of("name", name)));
            return;
        }

        try {
            ItemStack book = createBookItem(fileName, author, pages);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(book);

            if (leftover.isEmpty()) {
                player.sendMessage(languageManager.get("success"));
                var map = Map.of("file", fileName, "pages", String.valueOf(pages.size()));
                player.sendMessage(languageManager.get("success_detail", map));
            } else {
                player.getWorld().dropItemNaturally(player.getLocation(), book);
                player.sendMessage(languageManager.get("inventory_full"));
            }

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, languageManager.getRaw("log_give_error"), e);
            player.sendMessage(languageManager.get("internal_error"));
        }
    }

    private ItemStack createBookItem(String fileName, String author, List<Component> pages) {
        ItemStack book = new ItemStack(org.bukkit.Material.WRITTEN_BOOK, 1);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) return book;

        String title = TextUtils.extractTitleFromFileName(fileName);
        meta.title(LegacyComponentSerializer.legacySection().deserialize(title));
        meta.author(LegacyComponentSerializer.legacySection().deserialize(author));
        meta.pages(pages);
        book.setItemMeta(meta);
        return book;
    }

    private boolean checkPermission(CommandSender sender, String perm) {
        if (sender.hasPermission(perm)) return true;
        sender.sendMessage(languageManager.get("no_permission"));
        return false;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(languageManager.get("usage_main"));
        String mode = getConfig().getString("Switch-mode", "classic");
        sender.sendMessage(languageManager.get("usage_mode", Map.of("mode", mode)));
    }

    private void scheduleGlobal(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getGlobalRegionScheduler().execute(this, task);
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String @NotNull [] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);

            if ("reload".startsWith(input)) completions.add("reload");
            if ("info".startsWith(input)) completions.add("info");

            File dir = getDataFolder();
            if (dir.exists()) {
                File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".txt"));
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName();
                        if (input.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(input)) {
                            completions.add(name);
                        }
                    }
                }
            }
        }

        return completions;
    }
}
