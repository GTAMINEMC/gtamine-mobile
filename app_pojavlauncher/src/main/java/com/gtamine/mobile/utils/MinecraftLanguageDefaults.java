package com.gtamine.mobile.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** Prepares the language before downloading or launching a new game instance. */
public final class MinecraftLanguageDefaults {
    public static final String PREFERENCE_KEY = "minecraft_default_language";
    private MinecraftLanguageDefaults() {}

    public static void prepare(File gameDirectory) throws IOException {
        prepare(gameDirectory, "ru_ru");
    }

    public static void prepare(File gameDirectory, String language) throws IOException {
        writeLanguage(gameDirectory, language, false);
    }

    public static void setLanguage(File gameDirectory, String language) throws IOException {
        writeLanguage(gameDirectory, language, true);
    }

    private static void writeLanguage(File gameDirectory, String language, boolean replace) throws IOException {
        if (!"ru_ru".equals(language) && !"en_us".equals(language)) {
            throw new IllegalArgumentException("Unsupported default language: " + language);
        }
        if (!gameDirectory.isDirectory() && !gameDirectory.mkdirs()) {
            throw new IOException("Cannot create game directory: " + gameDirectory);
        }
        File options = new File(gameDirectory, "options.txt");
        StringBuilder content = new StringBuilder();
        boolean hasEmptyLanguage = false;
        if (options.exists()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(options), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("lang:")) {
                        // A saved selection, including English, always belongs to the player.
                        if (!replace && !line.substring(5).trim().isEmpty()) return;
                        hasEmptyLanguage = true;
                        line = "lang:" + language;
                    }
                    content.append(line).append('\n');
                }
            }
        }
        if (!hasEmptyLanguage) content.append("lang:").append(language).append('\n');
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(options), StandardCharsets.UTF_8)) {
            writer.write(content.toString());
        }
    }
}
