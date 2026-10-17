$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskTests = Join-Path $taskRoot 'build/lwjgl-tests'
$taskSources = Join-Path $taskTests 'src'
$taskClasses = Join-Path $taskTests 'classes'
$taskStubs = @{
    'android/content/Context.java' = 'package android.content; import java.io.*; public class Context { private final File assets,cache; public Context(File a,File c){assets=a;cache=c;cache.mkdirs();} public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager(assets);} public File getCacheDir(){return cache;} public String getString(int id){return "component download failed";} }'
    'android/content/res/AssetManager.java' = 'package android.content.res; import java.io.*; public class AssetManager { private final File root; public AssetManager(File r){root=r;} public InputStream open(String name)throws IOException{return new FileInputStream(new File(root,name));} }'
    'androidx/annotation/Keep.java' = 'package androidx.annotation; public @interface Keep {}'
    'com/gtamine/mobile/Tools.java' = 'package com.gtamine.mobile; public class Tools { public static String DIR_GAME_HOME,DIR_DATA; public static final com.google.gson.Gson GLOBAL_GSON=new com.google.gson.Gson(); }'
    'com/gtamine/mobile/Architecture.java' = 'package com.gtamine.mobile; public class Architecture { public static String abi; public static int getDeviceArchitecture(){return 1;} public static String archAsStringAndroid(int n){return abi;} }'
    'com/gtamine/mobile/JMinecraftVersionList.java' = 'package com.gtamine.mobile; public class JMinecraftVersionList { public static class Version { public com.gtamine.mobile.value.DependentLibrary[] libraries; } }'
    'com/gtamine/mobile/value/DependentLibrary.java' = 'package com.gtamine.mobile.value; public class DependentLibrary { public String name; }'
    'com/gtamine/mobile/R.java' = 'package com.gtamine.mobile; public class R { public static class string { public static final int direct_lwjgl_internet_required=1,direct_lwjgl_download_failed=2,direct_lwjgl_downloading=3; } }'
    'com/kdt/mcgui/ProgressLayout.java' = 'package com.kdt.mcgui; public class ProgressLayout { public static String DOWNLOAD_MINECRAFT="download"; public static void setProgress(String task,int p,int id,Object... args){if(p<0||p>100)throw new AssertionError("invalid progress");} }'
}
foreach ($taskEntry in $taskStubs.GetEnumerator()) {
    $taskPath = Join-Path $taskSources $taskEntry.Key
    New-Item -ItemType Directory -Force -Path (Split-Path $taskPath -Parent) | Out-Null
    [IO.File]::WriteAllText($taskPath, $taskEntry.Value)
}
New-Item -ItemType Directory -Force -Path $taskClasses | Out-Null
$taskJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
$taskJavac = Join-Path (Split-Path $taskJava -Parent) 'javac.exe'
$taskGson = Join-Path $taskRoot 'app_pojavlauncher/libs/gson-2.8.6.jar'
$taskJavaFiles = @((Get-ChildItem $taskSources -Recurse -Filter '*.java').FullName)
$taskJavaFiles += Join-Path $taskRoot 'app_pojavlauncher/src/main/java/com/gtamine/mobile/utils/VerifiedComponentFiles.java'
$taskJavaFiles += Join-Path $taskRoot 'app_pojavlauncher/src/main/java/com/gtamine/mobile/tasks/LwjglComponentInstaller.java'
$taskJavaFiles += Join-Path $taskRoot 'tests/LwjglComponentInstallerTest.java'
& $taskJavac -cp $taskGson -d $taskClasses $taskJavaFiles
if ($LASTEXITCODE -ne 0) { throw 'Component test compilation failed' }
& $taskJava -cp "$taskClasses;$taskGson" com.gtamine.mobile.tasks.LwjglComponentInstallerTest $taskRoot
if ($LASTEXITCODE -ne 0) { throw 'Component installation tests failed' }
