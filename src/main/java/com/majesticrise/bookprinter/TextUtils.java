package com.majesticrise.bookprinter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;
import java.util.regex.Pattern;

public final class TextUtils {

    private TextUtils() {}

    private static final Pattern LEGACY_HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern MODERN_LINE_BREAK = Pattern.compile("\\\\Line-break\\\\");
    private static final Pattern LEGACY_COLOR_SHORT = Pattern.compile("&([0-9a-fk-or])", Pattern.CASE_INSENSITIVE);

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();

    private static final Map<String, String> LEGACY_MAP = Map.ofEntries(
            Map.entry("0", "<black>"), Map.entry("1", "<dark_blue>"),
            Map.entry("2", "<dark_green>"), Map.entry("3", "<dark_aqua>"),
            Map.entry("4", "<dark_red>"), Map.entry("5", "<dark_purple>"),
            Map.entry("6", "<gold>"), Map.entry("7", "<gray>"),
            Map.entry("8", "<dark_gray>"), Map.entry("9", "<blue>"),
            Map.entry("a", "<green>"), Map.entry("b", "<aqua>"),
            Map.entry("c", "<red>"), Map.entry("d", "<light_purple>"),
            Map.entry("e", "<yellow>"), Map.entry("f", "<white>"),
            Map.entry("k", "<obfuscated>"), Map.entry("l", "<bold>"),
            Map.entry("m", "<strikethrough>"), Map.entry("n", "<underline>"),
            Map.entry("o", "<italic>"), Map.entry("r", "<reset>")
    );

