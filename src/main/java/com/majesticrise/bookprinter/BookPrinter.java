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
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class BookPrinter extends JavaPlugin implements CommandExecutor, TabCompleter {
    // 默认保守值（config 可覆盖）
    private static final int DEFAULT_MAX_CHARS = 256;
    private static final int DEFAULT_MAX_LINES = 14; // 仅在 lines 策略下使用
    private static final int MAX_TITLE_LENGTH = 32;
    private static final int MAX_AUTHOR_LENGTH = 32;
    private static final long DEFAULT_MAX_FILE_BYTES = 2L * 1024 * 1024; // 默认最大文件 2MB，配置可覆盖
    // 解析颜色/格式化代码的 serializer（使用 § 风格）
    private static final LegacyComponentSerializer COMPONENT_SERIALIZER = LegacyComponentSerializer.legacySection();

    // 预编译正则
    private static final Pattern TRAILING_NEWLINES = Pattern.compile("[\\n\\r]+$");

    @Override
    public void onEnable() {
        File data = getDataFolder();
        if (!data.exists() && !data.mkdirs()) {
            getLogger().log(Level.SEVERE, "无法创建插件数据目录: " + data.getAbsolutePath());
        }

        saveDefaultConfig();

        PluginCommand cmd = getCommand("bookprinter");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        } else {
            getLogger().warning("命令 bookprinter 未在 plugin.yml 中注册。");
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String @NotNull [] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("bookprinter.reload")) {
                sender.sendMessage("§c你没有权限重载配置。");
                return true;
            }
            reloadConfig();
            sender.sendMessage("§aBookPrinter 配置已重载。");
            return true;
        }

        if (!sender.hasPermission("bookprinter.use")) {
            sender.sendMessage("§c你没有权限使用此命令。");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§e用法: /bookprinter <文件名或绝对路径> [署名]");
            sender.sendMessage("§e可通过 plugins/BookPrinter/config.yml 调整分页配置，或使用 /bookprinter reload 重载。");
            return true;
        }

        // 配置选项
        int maxCharsPerPage = getConfig().getInt("max_chars_per_page", DEFAULT_MAX_CHARS);
        String splitStrategy = Optional.ofNullable(getConfig().getString("split_strategy")).orElse("smart").toLowerCase(Locale.ROOT);
        String pageMarker = Optional.ofNullable(getConfig().getString("page_marker")).orElse("---PAGE---");
        int maxLinesPerPage = getConfig().getInt("max_lines_per_page", DEFAULT_MAX_LINES);
        boolean preserveNewlines = getConfig().getBoolean("preserve_newlines", true);
        boolean allowAbsolute = getConfig().getBoolean("allow_absolute_paths", false);
        boolean allowSubdirs = getConfig().getBoolean("allow_subdirs", false);
        boolean trimTrailingEmptyPages = getConfig().getBoolean("trim_trailing_empty_pages", false);
        long maxFileBytes = getConfig().getLong("max_file_bytes", DEFAULT_MAX_FILE_BYTES);

        String rawFileName = args[0];
        if (!rawFileName.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            rawFileName = rawFileName + ".txt";
        }

        // 防止用户故意输入包含路径分隔符绕过，除非允许子目录
        if (!allowSubdirs) {
            if (rawFileName.contains(File.separator) || rawFileName.contains("/") || rawFileName.contains("\\")) {
                sender.sendMessage("§c文件名不能包含路径。若想使用子目录请在配置中允许 allow_subdirs。");
                return true;
            }
        }

        String author;
        UUID authorUuid = null;
        if (args.length >= 2) {
            StringJoiner sj = new StringJoiner(" ");
            for (int i = 1; i < args.length; i++) sj.add(args[i]);
            author = sj.toString();
        } else if (sender instanceof Player p) {
            author = p.getName();
            authorUuid = p.getUniqueId();
        } else {
            sender.sendMessage("§c控制台执行时必须提供署名：/bookprinter <文件> <署名>");
            return true;
        }

        // 作者名长度与非法字符检查（移除换行等）
        author = author.replaceAll("[\\r\\n]+", " ").trim();
        if (author.codePointCount(0, author.length()) > MAX_AUTHOR_LENGTH) {
            author = TextUtils.truncateByCodePoints(author, MAX_AUTHOR_LENGTH);
        }

        File file = new File(rawFileName);
        if (!file.isAbsolute()) {
            file = new File(getDataFolder(), rawFileName);
        } else {
            if (!allowAbsolute) {
                sender.sendMessage("§c不允许使用绝对路径读取文件。请移动文件到插件数据目录或在配置中启用 allow_absolute_paths。");
                return true;
            }
        }

        // 规范化并检查路径是否在 data folder 下（当不允许绝对路径或不允许越界时）
        try {
            File dataFolder = getDataFolder().getCanonicalFile();
            File canonical = file.getCanonicalFile();
            if (!allowSubdirs) {
                // 仅允许直接位于 dataFolder 下
                if (!Objects.equals(canonical.getParentFile(), dataFolder)) {
                    sender.sendMessage("§c仅允许读取插件数据目录下的文件（不允许子目录）。");
                    return true;
                }
            } else {
                // 允许子目录，但仍禁止越出 data folder
                if (!canonical.getPath().startsWith(dataFolder.getPath())) {
                    sender.sendMessage("§c禁止访问插件数据目录外的路径。");
                    return true;
                }
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "校验文件路径时发生错误: " + file.getPath(), e);
            sender.sendMessage("§c无法校验文件路径，请检查服务端日志。");
            return true;
        }

        final File finalFile = file;
        final String finalAuthor = author;
        final UUID finalAuthorUuid = authorUuid;

        if (maxCharsPerPage <= 0) maxCharsPerPage = DEFAULT_MAX_CHARS;
        if (maxLinesPerPage <= 0) maxLinesPerPage = DEFAULT_MAX_LINES;

        if (maxCharsPerPage > DEFAULT_MAX_CHARS) {
            sender.sendMessage("§6注意：已设置每页字符数为 " + maxCharsPerPage + "。建议使用 marker 或更多页来分割内容。");
        }

        // 任务开始提示
        if (sender instanceof Player) {
            sender.sendMessage("§e开始生成书: §f" + finalFile.getName());
        } else {
            sender.sendMessage("§e开始生成书: §f" + finalFile.getPath());
        }

        int finalMaxCharsPerPage = maxCharsPerPage;
        int finalMaxLinesPerPage = maxLinesPerPage;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                if (!finalFile.exists() || !finalFile.isFile()) {
                    Bukkit.getScheduler().runTask(this, () -> sender.sendMessage("§c文件不存在或不是文件: " + finalFile.getPath()));
                    return;
                }

                // 文件大小检查（避免一次读取超大文件）
                try {
                    long size = Files.size(finalFile.toPath());
                    if (size > maxFileBytes) {
                        Bukkit.getScheduler().runTask(this, () -> sender.sendMessage("§c文件太大（> " + maxFileBytes + " bytes），拒绝处理。"));
                        getLogger().warning("拒绝处理过大的文件: " + finalFile.getPath() + " (" + size + " bytes)");
                        return;
                    }
                } catch (IOException e) {
                    // 无法获取大小，记录并继续尝试读取，但捕获失败
                    getLogger().log(Level.WARNING, "无法获取文件大小: " + finalFile.getPath(), e);
                }

                String content;
                try {
                    content = Files.readString(finalFile.toPath(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    String msg = "§c读取文件出错: " + (e.getMessage() == null ? "I/O 错误" : e.getMessage());
                    Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(msg));
                    getLogger().log(Level.SEVERE, "无法读取文件 " + finalFile.getPath(), e);
                    return;
                }

                List<String> pages = TextUtils.splitToPagesSafe(content, finalMaxCharsPerPage, splitStrategy, pageMarker, finalMaxLinesPerPage, preserveNewlines, trimTrailingEmptyPages);

                final List<String> finalPages = pages;

                Bukkit.getScheduler().runTask(this, () -> {
                    try {
                        ItemStack book = new ItemStack(org.bukkit.Material.WRITTEN_BOOK, 1);
                        BookMeta meta = (BookMeta) book.getItemMeta();
                        if (meta == null) {
                            sender.sendMessage("§c插件内部错误：无法创建书的元数据。");
                            return;
                        }

                        String title = TextUtils.extractTitleFromFileName(finalFile.getName());
                        if (title.isEmpty()) title = "Book";
                        if (title.codePointCount(0, title.length()) > MAX_TITLE_LENGTH) {
                            title = TextUtils.truncateByCodePoints(title, MAX_TITLE_LENGTH);
                        }
                        title = title.replaceAll("[\\r\\n]+", " ").trim();

                        Component titleComponent = COMPONENT_SERIALIZER.deserialize(title);
                        Component authorComponent = COMPONENT_SERIALIZER.deserialize(finalAuthor);

                        List<Component> pageComponents = new ArrayList<>();
                        for (String p : finalPages) {
                            String safe = p == null ? "" : p;
                            // 若 preserveNewlines 为 false，已经在 splitToPagesSafe 中处理
                            try {
                                // 优先尝试解析颜色代码，如果失败则直接使用纯文本 Component 回退（不做 §->& 替换）
                                pageComponents.add(COMPONENT_SERIALIZER.deserialize(safe));
                            } catch (Throwable t) {
                                getLogger().log(Level.WARNING, "解析页组件失败，使用纯文本回退。页内容可能包含不可解析的字符。", t);
                                pageComponents.add(Component.text(safe));
                            }
                        }

                        // 设置标题与作者（注意：Paper 1.21.5 支持 Component）
                        meta.title(titleComponent);
                        meta.author(authorComponent);

                        try {
                            meta.pages(pageComponents);
                            book.setItemMeta(meta);
                        } catch (Throwable t) {
                            // 设置 pages 可能因长度等原因失败，尝试按页纯文本截断并重试
                            getLogger().log(Level.WARNING, "设置书页时失败，尝试截断每页再重试。", t);
                            List<Component> truncated = new ArrayList<>();
                            for (String s : finalPages) {
                                String plain = s == null ? "" : s;
                                // 基于 code point 做安全截断，避免破坏代理对
                                int allowed = Math.max(1, finalMaxCharsPerPage);
                                if (plain.codePointCount(0, plain.length()) > allowed) {
                                    int end = plain.offsetByCodePoints(0, allowed);
                                    plain = plain.substring(0, end);
                                }
                                truncated.add(Component.text(plain));
                            }
                            meta.pages(truncated);
                            book.setItemMeta(meta);
                        }

                        if (sender instanceof Player) {
                            Player player = null;
                            if (finalAuthorUuid != null) {
                                player = Bukkit.getPlayer(finalAuthorUuid);
                            }
                            if (player == null) {
                                // 最后尝试使用 sender 强转（在同步任务内一般是安全的）
                                Player sp = (Player) sender;
                                player = sp;
                            }
                            if (!player.isOnline()) {
                                // 无法给在线玩家，改为记录并告知
                                sender.sendMessage("§c目标玩家不在线或无法获取，书已保存在服务器日志（请手动分发）。");
                                getLogger().info("无法交付书给玩家（可能已下线）： " + finalFile.getPath() + " (作者: " + finalAuthor + ", 页数: " + finalPages.size() + ")");
                                return;
                            }

                            Map<Integer, ItemStack> leftover = player.getInventory().addItem(book);
                            if (leftover.isEmpty()) {
                                player.sendMessage("§a已将书放入你的背包: §e" + finalFile.getName() + " §7(共 " + finalPages.size() + " 页)");
                            } else {
                                Location loc = player.getLocation();
                                player.getWorld().dropItemNaturally(loc, book);
                                player.sendMessage("§a背包空间不足，书已掉落在你脚下: §e" + finalFile.getName() + " §7(共 " + finalPages.size() + " 页)");
                            }
                            player.sendMessage("§a生成完成: §f" + finalFile.getName() + " §7(共 " + finalPages.size() + " 页)");
                        } else {
                            sender.sendMessage("§a已为控制台/命令来源生成书（无法直接给予）： §e" + finalFile.getPath() + " §7(共 " + finalPages.size() + " 页)");
                            getLogger().info("已为控制台生成书: " + finalFile.getPath() + " (作者: " + finalAuthor + ", 页数: " + finalPages.size() + ")");
                        }
                    } catch (Throwable t) {
                        getLogger().log(Level.SEVERE, "在主线程处理书本时发生意外错误", t);
                        sender.sendMessage("§c生成书时发生内部错误，请查看服务器日志。");
                    }
                });
            } catch (Throwable ex) {
                // 异步任务总体异常处理，确保用户被告知
                getLogger().log(Level.SEVERE, "生成书的异步任务失败", ex);
                Bukkit.getScheduler().runTask(this, () -> sender.sendMessage("§c生成书时发生内部错误，请查看服务器日志。"));
            }
        });

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String @NotNull [] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            File dir = getDataFolder();
            if (dir.exists()) {
                File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".txt"));
                if (files != null) {
                    String prefix = args[0].toLowerCase(Locale.ROOT);
                    for (File f : files) {
                        String name = f.getName();
                        if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) completions.add(name);
                    }
                }
            }
        }
        return completions;

    }
}