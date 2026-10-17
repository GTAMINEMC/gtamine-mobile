# LWJGL component delivery

Pressing Play prepares LWJGL inside MinecraftDownloader's existing download task,
after normal game libraries and before MainActivity starts. Opening the launcher
does not download LWJGL. Both online and offline launch paths verify installed files.

The APK keeps its locally patched merged GLFW/OpenGL modules. Other Java modules
and the native AAR come from the original AngelAuraMC/Amethyst-Android repository,
pinned to commit `330c6eae3164df64bdc4828e946a9e62cc5169e4`.
The upstream AAR contains all ABIs, so its first download is roughly 16 MB;
only the current device's ABI is installed and the archive is then removed.
Only the selected game's LWJGL version is prepared (3.3.3 or 3.4.1).

`gradle/lwjgl_downloads.gradle` builds a manifest from the local source binaries,
including SHA-256 and exact sizes, and prepares filtered APK assets. Native AARs
remain in `app_pojavlauncher/libs` for manifest generation but are excluded from
application dependencies. Original source files are preserved for reproducibility.

The manifest is packaged inside the signed APK, never downloaded. Downloads use
HTTPS, bounded sizes, up to three attempts, temporary files and SHA-256 checks
before replacement. Native extraction uses only explicitly listed manifest entries.
Valid existing files are reused, including installations from older bundled builds.
If a file is missing or corrupt offline, launch is blocked until it can be repaired.

When upgrading original LWJGL binaries, update the pinned upstream commit together
with local JARs/AARs and verify that they match. Locally modified modules must stay
bundled unless an identical version is published to a controlled source.

Build normally, then run `scripts/test-lwjgl-components.ps1` with JAVA_HOME pointing
to JDK 21. Tests use production installation code with filesystem Android adapters:
real HTTPS download, offline reuse, repair, both versions/all four ABIs, checksum
failures, oversized files and temporary-file cleanup. Device graphics/JNI execution
still needs an Android phone or emulator.
