import java.io.*;
import java.util.*;
import java.util.regex.*;

/**
 *
 * Design inspired by HMCL's LibraryAnalyzer:
 *   https://github.com/HMCL-dev/HMCL (GPLv3)
 */
public class VersionAnalyzer {

    // ==========================================
    // WELL-KNOWN MAIN CLASS CONSTANTS  (from HMCL's LibraryAnalyzer)
    // ==========================================
    public static final String VANILLA_MAIN         = "net.minecraft.client.main.Main";
    public static final String LAUNCH_WRAPPER_MAIN  = "net.minecraft.launchwrapper.Launch";
    public static final String MOD_LAUNCHER_MAIN    = "cpw.mods.modlauncher.Launcher";
    public static final String BOOTSTRAP_LAUNCHER   = "cpw.mods.bootstraplauncher.BootstrapLauncher";
    public static final String FORGE_BOOTSTRAP_MAIN = "net.minecraftforge.bootstrap.ForgeBootstrap";
    public static final String NEO_FORGE_MAIN       = "net.neoforged.fml.startup.Client";

    // ==========================================
    // LOADER TYPES
    // ==========================================
    public enum LoaderType {
        VANILLA,
        FABRIC,
        QUILT,
        FORGE_LEGACY,    // ≤ 1.12.2  — LaunchWrapper + tweakClass
        FORGE_MODERN,    // 1.13–1.16 — ModLauncher
        FORGE_BOOTSTRAP, // 1.17+     — BootstrapLauncher / ForgeBootstrap
        NEO_FORGE,
        OPTIFINE,        // OptiFine standalone (tweakClass-based)
        LITELOADER,
        CLEANROOM,
        UNKNOWN
    }

    // ==========================================
    // LIBRARY DETECTION PATTERNS  (mirrors HMCL's LibraryType enum)
    // Each enum value holds compiled regex patterns for groupId and artifactId.
    // ==========================================
    private enum LibraryPattern {
        FABRIC      ("net\\.fabricmc",               "fabric-loader"),
        QUILT       ("org\\.quiltmc",                "quilt-loader"),
        // NeoForge uses net.neoforged.fancymodloader (core or loader artifact)
        NEO_FORGE   ("net\\.neoforged(\\.fancymodloader)?", "(core|loader|neoforge)"),
        // Forge: exclude NeoForge hits via higher-priority check in detectLoaderType()
        FORGE       ("net\\.minecraftforge",         "(forge|fmlloader)"),
        LITELOADER  ("com\\.mumfrey",                "liteloader"),
        // OptiFine may live under net.optifine or optifine
        OPTIFINE    ("(net\\.)?optifine",            "OptiFine"),
        CLEANROOM   ("com\\.cleanroommc",            "cleanroom");

        private final Pattern groupPat, artifactPat;

        LibraryPattern(String group, String artifact) {
            this.groupPat    = Pattern.compile(group);
            this.artifactPat = Pattern.compile(artifact);
        }

        /** Returns true when the maven coordinate (group:artifact:version…) matches this pattern. */
        boolean matches(String mavenCoord) {
            String[] parts = mavenCoord.split(":");
            if (parts.length < 2) return false;
            return groupPat.matcher(parts[0]).matches()
                && artifactPat.matcher(parts[1]).matches();
        }
    }

    // ==========================================
    // STATE
    // ==========================================
    private final String minecraftFolder;
    private final String version;

    private String     mainClass;
    private String     inheritsFrom;
    private LoaderType loaderType = LoaderType.UNKNOWN;

    private final List<String> tweakers  = new ArrayList<>();
    private final List<String> classpath = new ArrayList<>();

    // ==========================================
    // CONSTRUCTOR — triggers full analysis
    // ==========================================
    public VersionAnalyzer(String minecraftFolder, String version) {
        this.minecraftFolder = minecraftFolder;
        this.version         = version;
        analyze();
    }

    // ==========================================
    // ANALYSIS PIPELINE
    // ==========================================
    private void analyze() {
        String versionJson = readFile(jsonPath(version));
        if (versionJson == null) return;

        mainClass    = extractField(versionJson, "mainClass");
        inheritsFrom = extractField(versionJson, "inheritsFrom");

        // Base ("inheritsFrom") JSON — e.g. vanilla 1.20.1 under a Fabric/Forge version
        String baseJson = null;
        if (inheritsFrom != null && !inheritsFrom.isEmpty()) {
            baseJson = readFile(jsonPath(inheritsFrom));
            // Inherit mainClass only if the modded JSON doesn't override it
            if ((mainClass == null || mainClass.isEmpty()) && baseJson != null) {
                mainClass = extractField(baseJson, "mainClass");
            }
        }

        // Collect every maven coordinate mentioned in both JSONs
        Set<String> allCoords = new LinkedHashSet<>();
        extractMavenCoords(versionJson, allCoords);
        if (baseJson != null) extractMavenCoords(baseJson, allCoords);

        loaderType = detectLoaderType(allCoords);

        extractTweakers(versionJson);
        if (baseJson != null) extractTweakers(baseJson);

        buildClasspath(allCoords);
    }

