package com.gtamine.mobile.tasks;

import android.content.Context;
import com.gtamine.mobile.*;
import com.gtamine.mobile.value.DependentLibrary;
import com.gtamine.mobile.utils.VerifiedComponentFiles;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Runs the production installer with filesystem-only Android adapters. */
public class LwjglComponentInstallerTest {
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
    private static JMinecraftVersionList.Version version(String name) {
        JMinecraftVersionList.Version result = new JMinecraftVersionList.Version();
        DependentLibrary library = new DependentLibrary(); library.name = "org.lwjgl:lwjgl:" + name;
        result.libraries = new DependentLibrary[]{library}; return result;
    }
    public static void main(String[] args) throws Exception {
        Path workspace = Paths.get(args[0]);
        Path assets = workspace.resolve("app_pojavlauncher/build/generated/lwjglDownloadAssets");
        check(Files.exists(assets.resolve("lwjgl-downloads.json")), "Build the APK before testing");
        check(!Files.exists(assets.resolve("components/lwjgl3/3.3.3/lwjgl-vulkan.jar")), "Remote JAR still packaged");
        check(LwjglComponentInstaller.selectVersion(version("3.3.6")).equals("3.3.3"), "Version mapping");
        check(LwjglComponentInstaller.selectVersion(version("3.4.1")).equals("3.4.1"), "Version mapping");
        Path root = Files.createTempDirectory(workspace.resolve("build"), "lwjgl-install-test-");
        try {
            boolean first = true;
            for (String release : new String[]{"3.3.3", "3.4.1"}) {
                for (String abi : new String[]{"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}) {
                    Path instance = root.resolve(release + "-" + abi);
                    Tools.DIR_GAME_HOME = instance.resolve("game").toString();
                    Tools.DIR_DATA = instance.resolve("private").toString();
                    Architecture.abi = abi;
                    Path cache = instance.resolve("cache");
                    Context context = new Context(assets.toFile(), cache.toFile());
                    Path javaDirectory = Paths.get(Tools.DIR_GAME_HOME, "lwjgl3", release);
                    Files.createDirectories(javaDirectory);
                    if (!first) {
                        try (java.util.stream.Stream<Path> files = Files.list(workspace.resolve("app_pojavlauncher/src/main/assets/components/lwjgl3/" + release))) {
                            files.filter(Files::isRegularFile).forEach(path -> {
                                try { Files.copy(path, javaDirectory.resolve(path.getFileName())); }
                                catch (IOException error) { throw new UncheckedIOException(error); }
                            });
                        }
                        Files.copy(workspace.resolve("app_pojavlauncher/libs/lwjgl-" + release + "-natives-release.aar"), cache.resolve("lwjgl-" + release + "-natives.aar"));
                    }
                    LwjglComponentInstaller.ensure(context, version(release), first);
                    Path natives = Paths.get(Tools.DIR_DATA, "lwjgl-" + release + "-natives", abi);
                    check(Files.isRegularFile(natives.resolve("liblwjgl.so")), "Native installation missing");
                    for (String other : new String[]{"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}) {
                        if (!other.equals(abi)) check(!Files.exists(natives.getParent().resolve(other)), "Wrong ABI installed");
                    }
                    String otherVersion = release.equals("3.3.3") ? "3.4.1" : "3.3.3";
                    check(!Files.exists(javaDirectory.getParent().resolve(otherVersion)), "Wrong version installed");
                    check(!Files.exists(cache.resolve("lwjgl-" + release + "-natives.aar")), "Archive not cleaned up");
                    LwjglComponentInstaller.ensure(context, version(release), false);
                    if (first) {
                        Files.write(javaDirectory.resolve("lwjgl.jar"), new byte[]{1, 2, 3});
                        boolean failed = false;
                        try { LwjglComponentInstaller.ensure(context, version(release), false); }
                        catch (IOException expected) { failed = true; }
                        check(failed, "Corrupt offline component must block launch");
                        LwjglComponentInstaller.ensure(context, version(release), true);
                        LwjglComponentInstaller.ensure(context, version(release), false);
                        System.out.println("PASS: real HTTPS download, offline reuse, corruption detection and online repair");
                    }
                    first = false;
                    System.out.println("PASS: " + release + " / " + abi + " installs only selected architecture and version");
                }
            }
            Path target = root.resolve("safe.bin");
            Files.write(target, new byte[]{9});
            String wrongHash = String.join("", Collections.nCopies(64, "0"));
            try {
                VerifiedComponentFiles.install(target.toFile(), 3, wrongHash, () -> new ByteArrayInputStream(new byte[]{1, 2, 3}), bytes -> {});
                throw new AssertionError("Hash mismatch was accepted");
            } catch (IOException expected) { }
            check(Arrays.equals(Files.readAllBytes(target), new byte[]{9}), "Failed download replaced existing file");
            check(!Files.exists(root.resolve("safe.bin.part")), "Partial file leaked");
            try {
                VerifiedComponentFiles.install(target.toFile(), 1, wrongHash, () -> new ByteArrayInputStream(new byte[]{1, 2}), bytes -> {});
                throw new AssertionError("Oversized component accepted");
            } catch (IOException expected) { }
            check(Arrays.equals(Files.readAllBytes(target), new byte[]{9}), "Oversized download replaced existing file");
            System.out.println("PASS: integrity checks, size limits and partial file cleanup");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
}
