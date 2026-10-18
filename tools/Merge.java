import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Build helper: unpacks a library jar into the class folder, so `jar --create` makes one launcher jar.
 * Skips the library's manifest, module descriptor and signatures; keeps its licence as META-INF/LICENSE-&lt;name&gt;.
 * Run: java tools/Merge.java lib/flatlaf-3.7.2.jar build/classes
 */
public class Merge {
    public static void main(String[] args) throws IOException {
        Path jar = Path.of(args[0]);
        Path out = Path.of(args[1]).toAbsolutePath().normalize();
        String name = jar.getFileName().toString().replaceAll("-\\d.*$", "");
        int files = 0;
        try (InputStream in = Files.newInputStream(jar); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String n = e.getName();
                if (e.isDirectory() || n.equals("META-INF/MANIFEST.MF") || n.endsWith("module-info.class")
                        || n.startsWith("META-INF/versions/") || n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA")) {
                    continue;
                }
                if (n.equals("META-INF/LICENSE")) {
                    n = "META-INF/LICENSE-" + name;
                }
                Path target = out.resolve(n).normalize();
                if (!target.startsWith(out)) {
                    continue;   // a zip entry that points outside the folder
                }
                Files.createDirectories(target.getParent());
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                files++;
            }
        }
        System.out.println("merged " + files + " files from " + jar.getFileName());
    }
}
