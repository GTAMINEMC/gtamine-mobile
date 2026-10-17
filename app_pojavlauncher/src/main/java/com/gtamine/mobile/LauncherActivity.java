package com.gtamine.mobile;

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static com.gtamine.mobile.Tools.getMods;
import static com.gtamine.mobile.Tools.hasMods;
import static com.gtamine.mobile.Tools.hasNoOnlineProfileDialog;
import static com.gtamine.mobile.Tools.isOnline;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.RadioGroup;
import android.text.Editable;
import android.text.TextWatcher;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;
import androidx.fragment.app.FragmentManager;

import com.kdt.mcgui.ProgressLayout;
import com.kdt.mcgui.mcAccountSpinner;

import com.gtamine.mobile.contracts.OpenDocumentWithExtension;
import com.gtamine.mobile.extra.ExtraConstants;
import com.gtamine.mobile.extra.ExtraCore;
import com.gtamine.mobile.extra.ExtraListener;
import com.gtamine.mobile.fragments.MainMenuFragment;
import com.gtamine.mobile.fragments.MicrosoftLoginFragment;
import com.gtamine.mobile.fragments.SelectAuthFragment;
import com.gtamine.mobile.lifecycle.ContextAwareDoneListener;
import com.gtamine.mobile.lifecycle.ContextExecutor;
import com.gtamine.mobile.modloaders.LWJGL3ifyUtils;
import com.gtamine.mobile.modloaders.modpacks.ModloaderInstallTracker;
import com.gtamine.mobile.modloaders.modpacks.api.CommonApi;
import com.gtamine.mobile.modloaders.modpacks.api.ModLoader;
import com.gtamine.mobile.modloaders.modpacks.api.ModpackInstaller;
import com.gtamine.mobile.modloaders.modpacks.api.NotificationDownloadListener;
import com.gtamine.mobile.modloaders.modpacks.imagecache.IconCacheJanitor;
import com.gtamine.mobile.prefs.LauncherPreferences;
import com.gtamine.mobile.prefs.screens.LauncherPreferenceFragment;
import com.gtamine.mobile.progresskeeper.ProgressKeeper;
import com.gtamine.mobile.progresskeeper.ProgressListener;
import com.gtamine.mobile.progresskeeper.TaskCountListener;
import com.gtamine.mobile.services.ProgressServiceKeeper;
import com.gtamine.mobile.tasks.AsyncMinecraftDownloader;
import com.gtamine.mobile.tasks.AsyncVersionList;
import com.gtamine.mobile.tasks.MinecraftDownloader;
import com.gtamine.mobile.utils.DateUtils;
import com.gtamine.mobile.utils.NotificationUtils;
import com.gtamine.mobile.utils.MinecraftLanguageDefaults;
import com.gtamine.mobile.value.MinecraftAccount;
import com.gtamine.mobile.value.launcherprofiles.LauncherProfiles;
import com.gtamine.mobile.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

public class LauncherActivity extends BaseActivity {
    public static final String SETTING_FRAGMENT_TAG = "SETTINGS_FRAGMENT";
    private static final String DIRECT_LAUNCH_VERSION = "1.21.11";
    private static final String DIRECT_LAUNCH_PROFILE_ID = "4754414d-494e-4521-8121-000121110001";
    private static final String DIRECT_LAUNCH_PROFILE_NAME = "GTAMINE Mobile";

