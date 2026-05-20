import java.io.*;
import java.util.*;

/**
 * Builds the JVM launch command from a LaunchConfig + VersionAnalyzer,
 * then starts the Minecraft process.
 */
public class GameLauncher {

    private final LaunchConfig config;

    public GameLauncher(LaunchConfig config) {
        this.config = config;
    }

    // ==========================================
    // ENTRY POINT
    // Returns null on success, or an error/log string on failure.
    // ==========================================
    public String launch() {
        String mcFolder = config.getResolvedGameDir();
        String version  = config.getVersion();

        VersionAnalyzer analyzer = new VersionAnalyzer(mcFolder, version);
        if (!analyzer.isValid()) {
            return "Arquivo JSON da versão não encontrado:\n"
                 + mcFolder + "\\versions\\" + version + "\\" + version + ".json";
        }

        List<String> command = buildCommand(mcFolder, version, analyzer);
        return runProcess(command, mcFolder);
    }

    // ==========================================
    // COMMAND BUILDER
    // ==========================================
    private List<String> buildCommand(String mcFolder, String version, VersionAnalyzer analyzer) {
        List<String> cmd = new ArrayList<>();

        String mainClass   = analyzer.getMainClass();
        String baseVersion = analyzer.getInheritsFrom() != null ? analyzer.getInheritsFrom() : version;
        // Native libraries live next to the version JAR of the base game
        String nativeDir   = mcFolder + "\\versions\\" + baseVersion + "\\" + baseVersion;

        // -- Java executable
        cmd.add(config.getJavaExe());

        // -- Memory flags
        cmd.add("-Xmx" + config.getRam());
        cmd.add("-Xss1M");
        cmd.add("-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump");

        // -- Native library paths
        cmd.add("-Djava.library.path="                        + nativeDir);
        cmd.add("-Djna.tmpdir="                               + nativeDir);
        cmd.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + nativeDir);
        cmd.add("-Dio.netty.native.workdir="                  + nativeDir);

        // -- Launcher branding (some versions check these)
        cmd.add("-Dminecraft.launcher.brand=minecraft-launcher");
        cmd.add("-Dminecraft.launcher.version=2.25.4");

        // -- Log4Shell mitigation (CVE-2021-44228)
        cmd.add("-Dlog4j2.formatMsgNoLookups=true");

        // -- G1GC tuning
        cmd.add("-XX:+UnlockExperimentalVMOptions");
        cmd.add("-XX:+UseG1GC");
        cmd.add("-XX:G1NewSizePercent=20");
        cmd.add("-XX:G1ReservePercent=20");
        cmd.add("-XX:MaxGCPauseMillis=50");
        cmd.add("-XX:G1HeapRegionSize=32M");

        // -- Classpath
        cmd.add("-cp");
        cmd.add(analyzer.getFinalClasspath());

        // -- Main class
        cmd.add(mainClass);

        // -- Loader-specific extra arguments (tweakers, etc.)
        addLoaderArguments(cmd, analyzer);

        // -- Standard Minecraft game arguments
        addMinecraftArguments(cmd, mcFolder, version, analyzer.discoverAssetIndex());

        return cmd;
    }

    /**
     * Appends loader-specific arguments after the main class.
     *
     * Rules derived from HMCL's LibraryAnalyzer constants and Minecraft launch docs:
     *   - FORGE_LEGACY / LITELOADER  → LaunchWrapper needs --tweakClass <value(s)>
     *   - OPTIFINE standalone         → always needs --tweakClass optifine.OptiFineTweaker
     *   - FORGE_MODERN / BOOTSTRAP   → ModLauncher/BootstrapLauncher: no tweakers needed
     *   - FABRIC / QUILT / VANILLA   → no extra args
     */
    private void addLoaderArguments(List<String> cmd, VersionAnalyzer analyzer) {
        VersionAnalyzer.LoaderType type     = analyzer.getLoaderType();
        List<String>               tweakers = analyzer.getTweakers();

        switch (type) {
            case FORGE_LEGACY:
            case LITELOADER:
                if (!tweakers.isEmpty()) {
                    // Use tweakers extracted directly from the version JSON
                    for (String tweaker : tweakers) {
                        cmd.add("--tweakClass");
                        cmd.add(tweaker);
                    }
                } else {
                    // Fallback to well-known tweaker class when JSON didn't list one
                    cmd.add("--tweakClass");
                    cmd.add(type == VersionAnalyzer.LoaderType.LITELOADER
                            ? "com.mumfrey.liteloader.launch.LiteLoaderTweaker"
                            : "cpw.mods.fml.common.launcher.FMLTweaker");
                }
                break;

            case OPTIFINE:
                // OptiFine standalone always uses this tweaker regardless of JSON
                cmd.add("--tweakClass");
                cmd.add("optifine.OptiFineTweaker");
                break;

            case FORGE_MODERN:
            case FORGE_BOOTSTRAP:
            case NEO_FORGE:
            case CLEANROOM:
                // ModLauncher / BootstrapLauncher handle everything internally
                break;

            case FABRIC:
            case QUILT:
            case VANILLA:
            default:
                break;
        }
    }

    private void addMinecraftArguments(List<String> cmd, String mcFolder, String version, String assetIndex) {
        cmd.add("--username");    cmd.add(config.getUsername());
        cmd.add("--version");     cmd.add(version);
        cmd.add("--gameDir");     cmd.add(mcFolder);
        cmd.add("--assetsDir");   cmd.add(mcFolder + "\\assets");
        cmd.add("--assetIndex");  cmd.add(assetIndex);
        cmd.add("--accessToken"); cmd.add("0");
        cmd.add("--clientId");    cmd.add("0");
        cmd.add("--xuid");        cmd.add("0");
        cmd.add("--userType");    cmd.add("msa");
        cmd.add("--versionType"); cmd.add("release");
    }

    // ==========================================
    // PROCESS RUNNER
    // ==========================================
    private String runProcess(List<String> command, String workDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(workDir));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Wait briefly to catch immediate JVM crash before declaring success
            Thread.sleep(1500);

            if (!process.isAlive() && process.exitValue() != 0) {
                return readProcessOutput(process);
            }
            return null; // success — game is running
        } catch (Exception e) {
            return "Erro ao iniciar o processo:\n" + e.getMessage();
        }
    }

    private String readProcessOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            StringBuilder log = new StringBuilder();
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count++ < 40) {
                log.append(line).append("\n");
            }
            return log.toString();
        }
    }
}
