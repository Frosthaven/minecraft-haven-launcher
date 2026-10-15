# minecraft-haven-launcher

The Haven Prism instance's pre-launch step: `"$INST_JAVA" -jar haven-launcher.jar <pack.toml URL>`.

1. On the first launch only, it looks for an old Create Ultimate Selection 2 install (the CurseForge app on Windows and
   macOS, Prism on every system, including the Flatpak and the player's other Prism instances) and offers, in one
   window, to copy the player's map and waypoints (JourneyMap), Create schematics, keybinds and settings
   (`options.txt`), JEI bookmarks, screenshots, single-player worlds, and the server list when it lists Haven. It only
   copies; the old install is never changed. `haven-import.done` in the game folder stops it for good.
2. It keeps the instance on the pack's NeoForge without a question (`LoaderSync`): it writes pack.toml's NeoForge into
   Prism's `mmc-pack.json` (same Minecraft only) and starts a short background helper, because Prism writes its own
   copy back about two seconds after the game starts. That session runs on the old NeoForge; the next launch uses the
   new one. No dialog, no extra relaunch.
3. Then it runs `packwiz-installer-bootstrap.jar` (next to it) with the pack URL, which updates the pack, and exits with
   its exit code.
4. After a good update it adds or removes the "Offline Editor (Axiom)" bridge mods (`OfflineEditor`): the pack's
   `config/haven-offline-editor.txt` lists them (file, SHA-512, URL); they are installed while a `mods/Axiom-*.jar` is
   present and removed when it is gone. A NeoForge switch session leaves them as they are.

`just build`, `just test` (JDK 21+). Every push to main publishes a release `v<VERSION>-<commit>`; the Haven pack
(`minecraft-haven-pack`) pins one by hash and its update host ships it in `Haven.zip`.
