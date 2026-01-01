package com.majesticrise.bookprinter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;
import java.util.logging.Level;

public class LanguageManager {

    private final JavaPlugin plugin;
    private FileConfiguration langConfig;
    private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacySection();

    public LanguageManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        String langCode = plugin.getConfig().getString("language", "zh_CN");
        String fileName = "Language-" + langCode + ".yml";
        File langFile = new File(plugin.getDataFolder(), fileName);

        if (!langFile.exists()) {
            plugin.saveResource(fileName, false);
            if (!langFile.exists()) {
                plugin.getLogger().warning("Language file '" + fileName + "' not found in plugin jar, falling back to default.");
                fileName = "Language-en_US.yml";
                plugin.saveResource(fileName, false);
                langFile = new File(plugin.getDataFolder(), fileName);
            }
        }

        try {
            this.langConfig = YamlConfiguration.loadConfiguration(langFile);
            plugin.getLogger().info("Loaded language: " + langCode);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load language file: " + fileName, e);
        }
    }

    public String getRaw(String key) {
        if (langConfig == null) return key;
        String prefix = langConfig.getString("prefix", "");
        String msg = langConfig.getString(key, "");

        return ChatColor.translateAlternateColorCodes('&', prefix + msg);
    }

    public String getRaw(String key, Map<String, String> placeholders) {
        String raw = getRaw(key);
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                raw = raw.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return raw;
    }

    public Component get(String key) {
        return serializer.deserialize(getRaw(key));
    }

    public Component get(String key, Map<String, String> placeholders) {
        return serializer.deserialize(getRaw(key, placeholders));
    }

    public void reload() {
        load();
    }
}