    // ==========================================
    // LOADER DETECTION
    // Priority matters: NeoForge ships Forge libs too, so check it first.
    // ==========================================
    private LoaderType detectLoaderType(Set<String> coords) {
        boolean hasFabric     = coords.stream().anyMatch(LibraryPattern.FABRIC::matches);
        boolean hasQuilt      = coords.stream().anyMatch(LibraryPattern.QUILT::matches);
        boolean hasNeoForge   = coords.stream().anyMatch(LibraryPattern.NEO_FORGE::matches);
        boolean hasForge      = coords.stream().anyMatch(LibraryPattern.FORGE::matches);
        boolean hasLiteLoader = coords.stream().anyMatch(LibraryPattern.LITELOADER::matches);
        boolean hasOptiFine   = coords.stream().anyMatch(LibraryPattern.OPTIFINE::matches);
        boolean hasCleanroom  = coords.stream().anyMatch(LibraryPattern.CLEANROOM::matches);

        if (hasFabric)  return LoaderType.FABRIC;
        if (hasQuilt)   return LoaderType.QUILT;
        if (hasCleanroom) return LoaderType.CLEANROOM;

        if (NEO_FORGE_MAIN.equals(mainClass) || hasNeoForge) return LoaderType.NEO_FORGE;

        if (hasForge) {
            if (BOOTSTRAP_LAUNCHER.equals(mainClass) || FORGE_BOOTSTRAP_MAIN.equals(mainClass))
                return LoaderType.FORGE_BOOTSTRAP;
            if (MOD_LAUNCHER_MAIN.equals(mainClass))
                return LoaderType.FORGE_MODERN;
            // LaunchWrapper path (≤ 1.12.2)
            return LoaderType.FORGE_LEGACY;
        }

        if (hasLiteLoader) return LoaderType.LITELOADER;
        if (hasOptiFine)   return LoaderType.OPTIFINE;
        if (VANILLA_MAIN.equals(mainClass)) return LoaderType.VANILLA;

        return LoaderType.UNKNOWN;
    }

    // ==========================================
    // CLASSPATH BUILDER
    // ==========================================
    private void buildClasspath(Set<String> allCoords) {
        // 1. Libraries declared by name in the JSON(s)
        for (String coord : allCoords) {
            String rel = mavenToRelPath(coord);
            if (rel == null) continue;
            File jar = new File(minecraftFolder + "\\libraries\\" + rel.replace('/', '\\'));
            if (jar.exists()) addToClasspath(jar.getAbsolutePath());
        }

        // 2. Loader-specific recursive scans
        //    Fabric/Quilt sometimes ship extra JARs that are not listed by maven coord.
        switch (loaderType) {
            case FABRIC:
                scanLibraryDir("net\\fabricmc");
                scanLibraryDir("net\\irismc");   // Iris shaders
                break;
            case QUILT:
                scanLibraryDir("org\\quiltmc");
                break;
            default:
                break;
        }

        // 3. For versions with inheritsFrom, the version JAR of the modded layer (Fabric, Forge…)
        //    must be on the classpath before the base game JAR.
        if (inheritsFrom != null && !inheritsFrom.isEmpty()) {
            File modJar = new File(minecraftFolder + "\\versions\\" + version + "\\" + version + ".jar");
            if (modJar.exists()) addToClasspath(modJar.getAbsolutePath());

            // Some Fabric / Iris installs also drop extra JARs directly in the version folder
            if (loaderType == LoaderType.FABRIC || loaderType == LoaderType.QUILT) {
                collectJarsRecursively(new File(minecraftFolder + "\\versions\\" + version));
            }
        }

        // 4. The actual Minecraft client JAR always goes last
        String gameVersion = (inheritsFrom != null && !inheritsFrom.isEmpty()) ? inheritsFrom : version;
        File gameJar = new File(minecraftFolder + "\\versions\\" + gameVersion + "\\" + gameVersion + ".jar");
        if (gameJar.exists()) addToClasspath(gameJar.getAbsolutePath());
    }

    private void scanLibraryDir(String subPath) {
        File root = new File(minecraftFolder + "\\libraries\\" + subPath);
        if (root.exists() && root.isDirectory()) collectJarsRecursively(root);
    }

