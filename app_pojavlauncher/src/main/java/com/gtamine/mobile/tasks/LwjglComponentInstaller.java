package com.gtamine.mobile.tasks;

import android.content.Context;
import com.gtamine.mobile.Architecture;
import com.gtamine.mobile.JMinecraftVersionList;
import com.gtamine.mobile.R;
import com.gtamine.mobile.Tools;
import com.gtamine.mobile.utils.VerifiedComponentFiles;
import com.gtamine.mobile.value.DependentLibrary;
import com.kdt.mcgui.ProgressLayout;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Downloads only the LWJGL version needed by the selected game, within its preparation task. */
public final class LwjglComponentInstaller {
    private LwjglComponentInstaller() {}

    @androidx.annotation.Keep
    public static class Manifest { public int schema; public Map<String, Component> versions; }
    @androidx.annotation.Keep
    public static class Component { public List<FileSpec> javaFiles; public Map<String, List<FileSpec>> natives; public FileSpec archive; }
    @androidx.annotation.Keep
    public static class FileSpec { public String name, entry, url, sha256; public long size; public boolean bundled; }

    public static synchronized void ensure(Context context, JMinecraftVersionList.Version version, boolean online) throws IOException {
        if (context == null) throw new IOException("Application context is required to prepare LWJGL");
        Manifest manifest;
        try (Reader reader = new InputStreamReader(context.getAssets().open("lwjgl-downloads.json"), "UTF-8")) {
            manifest = Tools.GLOBAL_GSON.fromJson(reader, Manifest.class);
        }
        String selected = selectVersion(version);
        String abi = Architecture.archAsStringAndroid(Architecture.getDeviceArchitecture());
        Component component = manifest == null || manifest.schema != 1 || manifest.versions == null ? null : manifest.versions.get(selected);
        if (component == null || component.javaFiles == null || component.natives == null || component.natives.get(abi) == null) {
            throw new IOException("Unsupported LWJGL component: " + selected + " / " + abi);
        }
        File javaDirectory = new File(Tools.DIR_GAME_HOME, "lwjgl3/" + selected);
        File nativeDirectory = new File(Tools.DIR_DATA, "lwjgl-" + selected + "-natives/" + abi);
        List<FileSpec> nativeFiles = component.natives.get(abi);
        boolean needNatives = false;
        long total = 0;
        for (FileSpec spec : component.javaFiles) {
            File target = target(javaDirectory, spec.name);
            if (!spec.bundled && !VerifiedComponentFiles.matches(target, spec.size, spec.sha256)) total += spec.size;
        }
        for (FileSpec spec : nativeFiles) {
            if (!VerifiedComponentFiles.matches(target(nativeDirectory, spec.name), spec.size, spec.sha256)) needNatives = true;
        }
        if (needNatives) total += component.archive.size;
        final long totalBytes = Math.max(1, total);
        long completed = 0;
        for (FileSpec spec : component.javaFiles) {
            File output = target(javaDirectory, spec.name);
            if (VerifiedComponentFiles.matches(output, spec.size, spec.sha256)) continue;
            long base = completed;
            if (spec.bundled) {
                VerifiedComponentFiles.install(output, spec.size, spec.sha256,
                        () -> context.getAssets().open("components/lwjgl3/" + selected + "/" + spec.name), bytes -> {});
            } else {
                download(context, output, spec, online, bytes -> report(base + bytes, totalBytes));
                completed += spec.size;
            }
        }
        if (needNatives) {
            File archive = new File(context.getCacheDir(), "lwjgl-" + selected + "-natives.aar");
            long base = completed;
            download(context, archive, component.archive, online, bytes -> report(base + bytes, totalBytes));
            try (ZipFile zip = new ZipFile(archive)) {
                for (FileSpec spec : nativeFiles) {
                    File output = target(nativeDirectory, spec.name);
                    ZipEntry entry = zip.getEntry(spec.entry);
                    if (entry == null || entry.isDirectory() || entry.getSize() != spec.size) throw new IOException("Missing native " + spec.name);
                    VerifiedComponentFiles.install(output, spec.size, spec.sha256, () -> zip.getInputStream(entry), bytes -> {});
                }
            }
            if (!archive.delete()) archive.deleteOnExit();
        }
        report(totalBytes, totalBytes);
    }

    private static File target(File directory, String name) throws IOException {
        if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")) throw new IOException("Invalid component filename");
        return new File(directory, name);
    }

    private static void download(Context context, File output, FileSpec spec, boolean online,
                                 VerifiedComponentFiles.Progress progress) throws IOException {
        if (VerifiedComponentFiles.matches(output, spec.size, spec.sha256)) { progress.update(spec.size); return; }
        if (!online) throw new IOException(context.getString(R.string.direct_lwjgl_internet_required));
        IOException failure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                VerifiedComponentFiles.install(output, spec.size, spec.sha256,
                        () -> VerifiedComponentFiles.openHttps(spec.url), progress);
                return;
            } catch (InterruptedIOException interrupted) { throw interrupted; }
            catch (IOException error) { failure = error; }
        }
        throw new IOException(context.getString(R.string.direct_lwjgl_download_failed), failure);
    }

    private static void report(long completed, long total) {
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, (int) Math.min(100, completed * 100 / total),
                R.string.direct_lwjgl_downloading, completed / 1048576d, total / 1048576d);
    }

    static String selectVersion(JMinecraftVersionList.Version version) throws IOException {
        if (version != null && version.libraries != null) {
            for (DependentLibrary library : version.libraries) {
                if (library == null || library.name == null) continue;
                Matcher match = Pattern.compile("^org\\.lwjgl(?:\\.lwjgl)?:lwjgl:(\\d+)\\.(\\d+)\\.(\\d+)").matcher(library.name);
                if (match.find()) {
                    int major = Integer.parseInt(match.group(1));
                    int minor = Integer.parseInt(match.group(2));
                    int patch = Integer.parseInt(match.group(3));
                    return major > 3 || (major == 3 && (minor > 4 || (minor == 4 && patch >= 1))) ? "3.4.1" : "3.3.3";
                }
            }
        }
        throw new IOException("Cannot determine the game's LWJGL version");
    }
}
