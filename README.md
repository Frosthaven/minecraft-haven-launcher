# minecraft-haven-launcher

The Haven Prism instance's pre-launch step: `"$INST_JAVA" -jar haven-launcher.jar <pack.toml URL>`.

0. It keeps itself up to date (`SelfUpdate`): the pack delivers the newest launcher to `haven/haven-launcher.jar`; when
   it differs from this jar, it is copied to `haven/run/<hash>.jar` and that copy runs instead (same JVM options and
   arguments), so packwiz can replace the delivered file during the update even on Windows.
1. It opens the launcher window (`LauncherWindow`, Swing with FlatLaf): the pack's title art
   (`config/fancymenu/assets/1x/bg_haven.png`, delivered by the pack; a plain band before the first update), the
   update card (headline, progress, current file, release) and the footer: **Online Map** (the server's map, behind
   the Haven login), **Import settings**, **Optional mods…**, **Play now**. Play now is off while the update runs and
   starts the game when pressed (exit 0); there is no countdown. Closing the window stops the launch (exit 1), as a
   failed update does. Without a screen (tests, servers) everything prints to the console and the installer's exit
   code is the launcher's. `-Dhaven.test.action=play|close` presses the button or closes the window a second after the
   update, for checks with a screen; a file `haven/autoplay` in the game folder does the same once and is deleted (for
   checks that start the game through Prism).
2. On the first launch only, it offers to import the player's files from another Minecraft install (`OldInstalls`,
   `ImportDialog`): Prism (every sibling of this instance, the known roots, the Flatpak), the CurseForge app, the
   Modrinth app and the vanilla launcher, on Windows, macOS and Linux, plus any folder through Browse…. One checkbox
   per item (controls and settings, server list, screenshots, Create schematics, Axiom blueprints, JourneyMap, FTB
   Chunks, JEI bookmarks, single-player worlds), greyed out when the install lacks it; from a different modpack the
   items tied to that pack's worlds are off by default. It only copies: a folder adds what Haven does not have, a
   single file replaces; `servers.dat` only when it lists Haven; `options.txt` from another pack loses its resource pack
   lines. `haven-import.done` in the game folder stops the first-launch prompt; the footer button asks any time.
3. It keeps the instance on the pack's NeoForge without a question (`LoaderSync`): it writes pack.toml's NeoForge into
   Prism's `mmc-pack.json` (same Minecraft only) and starts a short background helper, because Prism writes its own
   copy back about two seconds after the game starts. That session runs on the old NeoForge; the next launch uses the
   new one. No dialog, no extra relaunch.
4. Then it runs `packwiz-installer-bootstrap.jar` (next to it) with `-g` and the pack URL and shows the installer's
   progress (`Updater`). Without its window the installer turns every optional file it meets for the first time on,
   so after the run the launcher puts those back to the pack's `[option] default` (`OptionalMods`, read from the
   `.pw.toml` on the update host), asks with the Optional mods dialog on the first launch, and runs the installer again
   when anything changed. Choices live in `packwiz.json` (`optionValue`), as with packwiz's own window. `config/haven-os-mods.txt` in
   the pack limits optional mods to one system (`macos mods/x.pw.toml`): elsewhere the mod is held off after every
   update, so the installer removes it, and it is left out of the dialog; on its own system it is turned on once
   (`haven-os-mods.applied`), then the player's choice stands. Its pack default is off, for launchers before 2.1.1.
   A changed choice clears `packFileHash` and `indexFileHash` in `packwiz.json` before the second installer pass: with
   both unchanged the installer skips the pass ("Modpack is already up to date!"), and up to 2.1.1 the change waited for
   the next release.
5. After a good update it adds or removes the "Offline Editor (Axiom)" bridge mods (`OfflineEditor`): the pack's
   `config/haven-offline-editor.txt` lists them (file, SHA-512, URL); they are installed while a `mods/Axiom-*.jar` is
   present and removed when it is gone. A NeoForge switch session leaves them as they are.
6. The release line: the update host publishes `haven/release.json` next to `pack.toml` (minecraft-haven-updates);
   the launcher shows `Haven <version>`, `Updating to Haven <version> (from <installed>)` while it updates, and keeps
   the installed one in `haven/installed-release.txt`. Without that file the line stays empty.

`just build`, `just test` (JDK 21+). The build merges `lib/flatlaf-3.7.2.jar` (Apache-2.0, checksum in
`lib/flatlaf-3.7.2.jar.sha1`) into the one launcher jar with `tools/Merge.java`. Every push to main publishes a release
`v<VERSION>-<commit>`; the Haven pack (`minecraft-haven-pack`) pins one by hash (`just bump-mod haven-launcher`) and its
update host ships it in `Haven.zip`.
