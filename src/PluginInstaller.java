/*
 * Offline plugin installer for IntelliJ IDEA.
 *
 * - Uses only the JDK standard library (Swing). No external dependencies.
 * - Never opens a network connection: everything is read from catalog.json, plugins/ and docs/images/.
 * - Runs on Java 11+; meant to be started with the Java runtime bundled with IntelliJ IDEA (jbr/).
 * - Works on Linux and Windows (launchers: plugin-installer.sh, plugin-installer.cmd).
 * - User interface in English (default) and Turkish; switch from the window.
 *
 * Build:  javac --release 11 -encoding UTF-8 -d build src/PluginInstaller.java
 *         jar --create --file plugin-installer.jar --main-class PluginInstaller -C build .
 */

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class PluginInstaller {

    // ------------------------------------------------------------------ language

    static String lang = "en";

    /** Returns the English or Turkish text depending on the current language. */
    static String L(String en, String tr) { return "tr".equals(lang) ? tr : en; }

    static String pick(Map<String, String> m) {
        if (m == null) return "";
        String v = m.get(lang);
        return v != null ? v : m.getOrDefault("en", "");
    }

    static Preferences prefs() {
        try { return Preferences.userRoot().node("intellij-offline-plugin-pack"); } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ model

    enum Status { NEW, INSTALLED, UPDATE, COPIED, MISSING, INCOMPATIBLE }

    static final class Text {
        String summary, why, changes;
        List<String> usage = new ArrayList<>(), requirements = new ArrayList<>();
    }

    static final class Plugin {
        String id, version, pluginVersion, pluginId, file, type, category, offline, platform, sinceBuild, untilBuild;
        String sha256;                   // SHA-256 of the complete zip (used to verify joined parts)
        int parts;                       // > 0: the zip is stored as <file>.001, <file>.002, ... (GitHub 100 MB limit)
        long catalogSize, catalogUnpacked;
        String catalogZipRoot;
        Map<String, String> name = new HashMap<>();
        boolean recommended, optional;
        List<String> images = new ArrayList<>(), files = new ArrayList<>();
        Map<String, Text> text = new HashMap<>();
        // computed at runtime
        String zipRoot;                  // top-level folder inside the zip
        long zipSize, unpackedSize;      // bytes
        boolean fileOk;
        Path installedDir;
        String installedVersion;
        boolean compatible = true;
        boolean selected;

        boolean isRules() { return "rules".equals(type); }
        String name() { return pick(name); }
        Text text() { Text t = text.get(lang); return t != null ? t : text.get("en"); }

        Status status() {
            if (!fileOk) return Status.MISSING;
            if (isRules()) return installedDir != null ? Status.COPIED : Status.NEW;
            if (!compatible) return Status.INCOMPATIBLE;
            if (installedDir == null) return Status.NEW;
            if (Objects.equals(installedVersion, pluginVersion)) return Status.INSTALLED;
            return Status.UPDATE;
        }
        boolean selectable() { return fileOk && (isRules() || compatible); }
    }

    static String statusText(Status s) {
        switch (s) {
            case NEW: return L("New", "Yeni");
            case INSTALLED: return L("Installed", "Kurulu");
            case UPDATE: return L("Update", "Güncellenecek");
            case COPIED: return L("Copied", "Kopyalanmış");
            case MISSING: return L("File missing", "Dosya eksik");
            default: return L("Incompatible", "Uyumsuz");
        }
    }

    static Color statusColor(Status s) {
        switch (s) {
            case INSTALLED: case COPIED: return new Color(0x2e8b57);
            case UPDATE: return new Color(0xb9770e);
            case MISSING: case INCOMPATIBLE: return new Color(0xc0392b);
            default: return Color.DARK_GRAY;
        }
    }

    static String offlineText(String o) {
        if ("full".equals(o)) return "✔ " + L("Yes", "Tam");
        if ("partial".equals(o)) return "⚠ " + L("Partly", "Koşullu");
        return "✖ " + L("Needs internet", "İnternet ister");
    }

    final Path root;
    final List<Plugin> plugins = new ArrayList<>();
    final List<String> categoryOrder = new ArrayList<>();
    final Map<String, Map<String, String>> categoryNames = new HashMap<>();
    Map<String, String> title = new HashMap<>();
    Path ideaHome;
    String ideaVersion, ideaBuild, dataDirName;

    // ------------------------------------------------------------------ UI fields

    JFrame frame;
    JTextField targetField;
    JLabel infoLabel, summaryLabel;
    JTable table;
    PluginTableModel tableModel;
    TableRowSorter<PluginTableModel> sorter;
    JTextField searchField;
    JCheckBox showOptional;
    JEditorPane details;
    ImagePanel imagePanel;
    JLabel imageCounter;
    JButton prevImage, nextImage, enlargeButton, installButton;
    Plugin shown;
    int imageIndex;
    int[] ideBuildForTarget;  // build of the IDE that owns the target directory, or null if unknown
    boolean snapshotMode;     // --snapshot: render the window to PNG files without showing it (used for README images)
    String lastPreviewHtml;
    boolean optionalVisible;

    public static void main(String[] args) {
        Map<String, String> param = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length && !args[i + 1].startsWith("--")) param.put(args[i].substring(2), args[++i]);
            else if (args[i].startsWith("--")) param.put(args[i].substring(2), "true");
        }
        // legacy Turkish option names
        if (param.containsKey("kok")) param.putIfAbsent("root", param.get("kok"));
        if (param.containsKey("hedef")) param.putIfAbsent("target", param.get("hedef"));
        if (param.containsKey("help")) {
            System.out.println("Usage: java -jar plugin-installer.jar [--root PACKAGE_DIR] [--idea-home IDEA_DIR] [--target PLUGIN_DIR] [--lang en|tr] [--test]");
            return;
        }
        Preferences p = prefs();
        String saved = p == null ? null : p.get("lang", null);
        lang = param.containsKey("lang") ? param.get("lang") : (saved != null ? saved : "en");
        if (!"tr".equals(lang)) lang = "en";

        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        Path root = param.containsKey("root") ? Paths.get(param.get("root")) : defaultRoot();
        PluginInstaller app = new PluginInstaller(root.toAbsolutePath().normalize());
        try {
            app.loadCatalog();
        } catch (Exception e) {
            String msg = "catalog.json could not be read:\n" + root.resolve("catalog.json") + "\n\n" + e;
            if (GraphicsEnvironment.isHeadless()) System.err.println(msg);
            else JOptionPane.showMessageDialog(null, msg, "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        if (param.containsKey("idea-home")) app.readIdeaHome(Paths.get(param.get("idea-home")));
        Path target = param.containsKey("target") ? Paths.get(param.get("target")) : app.guessTarget();

        if (param.containsKey("test")) System.exit(app.selfTest(target) ? 0 : 1);
        if (param.containsKey("snapshot")) {
            String prefix = param.get("snapshot");
            List<String> sel = Arrays.asList(param.getOrDefault("select", "").split(","));
            String show = param.get("show");
            try {
                SwingUtilities.invokeAndWait(() -> app.snapshot(target, prefix, sel, show));
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(1);
            }
            System.exit(0);
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("No graphical environment (DISPLAY is not set). Use " + (isWindows() ? "install-plugins.cmd" : "install-plugins.sh") + " instead.");
            System.exit(2);
        }
        SwingUtilities.invokeLater(() -> app.buildUi(target));
    }

    PluginInstaller(Path root) { this.root = root; }

    static Path defaultRoot() {
        try {
            Path jar = Paths.get(PluginInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(jar) ? jar : jar.getParent();
            for (Path p = dir; p != null; p = p.getParent()) if (Files.exists(p.resolve("catalog.json"))) return p;
            return dir;
        } catch (URISyntaxException | SecurityException e) {
            return Paths.get(".");
        }
    }

    // ------------------------------------------------------------------ catalog

    @SuppressWarnings("unchecked")
    void loadCatalog() throws IOException {
        String s = new String(Files.readAllBytes(root.resolve("catalog.json")), StandardCharsets.UTF_8);
        Map<String, Object> json = (Map<String, Object>) new Json(s).parse();
        title = strMap(json.get("title"));
        for (Object o : (List<Object>) json.getOrDefault("categories", Collections.emptyList())) {
            Map<String, Object> c = (Map<String, Object>) o;
            String id = str(c, "id");
            categoryOrder.add(id);
            categoryNames.put(id, strMap(c));
        }
        for (Object o : (List<Object>) json.get("plugins")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Plugin p = new Plugin();
            p.id = str(m, "id"); p.version = str(m, "version"); p.pluginVersion = str(m, "pluginVersion"); p.pluginId = str(m, "pluginId");
            p.file = str(m, "file"); p.type = str(m, "type"); p.category = str(m, "category"); p.offline = str(m, "offline");
            p.platform = str(m, "platform"); p.sinceBuild = str(m, "sinceBuild"); p.untilBuild = str(m, "untilBuild");
            p.sha256 = str(m, "sha256"); p.catalogZipRoot = str(m, "zipRoot");
            p.parts = num(m, "parts"); p.catalogSize = lnum(m, "size"); p.catalogUnpacked = lnum(m, "unpackedSize");
            p.name = strMap(m.get("name"));
            p.recommended = Boolean.TRUE.equals(m.get("recommended"));
            p.optional = Boolean.TRUE.equals(m.get("optional"));
            p.images = strList(m.get("images")); p.files = strList(m.get("files"));
            Object texts = m.get("text");
            if (texts instanceof Map) {
                for (Map.Entry<String, Object> en : ((Map<String, Object>) texts).entrySet()) {
                    Map<String, Object> tm = (Map<String, Object>) en.getValue();
                    Text t = new Text();
                    t.summary = str(tm, "summary"); t.why = str(tm, "why"); t.changes = str(tm, "changes");
                    t.usage = strList(tm.get("usage")); t.requirements = strList(tm.get("requirements"));
                    p.text.put(en.getKey(), t);
                }
            }
            computeFileInfo(p);
            plugins.add(p);
        }
        plugins.sort(Comparator.comparingInt(p -> { int i = categoryOrder.indexOf(p.category); return i < 0 ? Integer.MAX_VALUE : i; }));
    }

    static String str(Map<String, Object> m, String k) { Object o = m.get(k); return o == null ? null : String.valueOf(o); }

    static long lnum(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    static int num(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o instanceof Number ? (int) Math.min(Integer.MAX_VALUE, ((Number) o).longValue()) : 0;
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> strMap(Object o) {
        Map<String, String> r = new HashMap<>();
        if (o instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) if (e.getValue() instanceof String) r.put(e.getKey(), (String) e.getValue());
        return r;
    }

    @SuppressWarnings("unchecked")
    static List<String> strList(Object o) {
        List<String> l = new ArrayList<>();
        if (o instanceof List) for (Object x : (List<Object>) o) l.add(String.valueOf(x));
        return l;
    }

    String categoryName(String id) { return pick(categoryNames.get(id)); }

    void computeFileInfo(Plugin p) {
        if (p.isRules()) {
            p.fileOk = !p.files.isEmpty();
            for (String f : p.files) {
                Path path = root.resolve(f);
                if (!Files.isRegularFile(path)) { p.fileOk = false; continue; }
                try { long s = Files.size(path); p.zipSize += s; p.unpackedSize += s; } catch (IOException ignored) { }
            }
            return;
        }
        Path zip = root.resolve(p.file);
        if (!Files.isRegularFile(zip) && p.parts > 0) {
            // stored as parts: check that every part exists and the sizes add up
            long total = 0;
            p.fileOk = true;
            for (Path part : partPaths(root, p)) {
                if (!Files.isRegularFile(part)) { p.fileOk = false; break; }
                try { total += Files.size(part); } catch (IOException e) { p.fileOk = false; }
            }
            if (p.catalogSize > 0 && total != p.catalogSize) p.fileOk = false;
            p.zipSize = total;
            p.unpackedSize = p.catalogUnpacked;
            p.zipRoot = p.catalogZipRoot;
            return;
        }
        p.fileOk = Files.isRegularFile(zip);
        if (!p.fileOk) return;
        try (ZipFile z = new ZipFile(zip.toFile())) {
            p.zipSize = Files.size(zip);
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                int i = ze.getName().indexOf('/');
                if (i > 0 && p.zipRoot == null) p.zipRoot = ze.getName().substring(0, i);
                if (ze.getSize() > 0) p.unpackedSize += ze.getSize();
            }
        } catch (IOException ex) {
            p.fileOk = false;
        }
    }

    static List<Path> partPaths(Path root, Plugin p) {
        List<Path> l = new ArrayList<>();
        for (int i = 1; i <= p.parts; i++) l.add(root.resolve(p.file + String.format(Locale.ROOT, ".%03d", i)));
        return l;
    }

    /** Returns the plugin zip; if it is stored as parts, joins them into a temporary file and verifies its SHA-256. */
    static Path zipFor(Plugin p, Path root, Consumer<String> log) throws IOException {
        Path zip = root.resolve(p.file);
        if (Files.isRegularFile(zip) || p.parts == 0) return zip;
        Path tmp = Files.createTempFile("plugin-", ".zip");
        java.security.MessageDigest md;
        try { md = java.security.MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
        byte[] buf = new byte[1 << 20];
        try (OutputStream out = Files.newOutputStream(tmp)) {
            for (Path part : partPaths(root, p)) {
                try (InputStream in = Files.newInputStream(part)) {
                    int n;
                    while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); md.update(buf, 0, n); }
                }
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format("%02x", b));
        if (p.sha256 != null && !p.sha256.equalsIgnoreCase(hex.toString())) {
            Files.deleteIfExists(tmp);
            throw new IOException(L("The joined parts are damaged (SHA-256 mismatch): ", "Birleştirilen parçalar bozuk (SHA-256 uyuşmuyor): ") + p.file);
        }
        log.accept("  " + L("joined ", "birleştirildi: ") + p.parts + L(" parts, SHA-256 verified", " parça, SHA-256 doğrulandı"));
        return tmp;
    }

    // ------------------------------------------------------------------ build numbers and IDEA versions

    /** Parses "262.4852.17" or "263.*" into int components; "*" becomes Integer.MAX_VALUE. */
    static int[] parseBuild(String b) {
        if (b == null) return null;
        b = b.trim().replaceAll("^[A-Z]+-", "");
        if (b.isEmpty()) return null;
        String[] parts = b.split("\\.");
        int[] r = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) r[i] = "*".equals(parts[i]) ? Integer.MAX_VALUE : Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return r;
    }

    /** Compares builds component by component; missing components count as 0. */
    static int compareBuild(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    /** 262 -> "2026.2" */
    static String branchToVersion(int branch) { return (2000 + branch / 10) + "." + (branch % 10); }

    /** "261.23567" -> "2026.1 (build 261.23567+)", "242" -> "2024.2" */
    static String sinceText(String since) {
        int[] b = parseBuild(since);
        if (b == null) return "?";
        boolean detail = b.length > 1 && !(b.length == 2 && b[1] == 0);
        return branchToVersion(b[0]) + (detail ? " (build " + since + "+)" : "");
    }

    /** "263.*" -> "2026.3", "271.0" -> "< 2027.1" */
    static String untilText(String until) {
        int[] b = parseBuild(until);
        if (b == null) return null;
        if (b.length > 1 && b[1] == Integer.MAX_VALUE) return branchToVersion(b[0]);
        if (b.length > 1 && b[1] == 0) return "< " + branchToVersion(b[0]);
        return branchToVersion(b[0]) + " (build ≤ " + until + ")";
    }

    static String rangeText(Plugin p) {
        if (p.isRules() || p.sinceBuild == null) return "—";
        String u = untilText(p.untilBuild);
        String s = branchToVersion(parseBuild(p.sinceBuild)[0]);
        if (u == null) return s + "+";
        return s + " – " + u.replace("< ", "<");
    }

    /** Checks a plugin against an IDE build. If only the branch is known (e.g. 262), only branches are compared. */
    static boolean isCompatible(Plugin p, int[] ide) {
        if (ide == null || p.isRules()) return true;
        int[] since = parseBuild(p.sinceBuild), until = parseBuild(p.untilBuild);
        if (ide.length == 1) {
            if (since != null && since[0] > ide[0]) return false;
            if (until != null) {
                if (until[0] < ide[0]) return false;
                if (until.length > 1 && until[1] == 0 && until[0] == ide[0]) return false;
            }
            return true;
        }
        if (since != null && compareBuild(ide, since) < 0) return false;
        return until == null || compareBuild(ide, until) <= 0;
    }

    void readIdeaHome(Path home) {
        Path pi = home.resolve("product-info.json");
        if (!Files.isRegularFile(pi)) pi = home.resolve("Resources/product-info.json"); // macOS
        if (!Files.isRegularFile(pi)) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) new Json(new String(Files.readAllBytes(pi), StandardCharsets.UTF_8)).parse();
            ideaHome = home;
            ideaVersion = str(m, "version");
            ideaBuild = str(m, "buildNumber");
            dataDirName = str(m, "dataDirectoryName");
        } catch (Exception ignored) { }
    }

    static final Pattern DATA_DIR = Pattern.compile("(?:IntelliJIdea|IdeaIC|IntelliJIdeaCE)(\\d{4})\\.(\\d)");

    /** Determines which IDE build the target directory belongs to. */
    int[] ideBuildFor(Path target) {
        int[] full = parseBuild(ideaBuild);
        Integer branch = null;
        for (Path p = target.toAbsolutePath(); p != null; p = p.getParent()) {
            if (p.getFileName() == null) break;
            Matcher m = DATA_DIR.matcher(p.getFileName().toString());
            if (m.matches()) { branch = (Integer.parseInt(m.group(1)) - 2000) * 10 + Integer.parseInt(m.group(2)); break; }
        }
        if (branch == null) return full;
        if (full != null && full[0] == branch) return full;
        return new int[]{branch};
    }

    static boolean isLinux() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"); }
    static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
    static boolean isMac() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"); }

    /** JetBrains per-user data root (without the product folder). */
    static Path jetbrainsDataRoot() {
        String home = System.getProperty("user.home");
        if (isWindows()) {
            String appData = System.getenv("APPDATA");
            return Paths.get(appData != null ? appData : home, "JetBrains");
        }
        if (isMac()) return Paths.get(home, "Library", "Application Support", "JetBrains");
        String xdg = System.getenv("XDG_DATA_HOME");
        return (xdg != null && !xdg.isEmpty() ? Paths.get(xdg) : Paths.get(home, ".local", "share")).resolve("JetBrains");
    }

    static Path pluginDirFor(String productDir) {
        Path p = jetbrainsDataRoot().resolve(productDir);
        // On Windows plugins live in <product>\plugins; on Linux and macOS directly in the product folder.
        return isWindows() ? p.resolve("plugins") : p;
    }

    Path guessTarget() {
        Path custom = pluginsPathFromIdeaProperties();
        if (custom != null) return custom;
        if (dataDirName != null) return pluginDirFor(dataDirName);
        // newest existing IntelliJ IDEA data directory (any version)
        Path base = jetbrainsDataRoot();
        String best = null;
        int bestBranch = 0;
        if (Files.isDirectory(base)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(base)) {
                for (Path p : ds) {
                    Matcher m = DATA_DIR.matcher(p.getFileName().toString());
                    if (m.matches()) {
                        int b = (Integer.parseInt(m.group(1)) - 2000) * 10 + Integer.parseInt(m.group(2));
                        if (b > bestBranch) { bestBranch = b; best = p.getFileName().toString(); }
                    }
                }
            } catch (IOException ignored) { }
        }
        return pluginDirFor(best != null ? best : "IntelliJIdea2026.2");
    }

    Path pluginsPathFromIdeaProperties() {
        List<Path> candidates = new ArrayList<>();
        String env = System.getenv("IDEA_PROPERTIES");
        if (env != null) candidates.add(Paths.get(env));
        if (dataDirName != null) {
            // idea.properties in the user's config directory (Help › Edit Custom Properties)
            if (isLinux()) candidates.add(Paths.get(System.getProperty("user.home"), ".config", "JetBrains", dataDirName, "idea.properties"));
            else candidates.add(jetbrainsDataRoot().resolve(dataDirName).resolve("idea.properties"));
        }
        if (ideaHome != null) candidates.add(ideaHome.resolve("bin").resolve("idea.properties"));
        for (Path p : candidates) {
            if (!Files.isRegularFile(p)) continue;
            try (Reader r = Files.newBufferedReader(p, StandardCharsets.ISO_8859_1)) {
                Properties pr = new Properties();
                pr.load(r);
                String v = pr.getProperty("idea.plugins.path");
                if (v != null && !v.trim().isEmpty()) {
                    v = v.trim().replace("${user.home}", System.getProperty("user.home"));
                    if (!v.contains("${")) return Paths.get(v);
                }
            } catch (IOException ignored) { }
        }
        return null;
    }

    /** Does the catalog "platform" field match this machine? e.g. "linux-x64", "windows-x64". */
    static boolean platformMatches(String platform) {
        if (platform == null || platform.isEmpty()) return true;
        String os = isLinux() ? "linux" : isWindows() ? "windows" : isMac() ? "mac" : "?";
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String a = arch.matches("amd64|x86_64") ? "x64" : arch.matches("aarch64|arm64") ? "arm64" : arch;
        return platform.equalsIgnoreCase(os + "-" + a);
    }

    static boolean isIdeRunning() {
        try {
            return ProcessHandle.allProcesses().anyMatch(ph -> {
                if (ph.pid() == ProcessHandle.current().pid()) return false;
                ProcessHandle.Info info = ph.info();
                Optional<String> cl = info.commandLine();
                if (cl.isPresent() && cl.get().contains("com.intellij.idea.Main")) return true;
                // newer IDEA versions use a native launcher: .../bin/idea on Linux, ...\bin\idea64.exe on Windows
                Optional<String> cmd = info.command();
                if (!cmd.isPresent()) return false;
                Path p = Paths.get(cmd.get());
                Path n = p.getFileName(), parent = p.getParent();
                if (n == null || parent == null || parent.getFileName() == null || !"bin".equals(parent.getFileName().toString())) return false;
                String s = n.toString().toLowerCase(Locale.ROOT);
                return s.equals("idea") || s.equals("idea64.exe") || s.equals("idea.exe");
            });
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ installed plugins

    static final class InstalledPlugin { Path dir; String id, name, version; }

    static List<InstalledPlugin> scanInstalled(Path target) {
        List<InstalledPlugin> result = new ArrayList<>();
        if (!Files.isDirectory(target)) return result;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(target)) {
            for (Path d : ds) {
                Path lib = d.resolve("lib");
                if (!Files.isDirectory(lib)) continue;
                try (DirectoryStream<Path> jars = Files.newDirectoryStream(lib, "*.jar")) {
                    for (Path j : jars) {
                        String xml = readPluginXml(j);
                        if (xml == null) continue;
                        InstalledPlugin ip = new InstalledPlugin();
                        ip.dir = d; ip.id = tag(xml, "id"); ip.name = tag(xml, "name"); ip.version = tag(xml, "version");
                        result.add(ip);
                        break;
                    }
                } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
        return result;
    }

    static String readPluginXml(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("META-INF/plugin.xml");
            if (e == null) return null;
            try (InputStream in = z.getInputStream(e)) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        } catch (IOException e) {
            return null;
        }
    }

    static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + "(?:\\s[^>]*)?>\\s*(.*?)\\s*</" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    static Path siblingDir(Path target, String name) {
        Path parent = target.toAbsolutePath().getParent();
        return (parent != null ? parent : target).resolve(name);
    }

    static Path rulesDir(Path target) { return siblingDir(target, "spotbugs-rules"); }

    void refreshStatus(Path target) {
        ideBuildForTarget = ideBuildFor(target);
        List<InstalledPlugin> installed = scanInstalled(target);
        for (Plugin p : plugins) {
            p.installedDir = null;
            p.installedVersion = null;
            p.compatible = isCompatible(p, ideBuildForTarget);
            if (!p.compatible) p.selected = false;
            if (p.isRules()) {
                Path rd = rulesDir(target);
                boolean all = !p.files.isEmpty();
                for (String f : p.files) all &= Files.isRegularFile(rd.resolve(Paths.get(f).getFileName()));
                if (all) p.installedDir = rd;
                continue;
            }
            for (InstalledPlugin ip : installed) {
                boolean match = (p.pluginId != null && (p.pluginId.equals(ip.id) || (ip.id == null && p.pluginId.equals(ip.name))))
                        || (p.zipRoot != null && ip.dir.getFileName().toString().equals(p.zipRoot));
                if (match) { p.installedDir = ip.dir; p.installedVersion = ip.version; break; }
            }
        }
    }

    /** "IntelliJ IDEA 2021.3+ (complete set: 2026.2+)" based on the plugins available on this platform. */
    String compatibilitySummary() {
        int[] lowest = null, fullSet = null;
        for (Plugin p : plugins) {
            if (p.isRules() || !platformMatches(p.platform)) continue;
            int[] s = parseBuild(p.sinceBuild);
            if (s == null) continue;
            if (lowest == null || compareBuild(s, lowest) < 0) lowest = s;
            if (!p.optional && (fullSet == null || compareBuild(s, fullSet) > 0)) fullSet = s;
        }
        if (lowest == null || fullSet == null) return "";
        return "IntelliJ IDEA " + branchToVersion(lowest[0]) + "+ " + L("(complete set: ", "(tüm paket: ") + branchToVersion(fullSet[0]) + "+)";
    }

    // ------------------------------------------------------------------ UI

    void buildUi(Path target) {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) { UIManager.setLookAndFeel(info.getClassName()); break; }
            }
        } catch (Exception ignored) { }
        createWindow(target, null);
    }

    void createWindow(Path target, String selectedId) {
        Rectangle bounds = null;
        if (frame != null) { bounds = frame.getBounds(); frame.dispose(); }
        frame = new JFrame(pick(title) + " · " + compatibilitySummary());
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // --- top: target directory + language
        JPanel top = new JPanel(new GridBagLayout());
        top.setBorder(new EmptyBorder(8, 10, 4, 10));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 2, 2, 2);
        g.gridx = 0; g.gridy = 0; g.anchor = GridBagConstraints.WEST;
        top.add(new JLabel(L("Plugin directory:", "Eklenti dizini:")), g);
        targetField = new JTextField(target.toString());
        targetField.setToolTipText(L("IntelliJ IDEA plugin directory the plugins are installed into", "Eklentilerin kurulacağı IntelliJ IDEA eklenti dizini"));
        g.gridx = 1; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
        top.add(targetField, g);
        JButton browse = new JButton(L("Browse…", "Gözat…"));
        browse.addActionListener(a -> chooseDirectory());
        g.gridx = 2; g.weightx = 0; g.fill = GridBagConstraints.NONE;
        top.add(browse, g);
        JButton refresh = new JButton(L("Refresh", "Yenile"));
        refresh.setToolTipText(L("Scan installed plugins again", "Kurulu eklentileri yeniden tara"));
        refresh.addActionListener(a -> refresh());
        g.gridx = 3;
        top.add(refresh, g);
        JComboBox<String> language = new JComboBox<>(new String[]{"English", "Türkçe"});
        language.setSelectedIndex("tr".equals(lang) ? 1 : 0);
        language.setToolTipText("Language / Dil");
        language.addActionListener(a -> switchLanguage(language.getSelectedIndex() == 1 ? "tr" : "en"));
        g.gridx = 4;
        top.add(language, g);
        infoLabel = new JLabel(" ");
        g.gridx = 0; g.gridy = 1; g.gridwidth = 5; g.fill = GridBagConstraints.HORIZONTAL;
        top.add(infoLabel, g);
        targetField.addActionListener(a -> refresh());

        JLabel header = new JLabel("<html><span style='font-size:14pt'><b>" + esc(pick(title)) + "</b></span>&nbsp;&nbsp;<font color='#555555'>"
                + esc(L("For ", "Uyumlu: ")) + esc(compatibilitySummary()) + "</font></html>");
        header.setBorder(new EmptyBorder(8, 12, 0, 12));
        JPanel north = new JPanel(new BorderLayout());
        north.add(header, BorderLayout.NORTH);
        north.add(top, BorderLayout.CENTER);

        // --- left: list
        tableModel = new PluginTableModel();
        table = new JTable(tableModel);
        table.setRowHeight(24);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sorter = new TableRowSorter<>(tableModel);
        sorter.setSortable(0, false);
        table.setRowSorter(sorter);
        int[] widths = {30, 185, 125, 115, 80, 85};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getColumnModel().getColumn(0).setMaxWidth(36);
        table.setDefaultRenderer(Object.class, new StatusRenderer());
        table.getSelectionModel().addListSelectionListener(ev -> {
            if (ev.getValueIsAdjusting()) return;
            int r = table.getSelectedRow();
            if (r >= 0) showDetails(visiblePlugins().get(table.convertRowIndexToModel(r)));
        });

        searchField = new JTextField();
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        showOptional = new JCheckBox(L("Show optional plugins that need internet", "İnternet isteyen opsiyonel eklentileri göster"), optionalVisible);
        showOptional.addActionListener(a -> {
            optionalVisible = showOptional.isSelected();
            if (!optionalVisible) for (Plugin p : plugins) if (p.optional) p.selected = false;
            tableModel.fireTableDataChanged();
            updateSummary();
        });

        JPanel searchPanel = new JPanel(new BorderLayout(6, 0));
        searchPanel.add(new JLabel(L("Search:", "Ara:")), BorderLayout.WEST);
        searchPanel.add(searchField, BorderLayout.CENTER);
        JPanel leftTop = new JPanel(new BorderLayout(0, 4));
        leftTop.add(searchPanel, BorderLayout.NORTH);
        leftTop.add(showOptional, BorderLayout.SOUTH);

        JPanel selectButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton recommended = new JButton(L("Select recommended", "Önerilenleri seç"));
        recommended.addActionListener(a -> select(p -> p.recommended));
        JButton all = new JButton(L("Select all", "Tümünü seç"));
        all.addActionListener(a -> select(p -> true));
        JButton notInstalled = new JButton(L("Not installed", "Kurulu olmayanlar"));
        notInstalled.setToolTipText(L("Select plugins that are not installed yet or have an update", "Henüz kurulu olmayan veya güncellenecek olanları seç"));
        notInstalled.addActionListener(a -> select(p -> p.status() == Status.NEW || p.status() == Status.UPDATE));
        JButton clear = new JButton(L("Clear", "Temizle"));
        clear.addActionListener(a -> { for (Plugin p : plugins) p.selected = false; tableModel.fireTableDataChanged(); updateSummary(); });
        selectButtons.add(recommended); selectButtons.add(all); selectButtons.add(notInstalled); selectButtons.add(clear);

        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setBorder(new EmptyBorder(4, 10, 4, 4));
        left.add(leftTop, BorderLayout.NORTH);
        left.add(new JScrollPane(table), BorderLayout.CENTER);
        left.add(selectButtons, BorderLayout.SOUTH);

        // --- right: details + screenshot
        details = new JEditorPane();
        details.setContentType("text/html");
        details.setEditable(false);
        details.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        JScrollPane detailsScroll = new JScrollPane(details);
        detailsScroll.setBorder(BorderFactory.createTitledBorder(L("What does it do? What changes after installing?", "Ne işe yarar? Kurunca ne değişir?")));

        imagePanel = new ImagePanel();
        imagePanel.setToolTipText(L("Click to view full size", "Tam boyutta görmek için tıklayın"));
        imagePanel.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { enlargeImage(); }
        });
        prevImage = new JButton("◀");
        nextImage = new JButton("▶");
        enlargeButton = new JButton(L("Full size", "Tam boyut"));
        imageCounter = new JLabel(" ", SwingConstants.CENTER);
        prevImage.addActionListener(a -> changeImage(-1));
        nextImage.addActionListener(a -> changeImage(1));
        enlargeButton.addActionListener(a -> enlargeImage());
        JPanel imageNav = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 2));
        imageNav.add(prevImage); imageNav.add(imageCounter); imageNav.add(nextImage); imageNav.add(enlargeButton);
        JPanel imageBox = new JPanel(new BorderLayout());
        imageBox.setBorder(BorderFactory.createTitledBorder(L("Screenshot (preview before installing)", "Ekran görüntüsü (kurulumdan önce önizleme)")));
        imageBox.add(imagePanel, BorderLayout.CENTER);
        imageBox.add(imageNav, BorderLayout.SOUTH);

        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, detailsScroll, imageBox);
        right.setResizeWeight(0.45);
        right.setBorder(new EmptyBorder(4, 4, 4, 10));

        JSplitPane center = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        center.setResizeWeight(0.48);

        // --- bottom
        summaryLabel = new JLabel(" ");
        installButton = new JButton(L("Preview changes and install…", "Değişiklikleri önizle ve kur…"));
        installButton.setFont(installButton.getFont().deriveFont(Font.BOLD));
        installButton.addActionListener(a -> previewAndInstall());
        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setBorder(new EmptyBorder(6, 10, 10, 10));
        bottom.add(summaryLabel, BorderLayout.CENTER);
        bottom.add(installButton, BorderLayout.EAST);

        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(north, BorderLayout.NORTH);
        frame.getContentPane().add(center, BorderLayout.CENTER);
        frame.getContentPane().add(bottom, BorderLayout.SOUTH);

        if (bounds != null) frame.setBounds(bounds);
        else {
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            frame.setSize(Math.min(1340, screen.width - 40), Math.min(860, screen.height - 60));
            frame.setLocationRelativeTo(null);
        }

        refresh();
        applyFilter();
        int row = 0;
        List<Plugin> vis = visiblePlugins();
        for (int i = 0; i < vis.size(); i++) if (vis.get(i).id.equals(selectedId)) row = table.convertRowIndexToView(i);
        if (table.getRowCount() > 0) table.setRowSelectionInterval(row, row);
        if (snapshotMode) {
            frame.addNotify(); // displayable but never shown on screen
            frame.validate();
            center.setDividerLocation(0.48);
            frame.validate();
        } else {
            frame.setVisible(true);
            SwingUtilities.invokeLater(() -> center.setDividerLocation(0.48));
        }
    }

    void switchLanguage(String newLang) {
        if (newLang.equals(lang)) return;
        lang = newLang;
        Preferences p = prefs();
        if (p != null) try { p.put("lang", lang); p.flush(); } catch (Exception ignored) { }
        String keep = shown != null ? shown.id : null;
        Path target = target();
        SwingUtilities.invokeLater(() -> createWindow(target, keep));
    }

    interface Filter { boolean test(Plugin p); }

    void select(Filter f) {
        for (Plugin p : visiblePlugins()) p.selected = p.selectable() && f.test(p);
        tableModel.fireTableDataChanged();
        updateSummary();
    }

    Path target() { return Paths.get(targetField.getText().trim()); }

    void chooseDirectory() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle(L("Choose the IntelliJ IDEA plugin directory", "IntelliJ IDEA eklenti dizinini seçin"));
        Path t = target();
        fc.setCurrentDirectory((Files.isDirectory(t) ? t : jetbrainsDataRoot()).toFile());
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            targetField.setText(fc.getSelectedFile().getAbsolutePath());
            refresh();
        }
    }

    void refresh() {
        Path t = target();
        refreshStatus(t);
        StringBuilder sb = new StringBuilder("<html>");
        if (ideaVersion != null) sb.append("IntelliJ IDEA ").append(esc(ideaVersion)).append(" (build ").append(esc(ideaBuild)).append(") · ");
        if (ideBuildForTarget != null) {
            sb.append(L("Target belongs to IDEA ", "Hedef dizin: IDEA ")).append(branchToVersion(ideBuildForTarget[0])).append(" · ");
        } else {
            sb.append("<font color='#b9770e'>").append(L("IDEA version of the target is unknown; compatibility is not checked", "Hedefin IDEA sürümü bilinmiyor; uyumluluk denetlenmiyor")).append("</font> · ");
        }
        sb.append(Files.isDirectory(t) ? L("Directory exists", "Dizin mevcut") : "<b>" + L("Directory does not exist yet; it will be created", "Dizin henüz yok, kurulumda oluşturulacak") + "</b>");
        int installed = 0, incompatible = 0;
        for (Plugin p : platformPlugins()) {
            if (!p.isRules() && p.installedDir != null) installed++;
            if (!p.compatible) incompatible++;
        }
        sb.append(" · ").append(L("Installed from this list: ", "Bu listeden kurulu: ")).append(installed);
        if (incompatible > 0) sb.append(" · <font color='#c0392b'>").append(incompatible).append(L(" incompatible with this IDEA version", " eklenti bu IDEA sürümüyle uyumsuz")).append("</font>");
        if (isIdeRunning()) sb.append(" · <font color='#c0392b'><b>").append(L("IntelliJ IDEA is running. Close it before installing.", "IntelliJ IDEA şu an çalışıyor. Kurmadan önce kapatın.")).append("</b></font>");
        sb.append("</html>");
        infoLabel.setText(sb.toString());
        tableModel.fireTableDataChanged();
        updateSummary();
        if (shown != null) showDetails(shown);
    }

    /** Plugins listed in the table (respecting the "optional" checkbox and the platform). */
    List<Plugin> visiblePlugins() {
        List<Plugin> l = new ArrayList<>();
        for (Plugin p : plugins) if ((optionalVisible || !p.optional) && platformMatches(p.platform)) l.add(p);
        return l;
    }

    /** All plugins for this platform, including hidden optional ones. */
    List<Plugin> platformPlugins() {
        List<Plugin> l = new ArrayList<>();
        for (Plugin p : plugins) if (platformMatches(p.platform)) l.add(p);
        return l;
    }

    void applyFilter() {
        String q = searchField.getText().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) { sorter.setRowFilter(null); return; }
        sorter.setRowFilter(new RowFilter<PluginTableModel, Integer>() {
            @Override public boolean include(Entry<? extends PluginTableModel, ? extends Integer> en) {
                Plugin p = visiblePlugins().get(en.getIdentifier());
                Text t = p.text();
                String all = (p.name() + " " + categoryName(p.category) + " " + (t == null ? "" : t.summary + " " + t.why + " " + t.changes)).toLowerCase(Locale.ROOT);
                return all.contains(q);
            }
        });
    }

    void updateSummary() {
        int count = 0;
        long size = 0;
        for (Plugin p : visiblePlugins()) if (p.selected) { count++; size += p.unpackedSize; }
        summaryLabel.setText(count == 0
                ? L("Tick the plugins to install on the left. Click a row to see details.", "Kurulacak eklentileri soldaki kutucuklardan seçin. Ayrıntıları görmek için satıra tıklayın.")
                : L(count + " selected · about " + sizeText(size) + " will be written to disk", count + " öğe seçili · diske yazılacak yaklaşık " + sizeText(size)));
        installButton.setEnabled(count > 0);
    }

    static String sizeText(long b) {
        if (b >= 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f GB", b / 1024.0 / 1024 / 1024);
        if (b >= 1024L * 1024) return String.format(Locale.ROOT, "%.1f MB", b / 1024.0 / 1024);
        return String.format(Locale.ROOT, "%d KB", Math.max(1, b / 1024));
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    void showDetails(Plugin p) {
        shown = p;
        Text t = p.text();
        StringBuilder h = new StringBuilder("<html><body style='font-family:sans-serif; font-size:11pt; margin:6px'>");
        h.append("<div style='font-size:16pt'><b>").append(esc(p.name())).append("</b> <font color='#777777'>").append(esc(p.version)).append("</font></div><p>");
        h.append(badge(categoryName(p.category), "#5b6b7f"));
        String oc = "full".equals(p.offline) ? "#2e8b57" : "partial".equals(p.offline) ? "#d68910" : "#c0392b";
        String ot = "full".equals(p.offline) ? L("Works offline", "İnternetsiz çalışır") : "partial".equals(p.offline) ? L("Works offline with conditions", "İnternetsiz koşullu çalışır") : L("Needs internet", "İnternet ister");
        h.append(" ").append(badge(ot, oc));
        if (p.recommended) h.append(" ").append(badge(L("Recommended", "Önerilen"), "#2c6fbb"));
        Status s = p.status();
        h.append(" ").append(badge(statusText(s), String.format("#%06x", statusColor(s).getRGB() & 0xffffff)));
        h.append("</p>");

        if (!p.isRules() && p.sinceBuild != null) {
            h.append("<p><b>").append(L("Compatible IntelliJ IDEA versions", "Uyumlu IntelliJ IDEA sürümleri")).append("</b><br>");
            h.append(esc(sinceText(p.sinceBuild))).append(L(" or newer", " ve üzeri"));
            String u = untilText(p.untilBuild);
            if (u != null) h.append(L(", up to ", ", en fazla ")).append(esc(u));
            if (!p.compatible && ideBuildForTarget != null) {
                h.append("<br><font color='#c0392b'><b>").append(L("Not compatible with the target IDEA ", "Hedefteki IDEA ")).append(branchToVersion(ideBuildForTarget[0]))
                        .append(L(". It cannot be selected.", " ile uyumsuz, seçilemez.")).append("</b></font>");
            }
            h.append("</p>");
        }
        if (t != null) {
            section(h, L("What does it do?", "Ne işe yarar?"), t.summary);
            section(h, L("Why use it?", "Niçin kullanılır?"), t.why);
            section(h, L("What changes in the IDE after installing?", "Kurunca IDE'de ne değişir?"), t.changes);
            if (!t.usage.isEmpty()) {
                h.append("<p><b>").append(L("How to use", "Nasıl kullanılır?")).append("</b></p><ul>");
                for (String k : t.usage) h.append("<li>").append(esc(k)).append("</li>");
                h.append("</ul>");
            }
            if (!t.requirements.isEmpty()) {
                h.append("<p><b><font color='#c0392b'>").append(L("Requirements and warnings", "Gereksinimler ve uyarılar")).append("</font></b></p><ul>");
                for (String k : t.requirements) h.append("<li><font color='#a93226'>").append(esc(k)).append("</font></li>");
                h.append("</ul>");
            }
        }
        h.append("<p><b>").append(L("Installation details", "Kurulum ayrıntısı")).append("</b></p><ul>");
        String pathColor = "<font color='#1f4e79'>";
        if (p.isRules()) {
            for (String f : p.files) h.append("<li>").append(esc(Paths.get(f).getFileName().toString())).append("</li>");
            h.append("<li>").append(L("Copied to: ", "Kopyalanacağı yer: ")).append(pathColor).append(esc(rulesDir(target()).toString())).append("</font></li>");
        } else {
            h.append("<li>").append(L("File: ", "Dosya: ")).append(pathColor).append(esc(p.file)).append("</font>")
                    .append(p.parts > 0 ? L(" (stored as " + p.parts + " parts, joined automatically)", " (" + p.parts + " parça halinde, otomatik birleştirilir)") : "")
                    .append(" (").append(sizeText(p.zipSize))
                    .append(L(", unpacked ", ", açılınca ")).append(sizeText(p.unpackedSize)).append(")</li>");
            h.append("<li>").append(L("Installed into: ", "Kurulacağı klasör: ")).append(pathColor).append(esc(target().resolve(p.zipRoot == null ? "?" : p.zipRoot).toString())).append("</font></li>");
            if (p.pluginId != null) h.append("<li>").append(L("Plugin ID: ", "Eklenti kimliği: ")).append(pathColor).append(esc(p.pluginId)).append("</font></li>");
            if (p.installedDir != null) h.append("<li>").append(L("Currently installed: ", "Şu an kurulu: ")).append("<b>").append(esc(p.installedVersion)).append("</b> → ").append(pathColor).append(esc(p.installedDir.toString())).append("</font></li>");
        }
        if (!p.fileOk) h.append("<li><font color='#c0392b'><b>").append(L("Package file not found or damaged. This item cannot be installed.", "Paket dosyası bulunamadı veya bozuk. Bu öğe kurulamaz.")).append("</b></font></li>");
        h.append("</ul></body></html>");
        details.setText(h.toString());
        details.setCaretPosition(0);
        imageIndex = 0;
        showImage();
    }

    static String badge(String text, String color) {
        return "<font color='" + color + "'>■</font> <font color='" + color + "'><b>" + esc(text) + "</b></font>&nbsp;&nbsp;";
    }

    static void section(StringBuilder h, String title, String text) {
        if (text == null || text.isEmpty()) return;
        h.append("<p><b>").append(esc(title)).append("</b><br>").append(esc(text)).append("</p>");
    }

    void changeImage(int delta) {
        if (shown == null || shown.images.isEmpty()) return;
        int n = shown.images.size();
        imageIndex = (imageIndex + delta + n) % n;
        showImage();
    }

    void showImage() {
        Plugin p = shown;
        int n = p == null ? 0 : p.images.size();
        prevImage.setEnabled(n > 1);
        nextImage.setEnabled(n > 1);
        enlargeButton.setEnabled(n > 0);
        if (n == 0) {
            imagePanel.set(null, p != null && p.isRules()
                    ? L("Rule packs have no user interface of their own. Results appear in the SpotBugs tool window.", "Kural paketlerinin görsel arayüzü yok. Sonuçlar SpotBugs araç penceresinde görünür.")
                    : L("No screenshot is available for this plugin.", "Bu eklenti için ekran görüntüsü yok."));
            imageCounter.setText(" ");
            return;
        }
        Path path = root.resolve(p.images.get(imageIndex));
        if (!Files.isRegularFile(path)) imagePanel.set(null, L("Image not found: ", "Görsel bulunamadı: ") + path.getFileName());
        else imagePanel.set(Toolkit.getDefaultToolkit().createImage(path.toString()), null); // also animates GIFs
        imageCounter.setText((imageIndex + 1) + " / " + n + (path.getFileName().toString().contains("illustration") ? L("  (illustration)", "  (temsili görsel)") : ""));
    }

    void enlargeImage() {
        if (shown == null || shown.images.isEmpty()) return;
        Path path = root.resolve(shown.images.get(imageIndex));
        if (!Files.isRegularFile(path)) return;
        JDialog d = new JDialog(frame, shown.name() + " - " + path.getFileName(), false);
        JLabel l = new JLabel(new ImageIcon(Toolkit.getDefaultToolkit().createImage(path.toString())));
        JScrollPane sp = new JScrollPane(l);
        sp.getVerticalScrollBar().setUnitIncrement(24);
        sp.getHorizontalScrollBar().setUnitIncrement(24);
        d.getContentPane().add(sp);
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        Dimension pref = l.getPreferredSize();
        d.setSize(Math.min(pref.width + 40, screen.width - 80), Math.min(pref.height + 60, screen.height - 100));
        d.setLocationRelativeTo(frame);
        d.setVisible(true);
    }

    // ------------------------------------------------------------------ preview and install

    void previewAndInstall() {
        Path t = target();
        refreshStatus(t);
        List<Plugin> chosen = new ArrayList<>();
        for (Plugin p : visiblePlugins()) if (p.selected && p.selectable()) chosen.add(p);
        if (chosen.isEmpty()) return;

        Plugin spotbugs = byId("spotbugs");
        List<String> warnings = new ArrayList<>();
        for (Plugin p : chosen) {
            if (p.isRules() && spotbugs != null && !spotbugs.selected && spotbugs.installedDir == null)
                warnings.add(L("The SpotBugs rule jars are selected but SpotBugs itself is neither installed nor selected. The jars are copied but cannot be used without SpotBugs.",
                        "SpotBugs kural jar'ları seçildi ama SpotBugs eklentisi ne kurulu ne de seçili. Jar'lar kopyalanır ama SpotBugs kurulmadan kullanılamaz."));
            if ("none".equals(p.offline)) warnings.add(p.name() + L(": does not work without internet access.", ": internet erişimi olmadan çalışmaz."));
        }
        if (ideBuildForTarget == null)
            warnings.add(L("The IntelliJ IDEA version of the target directory is unknown, so compatibility was not checked.", "Hedef dizinin IntelliJ IDEA sürümü bilinmediği için uyumluluk denetlenmedi."));
        boolean running = isIdeRunning();
        if (running) warnings.add(0, L("INTELLIJ IDEA IS RUNNING. Changing plugin files while it runs can break the IDE. Close the IDE first.",
                "IntelliJ IDEA ŞU AN ÇALIŞIYOR. Çalışırken eklenti dosyalarını değiştirmek IDE'yi bozabilir. Önce IDE'yi kapatmanız önerilir."));
        long total = 0;
        for (Plugin p : chosen) total += p.unpackedSize;
        try {
            Path existing = t;
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            if (existing != null) {
                long free = Files.getFileStore(existing).getUsableSpace();
                if (free < total + 50L * 1024 * 1024)
                    warnings.add(0, L("Not enough disk space: about " + sizeText(total) + " needed, " + sizeText(free) + " free.",
                            "Diskte yeterli yer yok: gerekli ~" + sizeText(total) + ", boş " + sizeText(free) + "."));
            }
        } catch (IOException ignored) { }

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        Path backupRoot = siblingDir(t, "plugin-backups").resolve(stamp);

        StringBuilder s = new StringBuilder("<html><body style='font-family:sans-serif; font-size:10pt; margin:6px'>");
        s.append("<div style='font-size:13pt'><b>").append(L("Changes to be made", "Yapılacak değişiklikler")).append("</b></div>");
        s.append("<p>").append(L("Target plugin directory: ", "Hedef eklenti dizini: ")).append("<font color='#1f4e79'>").append(esc(t.toString())).append("</font>")
                .append(Files.isDirectory(t) ? "" : " <b>" + L("(will be created)", "(oluşturulacak)") + "</b>")
                .append("<br>").append(L("Written to disk: ~", "Diske yazılacak: ~")).append(sizeText(total)).append("</p>");
        if (!warnings.isEmpty()) {
            s.append("<p><b><font color='#c0392b'>").append(L("Warnings", "Uyarılar")).append("</font></b></p><ul>");
            for (String w : new LinkedHashSet<>(warnings)) s.append("<li><font color='#a93226'>").append(esc(w)).append("</font></li>");
            s.append("</ul>");
        }
        s.append("<table border='1' cellspacing='0' cellpadding='4' width='100%'><tr bgcolor='#e8ecf1'><th align='left'>").append(L("Item", "Öğe"))
                .append("</th><th align='left'>").append(L("Action", "İşlem")).append("</th><th align='left'>").append(L("Folder (in target)", "Klasör (hedef dizinde)"))
                .append("</th><th align='left'>").append(L("What you will see in the IDE", "IDE'de görülecek değişiklik")).append("</th></tr>");
        boolean backup = false;
        String grey = "<br><font color='#777777'>";
        for (Plugin p : chosen) {
            String action, folder;
            if (p.isRules()) {
                action = p.installedDir != null ? L("Copy (overwrite)", "Üzerine kopyala") : L("Copy", "Kopyala");
                folder = "../spotbugs-rules/";
            } else if (p.installedDir == null) {
                action = "<font color='#2e8b57'><b>" + L("New install", "Yeni kurulum") + "</b></font> " + esc(p.pluginVersion);
                folder = esc(p.zipRoot) + "/";
            } else if (Objects.equals(p.installedVersion, p.pluginVersion)) {
                action = L("Reinstall (same version ", "Yeniden kurulum (aynı sürüm ") + esc(p.pluginVersion) + ")";
                folder = esc(p.zipRoot) + "/" + grey + L("old folder is backed up", "eski klasör yedeklenir") + "</font>";
                backup = true;
            } else {
                action = "<font color='#d68910'><b>" + L("Update", "Güncelleme") + "</b></font> " + esc(p.installedVersion) + " → " + esc(p.pluginVersion);
                folder = esc(p.zipRoot) + "/" + grey + L("old version is backed up", "eski sürüm yedeklenir") + "</font>";
                backup = true;
            }
            Text tx = p.text();
            s.append("<tr><td><b>").append(esc(p.name())).append("</b></td><td>").append(action).append("</td><td><font color='#1f4e79'>").append(folder)
                    .append("</font></td><td>").append(esc(tx == null ? "" : tx.changes)).append("</td></tr>");
        }
        s.append("</table>");
        if (backup) s.append("<p>").append(L("Plugin folders that are replaced are not deleted; they are moved to:", "Değiştirilecek eski eklenti klasörleri silinmez, şuraya taşınır:"))
                .append("<br><font color='#1f4e79'>").append(esc(backupRoot.toString())).append("</font><br>")
                .append(L("To roll back, move the folder back into the plugin directory.", "Geri almak için klasörü eklenti dizinine geri taşımanız yeterli.")).append("</p>");
        s.append("<p>").append(L("This does not connect to the internet. Start (or restart) IntelliJ IDEA afterwards.", "Bu işlem internete bağlanmaz. Kurulumdan sonra IntelliJ IDEA'yı (yeniden) başlatın.")).append("</p></body></html>");

        if (snapshotMode) { lastPreviewHtml = s.toString(); return; }
        JEditorPane ep = new JEditorPane("text/html", s.toString());
        ep.setEditable(false);
        ep.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(ep);
        sp.setPreferredSize(new Dimension(Math.min(980, frame.getWidth() - 60), Math.min(620, frame.getHeight() - 80)));
        String[] options = {running ? L("Install anyway", "Yine de kur") : L("Install", "Kur"), L("Back", "Geri dön")};
        int c = JOptionPane.showOptionDialog(frame, sp, L("Installation preview: " + chosen.size() + " item(s)", "Kurulum önizlemesi: " + chosen.size() + " öğe"),
                JOptionPane.DEFAULT_OPTION, running ? JOptionPane.WARNING_MESSAGE : JOptionPane.PLAIN_MESSAGE, null, options, options[1]);
        if (c != 0) return;
        install(t, chosen, backupRoot);
    }

    Plugin byId(String id) {
        for (Plugin p : plugins) if (id.equals(p.id)) return p;
        return null;
    }

    void install(Path t, List<Plugin> chosen, Path backupRoot) {
        JDialog d = new JDialog(frame, L("Installing…", "Kuruluyor…"), true);
        JProgressBar bar = new JProgressBar(0, chosen.size());
        bar.setStringPainted(true);
        JTextArea log = new JTextArea(16, 80);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JButton close = new JButton(L("Close", "Kapat"));
        close.setEnabled(false);
        close.addActionListener(a -> d.dispose());
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        panel.add(bar, BorderLayout.NORTH);
        panel.add(new JScrollPane(log), BorderLayout.CENTER);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(close);
        panel.add(south, BorderLayout.SOUTH);
        d.setContentPane(panel);
        d.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        d.pack();
        d.setLocationRelativeTo(frame);

        SwingWorker<int[], String> worker = new SwingWorker<int[], String>() {
            @Override protected int[] doInBackground() {
                Installer inst = new Installer(t, backupRoot, this::publish);
                int ok = 0, failed = 0, i = 0;
                for (Plugin p : chosen) {
                    publish("▶ " + p.name());
                    try {
                        if (p.isRules()) inst.copyRules(p, root, rulesDir(t));
                        else inst.installPlugin(p, root);
                        ok++;
                    } catch (Exception ex) {
                        failed++;
                        publish("  " + L("ERROR: ", "HATA: ") + ex.getMessage());
                    }
                    final int progress = ++i;
                    SwingUtilities.invokeLater(() -> bar.setValue(progress));
                }
                return new int[]{ok, failed};
            }

            @Override protected void process(List<String> lines) {
                for (String line : lines) log.append(line + "\n");
                log.setCaretPosition(log.getDocument().getLength());
            }

            @Override protected void done() {
                int[] r;
                try { r = get(); } catch (Exception ex) { r = new int[]{0, chosen.size()}; log.append("ERROR: " + ex + "\n"); }
                log.append("\n" + L(r[0] + " succeeded, " + r[1] + " failed.", r[0] + " başarılı, " + r[1] + " hatalı.") + "\n");
                d.setTitle(r[1] == 0 ? L("Installation finished", "Kurulum tamamlandı") : L("Installation finished with errors", "Kurulum hatalarla bitti"));
                close.setEnabled(true);
                d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                for (Plugin p : plugins) p.selected = false;
                refresh();
                StringBuilder next = new StringBuilder("<html><b>")
                        .append(L(r[0] + " item(s) installed", r[0] + " öğe kuruldu"))
                        .append(r[1] > 0 ? ", " + L(r[1] + " failed", r[1] + " öğe hatalı") : "").append(".</b><br><br>");
                next.append(L("Next step: start IntelliJ IDEA (restart it if it is open).<br>Installed plugins are listed under <i>Settings › Plugins › Installed</i>.",
                        "Sonraki adım: IntelliJ IDEA'yı başlatın (açıksa yeniden başlatın).<br>Kurulanları <i>Settings › Plugins › Installed</i> altında görebilirsiniz."));
                for (Plugin p : chosen) if (p.isRules()) {
                    next.append("<br><br><b>").append(L("SpotBugs rule jars:", "SpotBugs kural jar'ları:")).append("</b> <i>Settings › Tools › SpotBugs › Plugins</i> → <b>+</b> → ")
                            .append(L("select the jars in this folder:", "şu klasördeki jar'ları seçin:")).append("<br><font color='#1f4e79'>").append(esc(rulesDir(t).toString())).append("</font><br>")
                            .append(L("Disable the older findsecbugs and fb-contrib versions bundled with the plugin.", "Eklentinin içindeki eski findsecbugs ve fb-contrib sürümlerini devre dışı bırakın."));
                }
                next.append("</html>");
                JOptionPane.showMessageDialog(d, next.toString(), L("Done", "Bitti"), r[1] == 0 ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
            }
        };
        worker.execute();
        d.setVisible(true);
    }

    /** File operations; independent of the UI (also used by --test). */
    static final class Installer {
        final Path target, backupRoot;
        final Consumer<String> log;

        Installer(Path target, Path backupRoot, Consumer<String> log) {
            this.target = target.toAbsolutePath().normalize();
            this.backupRoot = backupRoot.toAbsolutePath().normalize();
            this.log = log;
        }

        void installPlugin(Plugin p, Path root) throws IOException {
            Path zip = zipFor(p, root, log);
            try {
                extract(p, zip);
            } finally {
                if (!zip.startsWith(root)) Files.deleteIfExists(zip); // temporary joined file
            }
        }

        void extract(Plugin p, Path zip) throws IOException {
            if (p.zipRoot == null || p.zipRoot.contains("..") || p.zipRoot.contains("\\")) throw new IOException("Unexpected zip layout: " + zip.getFileName());
            Files.createDirectories(target);
            // move existing folders (including one installed under a different name) to the backup
            Set<Path> old = new LinkedHashSet<>();
            if (p.installedDir != null) old.add(p.installedDir.toAbsolutePath().normalize());
            Path dest = target.resolve(p.zipRoot).normalize();
            if (Files.exists(dest)) old.add(dest);
            for (Path o : old) {
                if (!o.startsWith(target) || o.equals(target)) throw new IOException("Unsafe path: " + o);
                if (!Files.exists(o)) continue;
                Path b = backupRoot.resolve(o.getFileName().toString());
                Files.createDirectories(backupRoot);
                try {
                    Files.move(o, b);
                } catch (IOException ex) { // e.g. different file system: copy + delete
                    copyTree(o, b);
                    deleteTree(o);
                }
                log.accept("  " + L("old folder backed up → ", "eski klasör yedeklendi → ") + b);
            }
            long count = 0;
            try (ZipFile z = new ZipFile(zip.toFile())) {
                Enumeration<? extends ZipEntry> en = z.entries();
                byte[] buf = new byte[64 * 1024];
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    Path out = target.resolve(ze.getName()).normalize();
                    if (!out.startsWith(dest)) throw new IOException("Rejected entry outside the plugin folder: " + ze.getName());
                    if (ze.isDirectory()) { Files.createDirectories(out); continue; }
                    Files.createDirectories(out.getParent());
                    try (InputStream in = z.getInputStream(ze); OutputStream os = Files.newOutputStream(out)) {
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    count++;
                }
            }
            log.accept("  " + L("installed → ", "kuruldu → ") + dest + " (" + count + L(" files)", " dosya)"));
        }

        void copyRules(Plugin p, Path root, Path dir) throws IOException {
            Files.createDirectories(dir);
            for (String f : p.files) {
                Path src = root.resolve(f);
                Path dst = dir.resolve(src.getFileName());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                log.accept("  " + L("copied → ", "kopyalandı → ") + dst);
            }
        }

        static void copyTree(Path src, Path dst) throws IOException {
            Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                    Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    Files.copy(f, dst.resolve(src.relativize(f).toString()), StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        static void deleteTree(Path p) throws IOException {
            Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException { Files.delete(f); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path d, IOException ex) throws IOException { Files.delete(d); return FileVisitResult.CONTINUE; }
            });
        }
    }

    // ------------------------------------------------------------------ snapshot (--snapshot, for README images)

    void snapshot(Path target, String prefix, List<String> select, String show) {
        snapshotMode = true;
        buildUi(target);
        for (Plugin p : plugins) p.selected = select.contains(p.id) && p.selectable();
        tableModel.fireTableDataChanged();
        updateSummary();
        List<Plugin> vis = visiblePlugins();
        for (int i = 0; i < vis.size(); i++) {
            if (vis.get(i).id.equals(show)) { int row = table.convertRowIndexToView(i); table.setRowSelectionInterval(row, row); }
        }
        if (imagePanel.image != null) {
            MediaTracker mt = new MediaTracker(imagePanel);
            mt.addImage(imagePanel.image, 0);
            try { mt.waitForAll(10000); } catch (InterruptedException ignored) { }
        }
        frame.validate();
        writePng(frame.getContentPane(), prefix + "-main.png");

        previewAndInstall();
        if (lastPreviewHtml != null) {
            JEditorPane ep = new JEditorPane("text/html", lastPreviewHtml);
            ep.setEditable(false);
            JPanel box = new JPanel(new BorderLayout(0, 8));
            box.setBorder(new EmptyBorder(10, 10, 10, 10));
            JLabel caption = new JLabel("<html><b>" + esc(L("Installation preview", "Kurulum önizlemesi")) + "</b></html>");
            box.add(caption, BorderLayout.NORTH);
            box.add(new JScrollPane(ep), BorderLayout.CENTER);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttons.add(new JButton(L("Install", "Kur")));
            buttons.add(new JButton(L("Back", "Geri dön")));
            box.add(buttons, BorderLayout.SOUTH);
            JFrame f = new JFrame();
            f.setContentPane(box);
            f.setSize(1000, 680);
            f.addNotify();
            f.validate();
            writePng(box, prefix + "-preview.png");
            f.dispose();
        }
        frame.dispose();
    }

    static void writePng(Component c, String file) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(c.getWidth(), c.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        c.printAll(g);
        g.dispose();
        try {
            javax.imageio.ImageIO.write(img, "png", new File(file));
            System.out.println("written: " + file + " (" + c.getWidth() + "x" + c.getHeight() + ")");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ self test (--test)

    boolean selfTest(Path target) {
        boolean ok = true;
        System.out.println("Package dir : " + root);
        System.out.println("Target dir  : " + target);
        if (ideaVersion != null) System.out.println("IDEA        : " + ideaVersion + " (" + ideaBuild + ")");
        System.out.println("Pack        : " + compatibilitySummary());
        refreshStatus(target);
        System.out.println("Target IDEA : " + (ideBuildForTarget == null ? "unknown" : Arrays.toString(ideBuildForTarget)));
        for (Plugin p : plugins) {
            List<String> missing = new ArrayList<>();
            for (String i : p.images) if (!Files.isRegularFile(root.resolve(i))) missing.add(i);
            boolean good = p.fileOk && missing.isEmpty() && (p.isRules() || p.zipRoot != null) && p.text.containsKey("en") && p.text.containsKey("tr");
            ok &= good;
            System.out.printf(Locale.ROOT, "%-5s %-32s %-15s %-13s %-9s %s%s%s%n", good ? "OK" : "ERROR", p.name(), rangeText(p), statusText(p.status()),
                    sizeText(p.unpackedSize), p.isRules() ? "(rules)" : p.zipRoot, p.parts > 0 ? " [" + p.parts + " parts]" : "",
                    missing.isEmpty() ? "" : " missing images: " + missing);
        }
        if (System.getProperty("test.install") != null) {
            Path backup = siblingDir(target, "plugin-backups").resolve("test");
            Installer inst = new Installer(target, backup, System.out::println);
            for (Plugin p : plugins) {
                if (!platformMatches(p.platform)) continue;
                try {
                    if (p.isRules()) inst.copyRules(p, root, rulesDir(target));
                    else inst.installPlugin(p, root);
                } catch (Exception ex) {
                    ok = false;
                    System.out.println("ERROR " + p.name() + ": " + ex);
                }
            }
            refreshStatus(target);
            for (Plugin p : plugins) {
                if (!platformMatches(p.platform)) continue;
                boolean installed = p.installedDir != null;
                ok &= installed;
                System.out.println((installed ? "OK    " : "ERROR ") + p.name() + " → " + statusText(p.status()) + (p.installedVersion != null ? " " + p.installedVersion : ""));
            }
        }
        System.out.println(ok ? "RESULT: OK" : "RESULT: ERRORS");
        return ok;
    }

    // ------------------------------------------------------------------ table

    final class PluginTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;

        @Override public int getRowCount() { return visiblePlugins().size(); }
        @Override public int getColumnCount() { return 6; }

        @Override public String getColumnName(int c) {
            switch (c) {
                case 0: return "";
                case 1: return L("Plugin", "Eklenti");
                case 2: return L("Category", "Kategori");
                case 3: return "IDEA";
                case 4: return L("Status", "Durum");
                default: return L("Offline", "İnternetsiz");
            }
        }

        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0 && visiblePlugins().get(r).selectable(); }

        @Override public Object getValueAt(int r, int c) {
            Plugin p = visiblePlugins().get(r);
            switch (c) {
                case 0: return p.selected;
                case 1: return p.recommended ? p.name() + "  ★" : p.name();
                case 2: return categoryName(p.category);
                case 3: return rangeText(p);
                case 4: return statusText(p.status());
                default: return offlineText(p.offline);
            }
        }

        @Override public void setValueAt(Object v, int r, int c) {
            if (c != 0) return;
            Plugin p = visiblePlugins().get(r);
            p.selected = Boolean.TRUE.equals(v) && p.selectable();
            // selecting the rule jars also selects SpotBugs if it is not installed
            if (p.selected && p.isRules()) {
                Plugin sb = byId("spotbugs");
                if (sb != null && sb.installedDir == null && !sb.selected && sb.selectable()) { sb.selected = true; fireTableDataChanged(); }
            }
            fireTableRowsUpdated(r, r);
            updateSummary();
        }
    }

    final class StatusRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
            if (!sel) {
                Plugin p = visiblePlugins().get(t.convertRowIndexToModel(r));
                Color color = p.selectable() ? Color.DARK_GRAY : Color.GRAY;
                if (c == 3 && !p.compatible) color = new Color(0xc0392b);
                if (c == 4) color = statusColor(p.status());
                if (c == 5) color = "full".equals(p.offline) ? new Color(0x2e8b57) : "partial".equals(p.offline) ? new Color(0xb9770e) : new Color(0xc0392b);
                comp.setForeground(color);
            }
            return comp;
        }
    }

    // ------------------------------------------------------------------ image panel

    static final class ImagePanel extends JPanel {
        private static final long serialVersionUID = 1L;
        Image image;
        String message;

        ImagePanel() {
            setBackground(new Color(0x2b2d30));
            setPreferredSize(new Dimension(600, 340));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        void set(Image img, String msg) {
            if (image != null && image != img) image.flush();
            image = img;
            message = msg;
            repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth() - 16, h = getHeight() - 16;
            if (image != null) {
                int iw = image.getWidth(this), ih = image.getHeight(this);
                if (iw > 0 && ih > 0) {
                    double scale = Math.min(1.0, Math.min((double) w / iw, (double) h / ih));
                    int dw = (int) (iw * scale), dh = (int) (ih * scale);
                    g.drawImage(image, (getWidth() - dw) / 2, (getHeight() - dh) / 2, dw, dh, this);
                } else {
                    text(g, L("Loading…", "Yükleniyor…"));
                }
            } else if (message != null) {
                text(g, message);
            }
            g.dispose();
        }

        void text(Graphics2D g, String s) {
            g.setColor(new Color(0xb0b3b8));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(s, Math.max(8, (getWidth() - fm.stringWidth(s)) / 2), getHeight() / 2);
        }
    }

    // ------------------------------------------------------------------ minimal JSON reader

    static final class Json {
        final String s;
        int i;

        Json(String s) { this.s = s; }

        Object parse() {
            Object v = value();
            ws();
            if (i != s.length()) throw error("Trailing data");
            return v;
        }

        Object value() {
            ws();
            if (i >= s.length()) throw error("Unexpected end");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (s.charAt(i++) != ':') throw error("':' expected");
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw error("',' or '}' expected");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw error("',' or ']' expected");
            }
        }

        String string() {
            if (s.charAt(i) != '"') throw error("'\"' expected");
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': b.append('\r'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: b.append(e);
                    }
                } else b.append(c);
            }
        }

        Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw error("Invalid value");
            return Double.parseDouble(s.substring(start, i));
        }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        IllegalArgumentException error(String m) { return new IllegalArgumentException(m + " (position " + i + ")"); }
    }
}
