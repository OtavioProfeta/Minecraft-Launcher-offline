import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Holds all user-configurable launch settings and handles launcher_profiles.json persistence.
 *
 * JSON mapping:
 *   root.selectedProfile          → selectedProfileKey (key inside "profiles" object)
 *   root.settings.username        → username
 *   root.settings.ram             → ram
 *   profile.lastVersionId         → version
 *   profile.gameDir               → gameDir
 *   profile.javaDir               → javaExe
 *
 * All other profile fields (icon, javaArgs, resolution, name, type, etc.) are
 * preserved unchanged when saving so external launchers stay compatible.
 */
public class LaunchConfig {

    private static final String CONFIG_FILE = "launcher_profiles.json";

    private String username;
    private String version;
    private String gameDir;
    private String ram;
    private String javaExe;

    // Full parsed JSON tree — preserved across load/save so we never lose
    // profiles or fields we don't manage.
    private Map<String, Object> root;
    private String selectedProfileKey;

    // ==========================================
    // CONSTRUCTOR WITH DEFAULTS
    // ==========================================
    public LaunchConfig() {
        this.username           = "Jogador";
        this.gameDir            = System.getenv("APPDATA") + "\\.minecraft";
        this.ram                = "4G";
        this.javaExe            = "javaw.exe";
        this.version            = "";
        this.selectedProfileKey = "";
        this.root               = buildDefaultRoot();
    }