    public static List<Component> parseModernMode(String rawText, ConfigurationSection config) {
        List<Component> pages = new ArrayList<>();

        boolean trimWhitespace = config.getBoolean("modern.trim_whitespace", false);
        if (trimWhitespace) {
            rawText = rawText.trim();
        }

        rawText = rawText.replace("\r", "");

        rawText = rawText.replaceAll("&#([A-Fa-f0-9]{6})", "<color:#$1>");

        for (Map.Entry<String, String> entry : LEGACY_MAP.entrySet()) {
            rawText = rawText.replace("&" + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : LEGACY_MAP.entrySet()) {
            rawText = rawText.replace("&" + entry.getKey().toUpperCase(Locale.ROOT), entry.getValue());
        }

        String[] rawPages = MODERN_LINE_BREAK.split(rawText, -1);

        for (String pageContent : rawPages) {
            String cleanContent = pageContent.trim();

            if (cleanContent.isEmpty()) {
                pages.add(Component.empty());
                continue;
            }

            cleanContent = cleanContent.replace("\\n", "\n").replace("\\\\n", "\n");

            try {
                Component pageComponent = MINI_MESSAGE.deserialize(cleanContent);
                pages.add(pageComponent);
            } catch (Exception e) {
                pages.add(Component.text(cleanContent));
            }
        }
        return pages;
    }

    public static List<Component> parseClassicMode(String rawText, ConfigurationSection config) {
        ConfigurationSection classic = config.getConfigurationSection("classic");
        int maxChars = (classic != null) ? classic.getInt("max_chars_per_page", 165) : 165;
        String strategy = (classic != null) ? classic.getString("split_strategy", "smart") : "smart";
        String pageMarker = (classic != null) ? classic.getString("page_marker", "---PAGE---") : "---PAGE---";
        int maxLines = (classic != null) ? classic.getInt("max_lines_per_page", 14) : 14;
        boolean preserveNewlines = (classic != null) ? classic.getBoolean("preserve_newlines", true) : true;
        boolean trimEmptyPages = (classic != null) ? classic.getBoolean("trim_trailing_empty_pages", false) : false;

        rawText = rawText.replace("\r", "");

        rawText = ChatColor.translateAlternateColorCodes('&', rawText);

        String hexProcessed = convertHexTags(rawText);

        List<String> pageTexts = splitToPagesSafe(hexProcessed, maxChars, strategy, pageMarker, maxLines, preserveNewlines, trimEmptyPages);

        List<Component> pages = new ArrayList<>();
        for (String text : pageTexts) {
            pages.add(LEGACY_SERIALIZER.deserialize(text));
        }
        return pages;
    }

    private static String convertHexTags(String text) {
        if (text == null || !text.contains("&#")) return text;
        StringBuffer sb = new StringBuffer();
        java.util.regex.Matcher m = LEGACY_HEX_PATTERN.matcher(text);
        while (m.find()) {
            String hex = m.group(1);
            StringBuilder hexFormat = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                hexFormat.append("§").append(c);
            }
            m.appendReplacement(sb, hexFormat.toString());
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static List<String> splitToPagesSafe(String text, int maxChars, String strategy, String pageMarker, int maxLines, boolean preserveNewlines, boolean trimTrailingEmptyPages) {
        LinkedList<String> pages = new LinkedList<>();
        if (text == null) {
            pages.add("");
            return pages;
        }

        if (maxChars <= 0) maxChars = 1;
        final int thresholdCp = Math.max(3, maxChars / 8);
        String usedStrategy = (strategy == null) ? "smart" : strategy;

        switch (usedStrategy) {
            case "marker": {
                if (pageMarker == null || pageMarker.isEmpty()) {
                    pages.addAll(splitToPagesSafe(text, maxChars, "smart", pageMarker, maxLines, preserveNewlines, trimTrailingEmptyPages));
                    break;
                }
                String[] segments = text.split(Pattern.quote(pageMarker), -1);
                for (String seg : segments) {
                    if (seg.isEmpty()) {
                        pages.add("");
                        continue;
                    }
                    List<String> segPages = splitToPagesSafe(seg, maxChars, "smart", pageMarker, maxLines, preserveNewlines, trimTrailingEmptyPages);
                    pages.addAll(segPages);
                }
                break;
            }

            case "lines": {
                if (maxLines <= 0) maxLines = 1;
                String[] lines = text.split("\n", -1);
                StringBuilder sb = new StringBuilder();
                int lineCount = 0;
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    if (sb.length() != 0) sb.append("\n");
                    sb.append(line);
                    lineCount++;
                    boolean isLastLine = (i == lines.length - 1);
                    if (lineCount >= maxLines || isLastLine) {
                        String pageText = sb.toString();
                        if (pageText.codePointCount(0, pageText.length()) <= maxChars) {
                            String finalPage = preserveNewlines ? pageText : trimTrailingNewlines(pageText);
                            finalPage = truncateByCodePoints(finalPage, maxChars);
                            pages.add(finalPage);
                        } else {
                            pages.addAll(splitToPagesSafe(pageText, maxChars, "smart", pageMarker, maxLines, preserveNewlines, trimTrailingEmptyPages));
                        }
                        sb.setLength(0);
                        lineCount = 0;
                    }
                }
                break;
            }

            case "hard": {
                int pos = 0;
                int len = text.length();
                while (pos < len) {
                    int remainingCp = text.codePointCount(pos, len);
                    int takeCp = Math.min(maxChars, remainingCp);
                    int end = text.offsetByCodePoints(pos, takeCp);
                    String piece = text.substring(pos, end);
                    String finalPiece = preserveNewlines ? piece : trimTrailingNewlines(piece);
                    finalPiece = truncateByCodePoints(finalPiece, maxChars);
                    pages.add(finalPiece);
                    pos = end;
                }
                break;
            }

            case "smart":
            default: {
                if (text.isEmpty()) {
                    pages.add("");
                    break;
                }
                int p = 0;
                int l = text.length();
                while (p < l) {
                    int remainingCp = text.codePointCount(p, l);
                    int takeCp = Math.min(maxChars, remainingCp);
                    int end = text.offsetByCodePoints(p, takeCp);

                    if (end >= l) {
                        String last = text.substring(p, l);
                        String finalLast = preserveNewlines ? last : trimTrailingNewlines(last);
                        finalLast = truncateByCodePoints(finalLast, maxChars);
                        pages.add(finalLast);
                        break;
                    }

                    int lastNewline = lastIndexOfChar(text, '\n', end - 1);
                    if (lastNewline >= p) {
                        int gapCp = text.codePointCount(lastNewline + 1, end);
                        if (gapCp <= thresholdCp) {
                            String part = text.substring(p, lastNewline + 1);
                            String finalPart = preserveNewlines ? part : trimTrailingNewlines(part);
                            finalPart = truncateByCodePoints(finalPart, maxChars);
                            pages.add(finalPart);
                            p = lastNewline + 1;
                            continue;
                        }
                    }

                    int lastSpace = lastIndexOfChar(text, ' ', end - 1);
                    if (lastSpace >= p) {
                        int gapCp = text.codePointCount(lastSpace + 1, end);
                        if (gapCp <= thresholdCp) {
                            String part = text.substring(p, lastSpace + 1);
                            String finalPart = preserveNewlines ? part : trimTrailingNewlines(part);
                            finalPart = truncateByCodePoints(finalPart, maxChars);
                            pages.add(finalPart);
                            p = lastSpace + 1;
                            continue;
                        }
                    }

                    String part = text.substring(p, end);
                    String finalPart = preserveNewlines ? part : trimTrailingNewlines(part);
                    finalPart = truncateByCodePoints(finalPart, maxChars);
                    pages.add(finalPart);
                    p = end;
                }
                break;
            }
        }

        if (trimTrailingEmptyPages) {
            while (!pages.isEmpty() && pages.getLast().isEmpty()) pages.removeLast();
            if (pages.isEmpty()) pages.add("");
        }

        return pages;
    }

    public static String truncateByCodePoints(String s, int maxCodePoints) {
        if (s == null) return null;
        if (maxCodePoints <= 0) return "";
        int cp = s.codePointCount(0, s.length());
        if (cp <= maxCodePoints) return s;
        int endIndex = s.offsetByCodePoints(0, maxCodePoints);
        return s.substring(0, endIndex);
    }

    public static String extractTitleFromFileName(String fileName) {
        if (fileName == null) return "";
        int lastSep = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String base = (lastSep >= 0) ? fileName.substring(lastSep + 1) : fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replace('_', ' ').replace('-', ' ').trim();
        base = base.replaceAll("\\s+", " ");
        StringBuilder sb = new StringBuilder();
        String[] parts = base.split(" ");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            int cp0 = p.codePointAt(0);
            int firstCharLen = Character.charCount(cp0);
            String firstChar = p.substring(0, firstCharLen);
            String rest = (p.length() > firstCharLen) ? p.substring(firstCharLen) : "";
            sb.append(firstChar.toUpperCase()).append(rest.toLowerCase());
            if (i < parts.length - 1) sb.append(' ');
        }
        String title = sb.toString().trim();
        if (title.isEmpty()) title = "Book";
        return title;
    }

    private static int lastIndexOfChar(String s, char c, int fromIndexInclusive) {
        if (s == null || s.isEmpty()) return -1;
        if (fromIndexInclusive >= s.length()) fromIndexInclusive = s.length() - 1;
        if (fromIndexInclusive < 0) return -1;
        for (int i = fromIndexInclusive; i >= 0; i--) {
            if (s.charAt(i) == c) return i;
        }
        return -1;
    }

    private static String trimTrailingNewlines(String s) {
        if (s == null || s.isEmpty()) return s;
        int end = s.length();
        while (end > 0) {
            char ch = s.charAt(end - 1);
            if (ch == '\n' || ch == '\r') end--;
            else break;
        }
        if (end == s.length()) return s;
        return s.substring(0, end);
    }
}
