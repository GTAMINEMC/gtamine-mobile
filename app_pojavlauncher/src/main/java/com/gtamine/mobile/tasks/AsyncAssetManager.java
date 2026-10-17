package com.gtamine.mobile.tasks;


import static com.gtamine.mobile.Architecture.archAsString;
import static com.gtamine.mobile.Architecture.archAsStringAndroid;
import static com.gtamine.mobile.Architecture.getDeviceArchitecture;
import static com.gtamine.mobile.GTAMINEApplication.sExecutorService;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import android.widget.Toast;

import com.kdt.mcgui.ProgressLayout;

import com.gtamine.mobile.R;
import com.gtamine.mobile.Tools;
import com.gtamine.mobile.multirt.MultiRTUtils;
import com.gtamine.mobile.prefs.LauncherPreferences;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class AsyncAssetManager {

    private AsyncAssetManager(){}

    /**
     * Attempt to install the java 8 runtime, if necessary
     * @param am App context
     */
    public static void unpackRuntime(AssetManager am) {
        /* Check if JRE is included */
        String rt_version = null;
        String current_rt_version = MultiRTUtils.readInternalRuntimeVersion("Internal");
        try {
            rt_version = Tools.read(am.open("components/jre/version"));
        } catch (IOException e) {
            Log.e("JREAuto", "JRE was not included on this APK.", e);
        }
        String exactJREName = MultiRTUtils.getExactJreName(8);
        if(current_rt_version == null && exactJREName != null && !exactJREName.equals("Internal")/*this clause is for when the internal runtime is goofed*/) return;
        if(rt_version == null) return;
        if(rt_version.equals(current_rt_version)) return;

        // Install the runtime in an async manner, hope for the best
        String finalRt_version = rt_version;
        sExecutorService.execute(() -> {

            try {
                MultiRTUtils.installRuntimeNamedBinpack(
                        am.open("components/jre/universal.tar.xz"),
                        am.open("components/jre/bin-" + archAsString(Tools.DEVICE_ARCHITECTURE) + ".tar.xz"),
                        "Internal", finalRt_version);
                MultiRTUtils.postPrepare("Internal");
            }catch (IOException e) {
                Log.e("JREAuto", "Internal JRE unpack failed", e);
            }
        });
    }

    /** Unpack single files, with no regard to version tracking */
    public static void unpackSingleFiles(Context ctx){
        ProgressLayout.setProgress(ProgressLayout.EXTRACT_SINGLE_FILES, 0);
        sExecutorService.execute(() -> {
            try {
                boolean hasOptions = new File(Tools.DIR_GAME_NEW, "options.txt").exists();
                Tools.copyAssetFile(ctx, "options.txt", Tools.DIR_GAME_NEW, false);
                if (!hasOptions) {
                    com.gtamine.mobile.utils.MinecraftLanguageDefaults.setLanguage(new File(Tools.DIR_GAME_NEW),
                            LauncherPreferences.DEFAULT_PREF.getString(
                                    com.gtamine.mobile.utils.MinecraftLanguageDefaults.PREFERENCE_KEY, "ru_ru"));
                }

                // This is disgusting, but am lazy. We probably wont be getting any updates to
                // controlmap till rewrite anyway so this is fiiine.
                try (InputStream is = ctx.getAssets().open("default.json")) {
                    String assetSha1 = new String(org.apache.commons.codec.binary.Hex.encodeHex(org.apache.commons.codec.digest.DigestUtils.sha1(is)));
                    if (!Tools.compareSHA1(new File(Tools.CTRLDEF_FILE), assetSha1)) {
                        // Keep the launcher-provided default layout in sync after an update.
                        // Custom layouts remain separate files and are never touched here.
                        Tools.copyAssetFile(ctx, "default.json", Tools.CTRLMAP_PATH, true);
                    } else if (!new File(Tools.CTRLMAP_PATH+"/new_default.json").exists())
                    Tools.copyAssetFile(ctx, "default.json", Tools.CTRLMAP_PATH, false);
                }

                Tools.copyAssetFile(ctx, "launcher_profiles.json", Tools.DIR_GAME_NEW, false);
                Tools.copyAssetFile(ctx,"resolv.conf",Tools.DIR_DATA, false);
            } catch (IOException e) {
                Log.e("AsyncAssetManager", "Failed to unpack critical components !");
            }
            ProgressLayout.clearProgress(ProgressLayout.EXTRACT_SINGLE_FILES);
        });
    }

    public static void unpackComponents(Context ctx){
        ProgressLayout.setProgress(ProgressLayout.EXTRACT_COMPONENTS, 0);
        sExecutorService.execute(() -> {
            try {
                unpackComponent(ctx, "caciocavallo", false);
                unpackComponent(ctx, "caciocavallo17", false);
                // LWJGL is prepared on Play, together with the selected game's libraries.
                unpackComponent(ctx, "security", true);
                unpackComponent(ctx, "arc_dns_injector", true);
                unpackComponent(ctx, "MioLibPatcher", true);
                unpackComponent(ctx, "forge_installer", true);
            } catch (IOException e) {
                Log.e("AsyncAssetManager", "Failed to unpack components !",e );
            }
            ProgressLayout.clearProgress(ProgressLayout.EXTRACT_COMPONENTS);
        });
    }
    private static void unpackComponent(Context ctx, String component, boolean privateDirectory) throws IOException {
        AssetManager am = ctx.getAssets();
        String rootDir = privateDirectory ? Tools.DIR_DATA : Tools.DIR_GAME_HOME;

        File versionFile = new File(rootDir + "/" + component + "/version");
        InputStream is = am.open("components/" + component + "/version");
        if(!versionFile.exists()) {
            if (versionFile.getParentFile().exists() && versionFile.getParentFile().isDirectory()) {
                FileUtils.deleteDirectory(versionFile.getParentFile());
            }
            versionFile.getParentFile().mkdir();

            Log.i("UnpackPrep", component + ": Pack was installed manually, or does not exist, unpacking new...");
            String[] fileList = am.list("components/" + component);
            for(String s : fileList) {
                Tools.copyAssetFile(ctx, "components/" + component + "/" + s, rootDir + "/" + component, true);
            }
        } else {
            FileInputStream fis = new FileInputStream(versionFile);
            String release1 = Tools.read(is);
            String release2 = Tools.read(fis);
            if (!release1.equals(release2)) {
                if (versionFile.getParentFile().exists() && versionFile.getParentFile().isDirectory()) {
                    FileUtils.deleteDirectory(versionFile.getParentFile());
                }
                versionFile.getParentFile().mkdir();

                String[] fileList = am.list("components/" + component);
                for (String fileName : fileList) {
                    Tools.copyAssetFile(ctx, "components/" + component + "/" + fileName, rootDir + "/" + component, true);
                }
            } else {
                Log.i("UnpackPrep", component + ": Pack is up-to-date with the launcher, continuing...");
            }
        }
    }
}