    private Map<String, Object> buildDefaultRoot() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("profiles", new LinkedHashMap<String, Object>());
        r.put("selectedProfile", "");
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("profileSorting", "byName");
        r.put("settings", s);
        return r;
    }

    // ==========================================
    // RESOLVED GAME DIR
    // ==========================================
    public String getResolvedGameDir() {
        String dir = gameDir;
        if (dir.toUpperCase().contains("%APPDATA%")) {
            dir = dir.replace("%APPDATA%", System.getenv("APPDATA"));
        }
        File base = new File(dir);
        if (!base.getName().equalsIgnoreCase(".minecraft") && new File(base, ".minecraft").exists()) {
            base = new File(base, ".minecraft");
        }
        return base.getAbsolutePath();
    }

    // ==========================================
    // LOAD
    // ==========================================
    @SuppressWarnings("unchecked")
    public static LaunchConfig load() {
        LaunchConfig config = new LaunchConfig();
        File file = new File(CONFIG_FILE);
        if (!file.exists()) return config;

        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String json = new String(bytes, StandardCharsets.UTF_8);
            Object parsed = new JsonParser(json).parse();
            if (parsed instanceof Map) {
                config.root = (Map<String, Object>) parsed;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return config;
        }

        // selectedProfile is the key inside the "profiles" object
        Object selRaw = config.root.get("selectedProfile");
        config.selectedProfileKey = selRaw instanceof String ? (String) selRaw : "";

        // Global settings: username, ram
        Object settingsRaw = config.root.get("settings");
        if (settingsRaw instanceof Map) {
            Map<String, Object> settings = (Map<String, Object>) settingsRaw;
            if (settings.get("username") instanceof String) config.username = (String) settings.get("username");
            if (settings.get("ram")      instanceof String) config.ram      = (String) settings.get("ram");
        }

        // Per-profile fields: version, gameDir, javaExe
        Object profilesRaw = config.root.get("profiles");
        if (profilesRaw instanceof Map && !config.selectedProfileKey.isEmpty()) {
            Map<String, Object> profiles = (Map<String, Object>) profilesRaw;
            Object profileRaw = profiles.get(config.selectedProfileKey);
            if (profileRaw instanceof Map) {
                Map<String, Object> profile = (Map<String, Object>) profileRaw;
                if (profile.get("lastVersionId") instanceof String) config.version = (String) profile.get("lastVersionId");
                if (profile.get("gameDir")        instanceof String) config.gameDir = (String) profile.get("gameDir");
                if (profile.get("javaDir")        instanceof String) config.javaExe = (String) profile.get("javaDir");
            }
        }

        return config;
    }

    // ==========================================
    // SAVE
    // ==========================================
    @SuppressWarnings("unchecked")
    public void save() {
        // Update global settings (preserves existing keys like profileSorting)
        Map<String, Object> settings = (Map<String, Object>) root.computeIfAbsent(
                "settings", k -> new LinkedHashMap<String, Object>());
        settings.putIfAbsent("profileSorting", "byName");
        settings.put("username", username);
        settings.put("ram", ram);

        // Get (or create) the profiles map
        Map<String, Object> profiles = (Map<String, Object>) root.computeIfAbsent(
                "profiles", k -> new LinkedHashMap<String, Object>());

        // Generate a profile key if none is set
        if (selectedProfileKey == null || selectedProfileKey.isEmpty()) {
            selectedProfileKey = UUID.randomUUID().toString().replace("-", "");
        }
        root.put("selectedProfile", selectedProfileKey);

        // Create profile entry only if it doesn't already exist
        boolean isNew = !profiles.containsKey(selectedProfileKey);
        Map<String, Object> profile = (Map<String, Object>) profiles.computeIfAbsent(
                selectedProfileKey, k -> new LinkedHashMap<String, Object>());

        String now = OffsetDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"));

        if (isNew) {
            profile.put("name", selectedProfileKey);
            profile.put("type", "custom");
            profile.put("created", now);
        }
        // Always update these fields; other fields (icon, javaArgs, resolution…) are untouched
        profile.put("lastUsed", now);
        profile.put("lastVersionId", version);
        profile.put("gameDir", gameDir);
        profile.put("javaDir", javaExe);

        // Serialize and write
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(CONFIG_FILE), StandardCharsets.UTF_8)) {
            writer.write(toJson(root, 0));
            writer.write("\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ==========================================
    // JSON WRITER
    // ==========================================
    @SuppressWarnings("unchecked")
    private String toJson(Object value, int indent) {
        if (value == null)            return "null";
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number)  return value.toString();
        if (value instanceof String)  return "\"" + escapeJson((String) value) + "\"";

        String pad   = "  ".repeat(indent);
        String inner = "  ".repeat(indent + 1);

        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            if (map.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder("{\n");
            Iterator<Map.Entry<String, Object>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Object> entry = it.next();
                sb.append(inner)
                  .append("\"").append(escapeJson(entry.getKey())).append("\": ")
                  .append(toJson(entry.getValue(), indent + 1));
                if (it.hasNext()) sb.append(",");
                sb.append("\n");
            }
            sb.append(pad).append("}");
            return sb.toString();
        }

        if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            if (list.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder("[\n");
            Iterator<Object> it = list.iterator();
            while (it.hasNext()) {
                sb.append(inner).append(toJson(it.next(), indent + 1));
                if (it.hasNext()) sb.append(",");
                sb.append("\n");
            }
            sb.append(pad).append("]");
            return sb.toString();
        }

        return "\"" + escapeJson(value.toString()) + "\"";
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ==========================================
    // JSON PARSER (recursive descent, no external deps)
    // ==========================================
    static class JsonParser {
        private final String src;
        private int pos;

        JsonParser(String src) { this.src = src; this.pos = 0; }

        Object parse() {
            skipWs();
            return pos < src.length() ? parseValue() : null;
        }

        private Object parseValue() {
            skipWs();
            if (pos >= src.length()) return null;
            char c = src.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't') { pos += 4; return Boolean.TRUE;  }
            if (c == 'f') { pos += 5; return Boolean.FALSE; }
            if (c == 'n') { pos += 4; return null;          }
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // skip '{'
            skipWs();
            if (pos < src.length() && src.charAt(pos) == '}') { pos++; return map; }
            while (pos < src.length()) {
                skipWs();
                if (src.charAt(pos) != '"') break;
                String key = parseString();
                skipWs();
                if (pos < src.length() && src.charAt(pos) == ':') pos++;
                skipWs();
                map.put(key, parseValue());
                skipWs();
                if (pos >= src.length()) break;
                char c = src.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; break; }
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // skip '['
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ']') { pos++; return list; }
            while (pos < src.length()) {
                skipWs();
                list.add(parseValue());
                skipWs();
                if (pos >= src.length()) break;
                char c = src.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; break; }
            }
            return list;
        }

        private String parseString() {
            pos++; // skip opening '"'
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') break;
                if (c == '\\' && pos < src.length()) {
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'u':
                            if (pos + 4 <= src.length()) {
                                sb.append((char) Integer.parseInt(
                                        src.substring(pos, pos + 4), 16));
                                pos += 4;
                            }
                            break;
                        default: sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Number parseNumber() {
            int start = pos;
            while (pos < src.length() && "0123456789.-+eE".indexOf(src.charAt(pos)) >= 0) pos++;
            String s = src.substring(start, pos);
            try { return Long.parseLong(s);     } catch (NumberFormatException ignored) {}
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
            return 0;
        }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }
    }

    // ==========================================
    // GETTERS / SETTERS
    // ==========================================
    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }

    public String getVersion() { return version; }
    public void setVersion(String v) { this.version = v; }

    public String getGameDir() { return gameDir; }
    public void setGameDir(String v) { this.gameDir = v; }

    public String getRam() { return ram; }
    public void setRam(String v) { this.ram = v; }

    public String getJavaExe() { return javaExe; }
    public void setJavaExe(String v) { this.javaExe = v; }
}