    private void collectJarsRecursively(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory())                              collectJarsRecursively(f);
            else if (f.getName().toLowerCase().endsWith(".jar")) addToClasspath(f.getAbsolutePath());
        }
    }

    private void addToClasspath(String path) {
        if (!classpath.contains(path)) classpath.add(path);
    }

    // ==========================================
    // TWEAKER EXTRACTION
    // Handles both legacy "minecraftArguments" string and new "arguments.game" array.
    // ==========================================
    private void extractTweakers(String json) {
        if (json == null) return;

        // --- Legacy format: "minecraftArguments": "... --tweakClass VALUE ..."
        Matcher legacyBlock = Pattern
                .compile("\"minecraftArguments\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        if (legacyBlock.find()) {
            Matcher tweaker = Pattern
                    .compile("--tweakClass\\s+(\\S+)")
                    .matcher(legacyBlock.group(1));
            while (tweaker.find()) addTweaker(tweaker.group(1));
        }

        // --- New format: arguments.game array  ["--tweakClass", "VALUE"]
        // Strategy: locate every "--tweakClass" JSON string and grab the next quoted value.
        int idx = json.indexOf("\"--tweakClass\"");
        while (idx >= 0) {
            int nextOpen = json.indexOf('"', idx + 14);   // skip past "--tweakClass"
            if (nextOpen < 0) break;
            int nextClose = json.indexOf('"', nextOpen + 1);
            if (nextClose < 0) break;
            String value = json.substring(nextOpen + 1, nextClose);
            // Basic sanity check: tweaker classes don't start with '-'
            if (!value.startsWith("-")) addTweaker(value);
            idx = json.indexOf("\"--tweakClass\"", idx + 1);
        }
    }

    private void addTweaker(String tweaker) {
        if (!tweakers.contains(tweaker)) tweakers.add(tweaker);
    }

    // ==========================================
    // ASSET INDEX DISCOVERY
    // ==========================================
    public String discoverAssetIndex() {
        File indexesDir = new File(minecraftFolder + "\\assets\\indexes");
        if (!indexesDir.exists() || !indexesDir.isDirectory()) return "legacy";

        File[] jsonFiles = indexesDir.listFiles((d, n) -> n.endsWith(".json"));
        if (jsonFiles == null || jsonFiles.length == 0) return "legacy";

        // Try to match major.minor of the base game version (e.g. "1.20" for "1.20.1-forge-…")
        String base   = (inheritsFrom != null && !inheritsFrom.isEmpty()) ? inheritsFrom : version;
        String[] parts = base.split("[.\\-]");
        String prefix = parts.length >= 2 ? parts[0] + "." + parts[1] : parts[0];

        for (File f : jsonFiles) {
            if (f.getName().startsWith(prefix)) return f.getName().replace(".json", "");
        }

        // Fallback: most recently modified index
        return Arrays.stream(jsonFiles)
                .max(Comparator.comparingLong(File::lastModified))
                .map(f -> f.getName().replace(".json", ""))
                .orElse("legacy");
    }

    // ==========================================
    // JSON / FILE HELPERS
    // ==========================================
    private String jsonPath(String ver) {
        return minecraftFolder + "\\versions\\" + ver + "\\" + ver + ".json";
    }

    private String readFile(String path) {
        File f = new File(path);
        if (!f.exists()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /** Extracts the first string value for a JSON key, e.g. "mainClass": "…" */
    private String extractField(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Collects every maven coordinate from "name": "group:artifact:version" entries. */
    private void extractMavenCoords(String json, Set<String> result) {
        Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (m.find()) result.add(m.group(1));
    }

    /** Converts a maven coordinate to a relative file path. */
    private String mavenToRelPath(String coord) {
        try {
            String[] p = coord.split(":");
            if (p.length < 3) return null;
            String group      = p[0].replace('.', '/');
            String artifact   = p[1];
            String ver        = p[2];
            String classifier = (p.length >= 4) ? "-" + p[3] : "";
            return group + "/" + artifact + "/" + ver + "/" + artifact + "-" + ver + classifier + ".jar";
        } catch (Exception e) {
            return null;
        }
    }

    // ==========================================
    // GETTERS
    // ==========================================
    public String     getMainClass()       { return mainClass != null ? mainClass : VANILLA_MAIN; }
    public String     getInheritsFrom()    { return inheritsFrom; }
    public LoaderType getLoaderType()      { return loaderType; }
    public List<String> getTweakers()      { return Collections.unmodifiableList(tweakers); }
    public List<String> getClasspath()     { return Collections.unmodifiableList(classpath); }
    public String     getFinalClasspath()  { return String.join(";", classpath); }

    /** Returns true when the version JSON file actually exists on disk. */
    public boolean isValid() {
        return new File(jsonPath(version)).exists();
    }
}
