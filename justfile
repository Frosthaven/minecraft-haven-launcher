# Haven launcher: the same commands on Windows, macOS and Linux (JDK 21+).
set windows-shell := ["powershell.exe", "-NoLogo", "-NoProfile", "-Command"]

# Build build/haven-launcher.jar (one jar: our classes plus FlatLaf from lib/).
build:
    javac --release 21 -cp lib/flatlaf-3.7.2.jar -d build/classes src/dev/thedragon/havenlauncher/HavenLauncher.java src/dev/thedragon/havenlauncher/LauncherWindow.java src/dev/thedragon/havenlauncher/Ui.java src/dev/thedragon/havenlauncher/ImportDialog.java src/dev/thedragon/havenlauncher/OldInstalls.java src/dev/thedragon/havenlauncher/OptionalMods.java src/dev/thedragon/havenlauncher/Updater.java src/dev/thedragon/havenlauncher/OfflineEditor.java src/dev/thedragon/havenlauncher/LoaderSync.java src/dev/thedragon/havenlauncher/SelfUpdate.java
    java tools/Merge.java lib/flatlaf-3.7.2.jar build/classes
    jar --create --file build/haven-launcher.jar --main-class dev.thedragon.havenlauncher.HavenLauncher -C build/classes .

# Build, then run the import, options, offline editor, NeoForge and self-update tests against a fake player's computer.
test: build
    java tests/ImportTest.java build/haven-launcher.jar
    java tests/OptionsTest.java build/haven-launcher.jar
    java tests/OfflineEditorTest.java build/haven-launcher.jar
    java tests/LoaderSyncTest.java build/haven-launcher.jar
    java tests/SelfUpdateTest.java build/haven-launcher.jar
