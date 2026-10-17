package com.gtamine.mobile.utils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Bounded, verified installation. A failed transfer never replaces a working component. */
public final class VerifiedComponentFiles {
    public interface Source { InputStream open() throws IOException; }
    public interface Progress { void update(long bytes); }
    private VerifiedComponentFiles() {}

    public static boolean matches(File file, long size, String sha256) throws IOException {
        if (!file.isFile() || file.length() != size) return false;
        try (InputStream input = new FileInputStream(file)) {
            MessageDigest digest = digest();
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            return hex(digest.digest()).equalsIgnoreCase(sha256);
        }
    }

    public static void install(File target, long size, String sha256, Source source, Progress progress) throws IOException {
        if (size < 0 || sha256 == null || !sha256.matches("[a-fA-F0-9]{64}")) throw new IOException("Invalid component metadata");
        if (matches(target, size, sha256)) { progress.update(size); return; }
        File directory = target.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
        File partial = new File(directory, target.getName() + ".part");
        try {
            MessageDigest hash = digest();
            long total = 0;
            try (InputStream input = source.open(); FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Component download interrupted");
                    total += count;
                    if (total > size) throw new IOException("Component exceeds declared size: " + target.getName());
                    output.write(buffer, 0, count);
                    hash.update(buffer, 0, count);
                    progress.update(total);
                }
                output.getFD().sync();
            }
            if (total != size || !hex(hash.digest()).equalsIgnoreCase(sha256)) {
                throw new IOException("Component integrity check failed: " + target.getName());
            }
            File backup = new File(directory, target.getName() + ".previous");
            boolean hadTarget = target.exists();
            if (hadTarget && !target.renameTo(backup)) throw new IOException("Cannot back up " + target);
            if (!partial.renameTo(target)) {
                if (hadTarget && !backup.renameTo(target)) throw new IOException("Cannot restore " + target);
                throw new IOException("Cannot install " + target);
            }
            if (backup.exists() && !backup.delete()) backup.deleteOnExit();
        } finally {
            if (partial.exists() && !partial.delete()) partial.deleteOnExit();
        }
    }

    public static InputStream openHttps(String address) throws IOException {
        URL url = new URL(address);
        for (int redirect = 0; redirect < 6; redirect++) {
            if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("HTTPS required for components");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            int status;
            try { status = connection.getResponseCode(); }
            catch (IOException error) { connection.disconnect(); throw error; }
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Missing download redirect");
                url = new URL(url, location);
                continue;
            }
            if (status != 200) { connection.disconnect(); throw new IOException("Component server returned HTTP " + status); }
            try {
                return new FilterInputStream(connection.getInputStream()) {
                    @Override public void close() throws IOException {
                        try { super.close(); } finally { connection.disconnect(); }
                    }
                };
            } catch (IOException error) { connection.disconnect(); throw error; }
        }
        throw new IOException("Too many component redirects");
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