    public final ActivityResultLauncher<Object> modInstallerLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("jar"), (data)->{
                if(data != null) Tools.launchModInstaller(this, data);
            });
    public final ActivityResultLauncher<Object> modpackImportLauncher =
            registerForActivityResult(new OpenDocumentWithExtension(new String[]{"zip", "mrpack"}), (data)->{
                if(data != null) {
                    GTAMINEApplication.sExecutorService.execute(() -> {
                        try {
                            // Copy ZIP file to cache
                            long fileSize = -1;
                            try (Cursor returnCursor = getContentResolver().query(data, new String[]{OpenableColumns.SIZE}, null, null, null)) {
                                if (returnCursor != null && returnCursor.moveToFirst()) {
                                    fileSize = returnCursor.getLong(0);
                                }
                            }
                            File modpackFile = new File(Tools.DIR_CACHE, "import_modpack_placeholdername.cf");
                            try (InputStream inputStream = getContentResolver().openInputStream(data)){
                                FileOutputStream output = new FileOutputStream(modpackFile);
                                byte[] b = new byte[262144];
                                int read;
                                int readTotal = 0;
                                while ((read = inputStream.read(b)) != -1) {
                                    output.write(b, 0, read);
                                    readTotal += read;
                                    String readMB = fileSize > 0 ? String.format(Locale.US, "%.2f", readTotal / (1024.0 * 1024.0)) : "unknown";
                                    String totalMB = fileSize > 0 ? String.format(Locale.US, "%.2f", fileSize / (1024.0 * 1024.0)) : "unknown";
                                    int progress = fileSize > 0 ? (int) ((readTotal * 100L) / fileSize) : 0;
                                    ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, progress, R.string.import_modpack_copy, readMB, totalMB);
                                }
                                output.flush();
                                output.close();
                            }
                            ModLoader loaderInfo = new CommonApi(getString(R.string.curseforge_api_key)).importModpack(modpackFile);
                            modpackFile.delete();
                            if (loaderInfo == null) return;
                            loaderInfo.getDownloadTask(new NotificationDownloadListener(this, loaderInfo)).run();
                        } catch (IOException e) {
                            Tools.showErrorRemote(this, R.string.modpack_install_download_failed, e);
                            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                        } catch (IllegalArgumentException e) {
                            Tools.showError(this, R.string.not_modpack_file, e);
                            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                        } catch (NoSuchAlgorithmException e) {
                            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                            // Should literally never happen because SHA-1 is required Java spec
                            throw new RuntimeException(e);
                        }
                    });
                }
            });

    private mcAccountSpinner mAccountSpinner;
    private FragmentContainerView mFragmentView;
    private ImageButton mSettingsButton;
    private ProgressLayout mProgressLayout;
    private ProgressServiceKeeper mProgressServiceKeeper;
    private ModloaderInstallTracker mInstallTracker;
    private NotificationManager mNotificationManager;
    private View mDirectLaunchView;
    private View mDirectProgressContainer;
    private EditText mDirectNicknameInput;
    private Button mDirectLaunchButton;
    private ProgressBar mDirectProgressBar;
    private TextView mDirectStatusText;
    private TextView mDirectPercentText;
    private boolean mDirectLaunchMode;
    private boolean mDirectLaunchRequested;
    private View mDirectSettingsView;
    private SeekBar mDirectRamSeekBar;
    private TextView mDirectRamValue;
    private View mDirectLogoBadge;
    private View mDirectNicknameCard;
    private View mDirectPlayCard;

    private final TaskCountListener mDirectSetupTaskCountListener = taskCount -> {
        if (taskCount != 0 || !mDirectLaunchMode || mDirectLaunchRequested) return;
        runOnUiThread(() -> {
            if (!mDirectLaunchMode || mDirectLaunchRequested || mDirectProgressContainer == null) return;
            mDirectProgressContainer.setVisibility(View.INVISIBLE);
            mDirectNicknameInput.setEnabled(true);
            findViewById(R.id.direct_saved_nicknames).setEnabled(true);
            mSettingsButton.setEnabled(true);
            mDirectLaunchButton.setEnabled(true);
            mDirectLaunchButton.setText(R.string.direct_launch_button);
        });
    };

    private final ProgressListener mDirectMinecraftProgress =
            new DirectProgressListener(R.string.direct_status_checking, true);
    private final ProgressListener mDirectRuntimeProgress =
            new DirectProgressListener(R.string.direct_status_java, false);
    private final ProgressListener mDirectComponentProgress =
            new DirectProgressListener(R.string.direct_status_components, false);
    private final ProgressListener mDirectFilesProgress =
            new DirectProgressListener(R.string.direct_status_components, false);

    /* Allows to switch from one button "type" to another */
    private final FragmentManager.FragmentLifecycleCallbacks mFragmentCallbackListener = new FragmentManager.FragmentLifecycleCallbacks() {
        @Override
        public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment f) {
            mSettingsButton.setImageDrawable(ContextCompat.getDrawable(getBaseContext(), f instanceof MainMenuFragment
                    ? R.drawable.ic_menu_settings : R.drawable.ic_menu_home));
        }
    };

    /* Listener for the back button in settings */
    private final ExtraListener<String> mBackPreferenceListener = (key, value) -> {
        if(value.equals("true")) onBackPressed();
        return false;
    };

    /* Listener for the auth method selection screen */
    private final ExtraListener<Boolean> mSelectAuthMethod = (key, value) -> {
        if (mDirectLaunchMode) return false;
        Fragment fragment = getSupportFragmentManager().findFragmentById(mFragmentView.getId());
        // Allow starting the add account only from the main menu, should it be moved to fragment itself ?
        if(!(fragment instanceof MainMenuFragment)) return false;

        Tools.swapFragment(this, SelectAuthFragment.class, SelectAuthFragment.TAG, null);
        return false;
    };

    /* Listener for the settings fragment */
    private final View.OnClickListener mSettingButtonListener = v -> {
        if (mDirectLaunchMode) {
            if (mDirectSettingsView.getVisibility() == View.VISIBLE) showDirectLaunchScreen();
            else showDirectSettings();
            return;
        }
        Fragment fragment = getSupportFragmentManager().findFragmentById(mFragmentView.getId());
        if(fragment instanceof MainMenuFragment){
            Tools.swapFragment(this, LauncherPreferenceFragment.class, SETTING_FRAGMENT_TAG, null);
        } else{
            // The setting button doubles as a home button now
            Tools.backToMainMenu(this);
        }
    };

    private final ExtraListener<Boolean> mLaunchGameListener = (key, value) -> {
        if(ProgressLayout.hasProcesses()){
            Toast.makeText(this, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return false;
        }

        String selectedProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE,"");
        if (LauncherProfiles.mainProfileJson == null || !LauncherProfiles.mainProfileJson.profiles.containsKey(selectedProfile)){
            Toast.makeText(this, R.string.error_no_version, Toast.LENGTH_LONG).show();
            return false;
        }
        MinecraftProfile prof = LauncherProfiles.mainProfileJson.profiles.get(selectedProfile);
        if (prof == null || prof.lastVersionId == null || "Unknown".equals(prof.lastVersionId)){
            Toast.makeText(this, R.string.error_no_version, Toast.LENGTH_LONG).show();
            return false;
        }

        if(mAccountSpinner.getSelectedAccount() == null){
            Toast.makeText(this, R.string.no_saved_accounts, Toast.LENGTH_LONG).show();
            ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
            return false;
        }

        // Override whatever version is in use and replace it with lwjgl3ify if needed
        List<File> lwjgl3ifyJars = getMods("lwjgl3ify-3");
        if (!lwjgl3ifyJars.isEmpty()) {
            if (lwjgl3ifyJars.size() > 1) {
                // "Duplicate LWJGL3ify jars found, cannot launch."
                Tools.dialogOnUiThread(this, R.string.global_error, R.string.mc_download_failed);
                return false;
            }

            File lwjgl3ifyJar = lwjgl3ifyJars.get(0);

            // If the version contains lwjgl3ify, its probably someone who knows what they're doing
            // so lets leave that alone
            if (!prof.lastVersionId.toLowerCase().contains("lwjgl3ify")) {
                try {
                    prof.lastVersionId = LWJGL3ifyUtils.installJson(lwjgl3ifyJar).id;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                LauncherProfiles.mainProfileJson.profiles.put(selectedProfile, prof);
                LauncherProfiles.write();


            }
            // We just installed a json, we need internet + online acc to download so we add super
            // basic detection whether lwjgl3ify assets were downloaded
            try {
                String jsonPath = LWJGL3ifyUtils.getJsonPath(LWJGL3ifyUtils.getProfileID(lwjgl3ifyJar));
                File lwjgl3ifyClientJar = new File(jsonPath.replace(".json", ".jar"));
                if (!lwjgl3ifyClientJar.exists()){
                    if (mAccountSpinner.getSelectedAccount().isLocal() || !isOnline(this)){
                        Tools.dialogOnUiThread(this, R.string.global_error, R.string.mc_download_failed);
                        return false;
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        String normalizedVersionId = AsyncMinecraftDownloader.normalizeVersionId(prof.lastVersionId);
        JMinecraftVersionList.Version mcVersion = AsyncMinecraftDownloader.getListedVersion(normalizedVersionId);
        if (mcVersion == null && mDirectLaunchMode) {
            new AsyncVersionList().getVersionList(versions -> {
                if (versions == null) {
                    runOnUiThread(() -> {
                        mDirectLaunchRequested = false;
                        mDirectNicknameInput.setEnabled(true);
                        findViewById(R.id.direct_saved_nicknames).setEnabled(true);
                        mSettingsButton.setEnabled(true);
                        mDirectLaunchButton.setEnabled(true);
                        mDirectLaunchButton.setText(R.string.direct_launch_button);
                        Toast.makeText(this, R.string.direct_launch_metadata_error, Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, versions);
                runOnUiThread(() -> ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true));
            }, false);
            return false;
        }

        // Do not load when is a modded version or older than minecraft 1.3 on demo account
        if (mAccountSpinner.getSelectedAccount().isDemo()) {
            boolean isOlderThan13 = true;

            if (mcVersion != null) {
                try {
                    isOlderThan13 = DateUtils.dateBefore(DateUtils.parseReleaseDate(mcVersion.releaseTime), 2012, 6, 22);
                } catch (ParseException ignored) {}
            }

            if (isOlderThan13) {
                hasNoOnlineProfileDialog(this, getString(R.string.global_error), getString(R.string.demo_versions_supported));
                return false;
            }
        }

        new MinecraftDownloader().start(
                this,
                mcVersion,
                normalizedVersionId,
                new ContextAwareDoneListener(this, normalizedVersionId) {
                    @Override public void onDownloadFailed(Throwable throwable) {
                        runOnUiThread(() -> {
                            if (!mDirectLaunchMode) return;
                            mDirectLaunchRequested = false;
                            mDirectNicknameInput.setEnabled(true);
                            findViewById(R.id.direct_saved_nicknames).setEnabled(true);
                            mSettingsButton.setEnabled(true);
                            mDirectLaunchButton.setEnabled(true);
                            mDirectLaunchButton.setText(R.string.direct_launch_button);
                            mDirectProgressContainer.setVisibility(View.INVISIBLE);
                        });
                        super.onDownloadFailed(throwable);
                    }
                }
        );
        return false;
    };

    private final TaskCountListener mDoubleLaunchPreventionListener = taskCount -> {
        // Hide the notification that starts the game if there are tasks executing.
        // Prevents the user from trying to launch the game with tasks ongoing.
        if(taskCount > 0) {
            Tools.runOnUiThread(() ->
                    mNotificationManager.cancel(NotificationUtils.NOTIFICATION_ID_GAME_START)
            );
        }
    };

    private ActivityResultLauncher<String> mRequestNotificationPermissionLauncher;
    private ActivityResultLauncher<String> mRequestMicrophonePermissionLauncher;
    private WeakReference<Runnable> mRequestNotificationPermissionRunnable;
    private WeakReference<Runnable> mRequestMicrophonePermissionRunnable;

    @Override
    protected boolean shouldIgnoreNotch() {
        return getResources().getConfiguration().orientation == ORIENTATION_PORTRAIT;
    }

    @Override
    public boolean setFullscreen() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean directLaunchReady = prepareDirectLaunchProfile();
        mDirectLaunchMode = directLaunchReady;
        setContentView(R.layout.activity_pojav_launcher);
        FragmentManager fragmentManager = getSupportFragmentManager();
        // If we don't have a back stack root yet...
        if(fragmentManager.getBackStackEntryCount() < 1) {
            // Manually add the first fragment to the backstack to get easily back to it
            // There must be a better way to handle the root though...
            // (artDev: No, there is not. I've spent days researching this for another unrelated project.)
            fragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addToBackStack("ROOT")
                    .add(R.id.container_fragment, MainMenuFragment.class, null, "ROOT").commit();
        }


        IconCacheJanitor.runJanitor();
        mRequestNotificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isAllowed -> {
                    if(!isAllowed) handleNoNotificationPermission();
                    else {
                        Runnable runnable = Tools.getWeakReference(mRequestNotificationPermissionRunnable);
                        if(runnable != null) runnable.run();
                    }
                }
        );
        mRequestMicrophonePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isAllowed -> {
                    if(!isAllowed) handleNoNotificationPermission();
                    else {
                        Runnable runnable = Tools.getWeakReference(mRequestMicrophonePermissionRunnable);
                        if(runnable != null) runnable.run();
                    }
                }
        );
        getWindow().setBackgroundDrawable(null);
        bindViews();
        checkNotificationPermission();
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        ProgressKeeper.addTaskCountListener(mDoubleLaunchPreventionListener);
        ProgressKeeper.addTaskCountListener((mProgressServiceKeeper = new ProgressServiceKeeper(this)));

        mSettingsButton.setOnClickListener(mSettingButtonListener);
        if (!mDirectLaunchMode) ProgressKeeper.addTaskCountListener(mProgressLayout);
        ExtraCore.addExtraListener(ExtraConstants.BACK_PREFERENCE, mBackPreferenceListener);
        ExtraCore.addExtraListener(ExtraConstants.SELECT_AUTH_METHOD, mSelectAuthMethod);

        ExtraCore.addExtraListener(ExtraConstants.LAUNCH_GAME, mLaunchGameListener);

        // The direct launcher uses an already installed, fixed version and does
        // not need to block the Play button on the remote version manifest.
        new AsyncVersionList().getVersionList(versions ->
                ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, versions), false);

        if (directLaunchReady) {
            showDirectLaunchScreen();
            ProgressKeeper.addListener(ProgressLayout.DOWNLOAD_MINECRAFT, mDirectMinecraftProgress);
            ProgressKeeper.addListener(ProgressLayout.UNPACK_RUNTIME, mDirectRuntimeProgress);
            ProgressKeeper.addListener(ProgressLayout.EXTRACT_COMPONENTS, mDirectComponentProgress);
            ProgressKeeper.addListener(ProgressLayout.EXTRACT_SINGLE_FILES, mDirectFilesProgress);
            ProgressKeeper.addTaskCountListener(mDirectSetupTaskCountListener);
        }

        mInstallTracker = new ModloaderInstallTracker(this);

        if (!mDirectLaunchMode) {
            mProgressLayout.observe(ProgressLayout.DOWNLOAD_MINECRAFT);
            mProgressLayout.observe(ProgressLayout.UNPACK_RUNTIME);
            mProgressLayout.observe(ProgressLayout.INSTALL_MODPACK);
            mProgressLayout.observe(ProgressLayout.AUTHENTICATE_MICROSOFT);
            mProgressLayout.observe(ProgressLayout.DOWNLOAD_VERSION_LIST);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startNicknameHint();
        ContextExecutor.setActivity(this);
        mInstallTracker.attach();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopNicknameHint();
        ContextExecutor.clearActivity();
        mInstallTracker.detach();
    }

    @Override
    protected void onStart() {
        super.onStart();
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(mFragmentCallbackListener, true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mProgressLayout.cleanUpObservers();
        ProgressKeeper.removeTaskCountListener(mProgressLayout);
        ProgressKeeper.removeTaskCountListener(mProgressServiceKeeper);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.BACK_PREFERENCE, mBackPreferenceListener);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.SELECT_AUTH_METHOD, mSelectAuthMethod);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.LAUNCH_GAME, mLaunchGameListener);
        ProgressKeeper.removeListener(ProgressLayout.DOWNLOAD_MINECRAFT, mDirectMinecraftProgress);
        ProgressKeeper.removeListener(ProgressLayout.UNPACK_RUNTIME, mDirectRuntimeProgress);
        ProgressKeeper.removeListener(ProgressLayout.EXTRACT_COMPONENTS, mDirectComponentProgress);
        ProgressKeeper.removeListener(ProgressLayout.EXTRACT_SINGLE_FILES, mDirectFilesProgress);
        ProgressKeeper.removeTaskCountListener(mDirectSetupTaskCountListener);

        getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(mFragmentCallbackListener);
    }

    /** Custom implementation to feel more natural when a backstack isn't present */
    @Override
    public void onBackPressed() {
        View nicknames = findViewById(R.id.direct_nicknames_panel);
        if (nicknames != null && nicknames.getVisibility() == View.VISIBLE) {
            closeSavedNicknames();
            return;
        }
        if (mDirectLaunchMode && mDirectSettingsView != null && mDirectSettingsView.getVisibility() == View.VISIBLE) {
            showDirectLaunchScreen();
            return;
        }
        MicrosoftLoginFragment fragment = (MicrosoftLoginFragment) getVisibleFragment(MicrosoftLoginFragment.TAG);
        if(fragment != null){
            if(fragment.canGoBack()){
                fragment.goBack();
                return;
            }
        }

        // Check if we are at the root then
        if(getVisibleFragment("ROOT") != null){
            finish();
        }

        super.onBackPressed();
    }

    @Override
    public void onAttachedToWindow() {
        LauncherPreferences.computeNotchSize(this);
    }

    @SuppressWarnings("SameParameterValue")
    private Fragment getVisibleFragment(String tag){
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(tag);
        if(fragment != null && fragment.isVisible()) {
            return fragment;
        }
        return null;
    }

    @SuppressWarnings("unused")
    private Fragment getVisibleFragment(int id){
        Fragment fragment = getSupportFragmentManager().findFragmentById(id);
        if(fragment != null && fragment.isVisible()) {
            return fragment;
        }
        return null;
    }

    private void checkNotificationPermission() {
        if(LauncherPreferences.PREF_SKIP_NOTIFICATION_PERMISSION_CHECK ||
            checkForNotificationPermission()) {
            return;
        }

        if(ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.POST_NOTIFICATIONS)) {
            showNotificationPermissionReasoning();
            return;
        }
        askForNotificationPermission(null);
    }

    private void showNotificationPermissionReasoning() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.notification_permission_dialog_title)
                .setMessage(R.string.notification_permission_dialog_text)
                .setPositiveButton(android.R.string.ok, (d, w) -> askForNotificationPermission(null))
                .setNegativeButton(android.R.string.cancel, (d, w)-> handleNoNotificationPermission())
                .show();
    }

    private void handleNoNotificationPermission() {
        LauncherPreferences.PREF_SKIP_NOTIFICATION_PERMISSION_CHECK = true;
        LauncherPreferences.DEFAULT_PREF.edit()
                .putBoolean(LauncherPreferences.PREF_KEY_SKIP_NOTIFICATION_CHECK, true)
                .apply();
        Toast.makeText(this, R.string.notification_permission_toast, Toast.LENGTH_LONG).show();
    }

    public boolean checkForNotificationPermission() {
        return Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_DENIED;
    }
    public boolean checkForMicrophonePermission() {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_DENIED;
    }

    public void askForNotificationPermission(Runnable onSuccessRunnable) {
        if(Build.VERSION.SDK_INT < 33) return;
        if(onSuccessRunnable != null) {
            mRequestNotificationPermissionRunnable = new WeakReference<>(onSuccessRunnable);
        }
        mRequestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    public void askForMicrophonePermission(Runnable onSuccessRunnable) {
        if(onSuccessRunnable != null) {
            mRequestMicrophonePermissionRunnable = new WeakReference<>(onSuccessRunnable);
        }
        mRequestMicrophonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
    }

    /** Stuff all the view boilerplate here */
    private void bindViews(){
        mFragmentView = findViewById(R.id.container_fragment);
        mSettingsButton = findViewById(R.id.setting_button);
        mAccountSpinner = findViewById(R.id.account_spinner);
        mProgressLayout = findViewById(R.id.progress_layout);
        mDirectLaunchView = findViewById(R.id.direct_launch_view);
        mDirectProgressContainer = findViewById(R.id.direct_progress_container);
        mDirectNicknameInput = findViewById(R.id.direct_launch_nickname);
        mDirectLaunchButton = findViewById(R.id.direct_launch_button);
        mDirectProgressBar = findViewById(R.id.direct_progress_bar);
        mDirectStatusText = findViewById(R.id.direct_status_text);
        mDirectPercentText = findViewById(R.id.direct_percent_text);
        mDirectSettingsView = findViewById(R.id.direct_settings_view);
        mDirectRamSeekBar = findViewById(R.id.direct_ram_seekbar);
        mDirectRamValue = findViewById(R.id.direct_ram_value);
        mDirectLogoBadge = findViewById(R.id.direct_logo_badge);
        mDirectNicknameCard = findViewById(R.id.direct_nickname_card);
        mDirectPlayCard = findViewById(R.id.direct_play_card);
        findViewById(R.id.direct_saved_nicknames).setOnClickListener(view -> showSavedNicknames());
        mDirectNicknameInput.setOnFocusChangeListener((view, focused) -> {
            if (focused) stopNicknameHint(); else startNicknameHint();
        });
        mDirectNicknameInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() > 0) stopNicknameHint(); else startNicknameHint();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    /**
     * Selects a dedicated 1.21.11 launcher profile. Existing renderer, Java and
     * control settings are copied into it the first time it is created.
     */
    private boolean prepareDirectLaunchProfile() {
        try {
            LauncherProfiles.load();
            MinecraftProfile directProfile = LauncherProfiles.mainProfileJson.profiles.get(DIRECT_LAUNCH_PROFILE_ID);
            if (directProfile == null) {
                String selectedProfileId = LauncherPreferences.DEFAULT_PREF.getString(
                        LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
                MinecraftProfile selectedProfile = LauncherProfiles.mainProfileJson.profiles.get(selectedProfileId);
                directProfile = selectedProfile == null
                        ? MinecraftProfile.getDefaultProfile()
                        : new MinecraftProfile(selectedProfile);
            }

            directProfile.name = DIRECT_LAUNCH_PROFILE_NAME;
            directProfile.lastVersionId = DIRECT_LAUNCH_VERSION;
            LauncherProfiles.mainProfileJson.profiles.put(DIRECT_LAUNCH_PROFILE_ID, directProfile);
            LauncherProfiles.write();
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, DIRECT_LAUNCH_PROFILE_ID)
                    .commit();
            return true;
        } catch (RuntimeException exception) {
            Log.e("GTAMINE", "Failed to prepare direct launch", exception);
            return false;
        }
    }

    private void showDirectLaunchScreen() {
        if (mDirectLaunchView == null) {
            Log.e("GTAMINE", "Direct launch view is missing from the active layout");
            return;
        }
        mAccountSpinner.setVisibility(View.GONE);
        // Keep a dedicated settings button available on the simplified screen.
        mSettingsButton.setVisibility(View.VISIBLE);
        mFragmentView.setVisibility(View.GONE);
        mDirectLaunchView.setVisibility(View.VISIBLE);
        if (mDirectSettingsView != null) mDirectSettingsView.setVisibility(View.GONE);
        animateDirectScreen();

        MinecraftAccount currentAccount = GTAMINEProfile.getCurrentProfileContent(this, null);
        if (currentAccount != null && currentAccount.isLocal()) {
            mDirectNicknameInput.setText(currentAccount.username);
            mDirectNicknameInput.setSelection(currentAccount.username.length());
        }
        mDirectLaunchButton.setOnClickListener(view -> launchWithNickname(mDirectNicknameInput));
        mDirectNicknameInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            launchWithNickname(mDirectNicknameInput);
            return true;
        });
    }

    private void animateDirectScreen() {
        startNicknameHint();
    }

    private void showDirectSettings() {
        stopNicknameHint();
        mDirectLaunchView.setVisibility(View.GONE);
        mDirectSettingsView.setVisibility(View.VISIBLE);
        RadioGroup languages = findViewById(R.id.direct_language_group);
        languages.setOnCheckedChangeListener(null);
        String language = LauncherPreferences.DEFAULT_PREF.getString(MinecraftLanguageDefaults.PREFERENCE_KEY, "ru_ru");
        languages.check("en_us".equals(language) ? R.id.direct_language_english : R.id.direct_language_russian);
        languages.setOnCheckedChangeListener((group, checkedId) -> {
            String selected = checkedId == R.id.direct_language_english ? "en_us" : "ru_ru";
            try {
                MinecraftLanguageDefaults.setLanguage(Tools.getGameDirPath(LauncherProfiles.getCurrentProfile()), selected);
                LauncherPreferences.DEFAULT_PREF.edit().putString(MinecraftLanguageDefaults.PREFERENCE_KEY, selected).apply();
            } catch (IOException exception) {
                Toast.makeText(this, R.string.direct_language_error, Toast.LENGTH_LONG).show();
                group.setOnCheckedChangeListener(null);
                String saved = LauncherPreferences.DEFAULT_PREF.getString(MinecraftLanguageDefaults.PREFERENCE_KEY, "ru_ru");
                group.check("en_us".equals(saved) ? R.id.direct_language_english : R.id.direct_language_russian);
                showDirectSettings();
            }
        });
        int ram = LauncherPreferences.PREF_RAM_ALLOCATION;
        mDirectRamSeekBar.setProgress(Math.max(0, ram - 256));
        mDirectRamValue.setText(getString(R.string.direct_settings_ram_value, ram));
        mDirectRamSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int value = 256 + (progress / 128) * 128;
                mDirectRamValue.setText(getString(R.string.direct_settings_ram_value, value));
                if (fromUser) LauncherPreferences.DEFAULT_PREF.edit().putInt("allocation", value).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { LauncherPreferences.loadPreferences(LauncherActivity.this); }
        });
    }

    public void onDirectSettingsBackClicked(View view) {
        showDirectLaunchScreen();
    }

    public void onNewsButtonClicked(View view) {
        startActivity(new Intent(this, NewsActivity.class));
    }

    private int mNicknameHintStep;
    private boolean mNicknameHintActive;
    private final Runnable mNicknameHint = new Runnable() {
        @Override public void run() {
            if (!mNicknameHintActive || mDirectNicknameInput == null || !mDirectNicknameInput.isShown()
                    || mDirectNicknameInput.hasFocus() || mDirectNicknameInput.length() != 0) return;
            String example = getString(R.string.direct_nickname_example);
            int step = mNicknameHintStep++ % (example.length() + 14);
            mDirectNicknameInput.setHint(step <= example.length()
                    ? example.substring(0, step) + "▏" : getString(R.string.direct_launch_nickname_hint));
            mDirectNicknameInput.postDelayed(this, step <= example.length() ? 110 : 220);
        }
    };

    private void startNicknameHint() {
        if (mDirectNicknameInput == null) return;
        stopNicknameHint();
        View nicknamePanel = findViewById(R.id.direct_nicknames_panel);
        if (nicknamePanel != null && nicknamePanel.getVisibility() == View.VISIBLE) return;
        if (!mDirectNicknameInput.isShown() || mDirectNicknameInput.hasFocus()
                || mDirectNicknameInput.length() != 0 || !mDirectNicknameInput.isEnabled()) return;
        if (Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        android.view.accessibility.AccessibilityManager accessibility =
                (android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (accessibility != null && accessibility.isTouchExplorationEnabled()) return;
        mNicknameHintActive = true;
        mNicknameHintStep = 0;
        mDirectNicknameInput.postDelayed(mNicknameHint, 1200);
    }

    private void stopNicknameHint() {
        mNicknameHintActive = false;
        if (mDirectNicknameInput == null) return;
        mDirectNicknameInput.removeCallbacks(mNicknameHint);
        mDirectNicknameInput.setHint(R.string.direct_launch_nickname_hint);
    }

    private void selectSavedNickname(String nickname) {
        GTAMINEProfile.setCurrentProfile(this, nickname);
        mAccountSpinner.reloadAndSelectCurrentAccount();
        mDirectNicknameInput.setError(null);
        mDirectNicknameInput.setText(nickname);
        mDirectNicknameInput.setSelection(nickname.length());
    }

    private String mEditingSavedNickname;
    private String mDeletingSavedNickname;
    private static final int MAX_SAVED_NICKNAMES = 3;

    private boolean isNicknameListFull() {
        int count = 0;
        for (MinecraftAccount account : GTAMINEProfile.getAllProfiles()) {
            if (account.isLocal()) count++;
        }
        return count >= MAX_SAVED_NICKNAMES;
    }

    private void showSavedNicknames() {
        if (!mDirectNicknameInput.isEnabled()) return;
        stopNicknameHint();
        mEditingSavedNickname = null;
        mDeletingSavedNickname = null;
        View panel = findViewById(R.id.direct_nicknames_panel);
        panel.setVisibility(View.VISIBLE);
        EditText editor = findViewById(R.id.direct_nickname_editor);
        editor.setText("");
        editor.clearFocus();
        findViewById(R.id.direct_nickname_error).setVisibility(View.GONE);
        findViewById(R.id.direct_nickname_delete_confirmation).setVisibility(View.GONE);
        findViewById(R.id.direct_nicknames_close).setOnClickListener(view -> closeSavedNicknames());
        findViewById(R.id.direct_nickname_save).setOnClickListener(view -> saveNicknameInPanel());
        findViewById(R.id.direct_nickname_delete_confirm).setOnClickListener(view -> confirmNicknameDeletion());
        findViewById(R.id.direct_nickname_delete_cancel).setOnClickListener(view -> {
            mDeletingSavedNickname = null;
            findViewById(R.id.direct_nickname_delete_confirmation).setVisibility(View.GONE);
        });
        editor.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            saveNicknameInPanel();
            return true;
        });
        refreshSavedNicknameRows();
    }

    private void closeSavedNicknames() {
        View panel = findViewById(R.id.direct_nicknames_panel);
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(panel.getWindowToken(), 0);
        findViewById(R.id.direct_nickname_editor).clearFocus();
        panel.setVisibility(View.GONE);
        mEditingSavedNickname = null;
        mDeletingSavedNickname = null;
        startNicknameHint();
    }

    private void showNicknameError(int message) {
        TextView error = findViewById(R.id.direct_nickname_error);
        error.setText(message);
        error.setVisibility(View.VISIBLE);
    }

    private void refreshSavedNicknameRows() {
        android.widget.LinearLayout list = findViewById(R.id.direct_nickname_list);
        list.removeAllViews();
        ArrayList<String> names = new ArrayList<>();
        for (MinecraftAccount account : GTAMINEProfile.getAllProfiles()) {
            if (account.isLocal() && account.username.matches("^[a-zA-Z0-9_]{3,16}$")) names.add(account.username);
        }
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        String selected = GTAMINEProfile.getCurrentProfileName(this);
        for (String name : names) {
            View row = getLayoutInflater().inflate(R.layout.item_saved_nickname, list, false);
            TextView label = row.findViewById(R.id.saved_nickname_name);
            label.setText(name);
            label.setTextColor(android.graphics.Color.parseColor(name.equals(selected) ? "#8EF0A5" : "#FFFFFF"));
            label.setOnClickListener(view -> { selectSavedNickname(name); closeSavedNicknames(); });
            View edit = row.findViewById(R.id.saved_nickname_edit);
            edit.setContentDescription(getString(R.string.direct_nickname_edit) + ": " + name);
            edit.setOnClickListener(view -> {
                mEditingSavedNickname = name;
                mDeletingSavedNickname = null;
                findViewById(R.id.direct_nickname_delete_confirmation).setVisibility(View.GONE);
                findViewById(R.id.direct_nickname_error).setVisibility(View.GONE);
                EditText editor = findViewById(R.id.direct_nickname_editor);
                editor.setText(name);
                editor.setSelection(name.length());
                editor.requestFocus();
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
            });
            View delete = row.findViewById(R.id.saved_nickname_delete);
            delete.setContentDescription(getString(R.string.direct_nickname_delete) + ": " + name);
            delete.setOnClickListener(view -> {
                mDeletingSavedNickname = name;
                findViewById(R.id.direct_nickname_error).setVisibility(View.GONE);
                ((TextView) findViewById(R.id.direct_nickname_delete_text))
                        .setText(getString(R.string.direct_nickname_delete_message, name));
                findViewById(R.id.direct_nickname_delete_confirmation).setVisibility(View.VISIBLE);
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(view.getWindowToken(), 0);
            });
            list.addView(row);
        }
        findViewById(R.id.direct_nickname_empty).setVisibility(names.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void saveNicknameInPanel() {
        EditText editor = findViewById(R.id.direct_nickname_editor);
        String name = editor.getText().toString().trim();
        if (!name.matches("^[a-zA-Z0-9_]{3,16}$")) {
            showNicknameError(R.string.direct_launch_invalid_nickname);
            return;
        }
        if (name.equals(mEditingSavedNickname)) {
            selectSavedNickname(name);
            closeSavedNicknames();
            return;
        }
        for (String existing : GTAMINEProfile.getAllProfilesList()) {
            if (existing.equalsIgnoreCase(name)) {
                showNicknameError(R.string.direct_nickname_exists);
                return;
            }
        }
        if (mEditingSavedNickname == null && isNicknameListFull()) {
            showNicknameError(R.string.direct_nickname_limit);
            return;
        }
        try {
            File directory = new File(Tools.DIR_ACCOUNT_NEW);
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create nickname directory");
            MinecraftAccount account = MinecraftAccount.createLocal(name);
            if (mEditingSavedNickname != null) {
                MinecraftAccount old = MinecraftAccount.load(mEditingSavedNickname);
                if (old == null || !old.isLocal()) throw new IOException("Local nickname no longer exists");
                account.selectedVersion = old.selectedVersion;
            }
            account.save();
            if (mEditingSavedNickname != null && !new File(directory, mEditingSavedNickname + ".json").delete()) {
                if (!new File(directory, name + ".json").delete()) Log.w("GTAMINE", "Could not roll back nickname copy");
                throw new IOException("Cannot remove old nickname");
            }
            selectSavedNickname(name);
            closeSavedNicknames();
        } catch (IOException exception) {
            showNicknameError(R.string.direct_nickname_error);
        }
    }

    private void confirmNicknameDeletion() {
        String name = mDeletingSavedNickname;
        if (name == null) return;
        MinecraftAccount account = MinecraftAccount.load(name);
        if (account == null || !account.isLocal() || !new File(Tools.DIR_ACCOUNT_NEW, name + ".json").delete()) {
            showNicknameError(R.string.direct_nickname_error);
            return;
        }
        if (name.equals(GTAMINEProfile.getCurrentProfileName(this))) GTAMINEProfile.setCurrentProfile(this, null);
        if (name.equals(mDirectNicknameInput.getText().toString().trim())) mDirectNicknameInput.setText("");
        if (name.equals(mEditingSavedNickname)) {
            mEditingSavedNickname = null;
            ((EditText) findViewById(R.id.direct_nickname_editor)).setText("");
        }
        mDeletingSavedNickname = null;
        findViewById(R.id.direct_nickname_delete_confirmation).setVisibility(View.GONE);
        mAccountSpinner.reloadAndSelectCurrentAccount();
        refreshSavedNicknameRows();
    }

    private void launchWithNickname(EditText nicknameInput) {
        String nickname = nicknameInput.getText().toString().trim();
        if (!nickname.matches("^[a-zA-Z0-9_]{3,16}$")) {
            nicknameInput.setError(getString(R.string.direct_launch_invalid_nickname));
            return;
        }

        try {
            File accountDirectory = new File(Tools.DIR_ACCOUNT_NEW);
            if (!accountDirectory.exists() && !accountDirectory.mkdirs()) {
                throw new IOException("Unable to create local account directory");
            }

            MinecraftAccount localAccount = MinecraftAccount.load(nickname);
            if (localAccount != null && !localAccount.isLocal()) {
                nicknameInput.setError(getString(R.string.direct_launch_online_name_conflict));
                return;
            }
            if (localAccount == null) {
                if (isNicknameListFull()) {
                    showSavedNicknames();
                    showNicknameError(R.string.direct_nickname_limit);
                    return;
                }
                localAccount = MinecraftAccount.createLocal(nickname);
                localAccount.save();
            }

            GTAMINEProfile.setCurrentProfile(this, nickname);
            mAccountSpinner.reloadAndSelectCurrentAccount();
            InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            keyboard.hideSoftInputFromWindow(nicknameInput.getWindowToken(), 0);
            nicknameInput.clearFocus();
            nicknameInput.setEnabled(false);
            findViewById(R.id.direct_saved_nicknames).setEnabled(false);
            mSettingsButton.setEnabled(false);
            mDirectLaunchButton.setEnabled(false);
            mDirectLaunchRequested = true;
            mDirectLaunchButton.setText(R.string.direct_launch_preparing);
            mDirectProgressContainer.setVisibility(View.VISIBLE);
            mDirectProgressBar.setIndeterminate(true);
            mDirectStatusText.setText(R.string.direct_status_checking);
            mDirectPercentText.setText("");
            // Component extraction starts before LauncherActivity. Waiting here
            // prevents a quick tap from being rejected as "tasks ongoing" and
            // guarantees that LWJGL natives are present before Minecraft starts.
            ProgressKeeper.waitUntilDone(() -> runOnUiThread(() ->
                    ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true)));
        } catch (IOException exception) {
            Log.e("GTAMINE", "Failed to save local account", exception);
            Toast.makeText(this, R.string.direct_launch_account_error, Toast.LENGTH_LONG).show();
        }
    }

    private final class DirectProgressListener implements ProgressListener {
        private final int fallbackStatus;
        private final boolean finalStage;

        private DirectProgressListener(int fallbackStatus, boolean finalStage) {
            this.fallbackStatus = fallbackStatus;
            this.finalStage = finalStage;
        }

        @Override
        public void onProgressStarted() {
            runOnUiThread(() -> {
                if (!mDirectLaunchMode || mDirectProgressContainer == null) return;
                mDirectProgressContainer.setVisibility(View.VISIBLE);
                mDirectProgressBar.setIndeterminate(true);
                mDirectStatusText.setText(fallbackStatus);
                mDirectPercentText.setText("");
            });
        }

        @Override
        public void onProgressUpdated(int progress, int resid, Object... values) {
            runOnUiThread(() -> {
                if (!mDirectLaunchMode || mDirectProgressContainer == null) return;
                String status = getString(fallbackStatus);
                try {
                    if (resid != -1) status = getString(resid, values);
                    else if (values.length > 0 && values[0] instanceof String) status = (String) values[0];
                } catch (RuntimeException exception) {
                    Log.w("GTAMINE", "Unable to format progress message", exception);
                }
                mDirectStatusText.setText(status);
                if (progress >= 0) {
                    int safeProgress = Math.max(0, Math.min(100, progress));
                    mDirectProgressBar.setIndeterminate(false);
                    mDirectProgressBar.setProgress(safeProgress);
                    mDirectPercentText.setText(getString(R.string.direct_progress_percent, safeProgress));
                } else {
                    mDirectProgressBar.setIndeterminate(true);
                    mDirectPercentText.setText("");
                }
            });
        }

        @Override
        public void onProgressEnded() {
            runOnUiThread(() -> {
                if (!mDirectLaunchMode || mDirectProgressContainer == null) return;
                if (!mDirectLaunchRequested && ProgressKeeper.getTaskCount() == 0) {
                    mDirectProgressContainer.setVisibility(View.INVISIBLE);
                    return;
                }
                mDirectProgressBar.setIndeterminate(true);
                mDirectPercentText.setText("");
                mDirectStatusText.setText(finalStage
                        ? R.string.direct_status_launching
                        : R.string.direct_status_finishing);
                if (finalStage && !mDirectLaunchRequested) {
                    mDirectNicknameInput.setEnabled(true);
                    findViewById(R.id.direct_saved_nicknames).setEnabled(true);
                    mSettingsButton.setEnabled(true);
                    mDirectLaunchButton.setEnabled(true);
                    mDirectLaunchButton.setText(R.string.direct_launch_button);
                }
            });
        }
    }
}
