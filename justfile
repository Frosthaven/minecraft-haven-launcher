# Haven launcher: the same commands on Windows, macOS and Linux (JDK 21+).
set windows-shell := ["powershell.exe", "-NoLogo", "-NoProfile", "-Command"]

# Build build/haven-launcher.jar.
build:
    javac --release 21 -d build/classes src/dev/thedragon/havenlauncher/HavenLauncher.java src/dev/thedragon/havenlauncher/OldInstalls.java src/dev/thedragon/havenlauncher/OfflineEditor.java src/dev/thedragon/havenlauncher/LoaderSync.java src/dev/thedragon/havenlauncher/SelfUpdate.java
    jar --create --file build/haven-launcher.jar --main-class dev.thedragon.havenlauncher.HavenLauncher -C build/classes .

# Build, then run the import, offline editor, NeoForge and self-update tests against a fake player's computer.
test: build
    java tests/ImportTest.java build/haven-launcher.jar
    java tests/OfflineEditorTest.java build/haven-launcher.jar
    java tests/LoaderSyncTest.java build/haven-launcher.jar
    java tests/SelfUpdateTest.java build/haven-launcher.jar
