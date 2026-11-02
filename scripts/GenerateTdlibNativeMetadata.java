import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;

/** Inspects class declarations without initializing TdApi/Client or loading any DLL. */
class GenerateTdlibNativeMetadata {
    private static final String PACKAGE = "org.drinkless.tdlib.";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Expected repository root and optional --check/--binding=/--output=");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        boolean check = false;
        Path binding = root.resolve("native/windows-x64/tdlib.jar");
        Path resources = root.resolve("shared/src/jvmMain/resources");
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--check")) check = true;
            else if (args[i].startsWith("--binding=")) binding = root.resolve(args[i].substring(10));
            else if (args[i].startsWith("--output=")) resources = root.resolve(args[i].substring(9));
            else throw new IllegalArgumentException("Unknown metadata generation option");
        }
        Properties pin = new Properties();
        try (InputStream input = Files.newInputStream(root.resolve("native/tdlib-source.properties"))) { pin.load(input); }
        Properties provenance = new Properties();
        List<String> names;
        try (JarFile jar = new JarFile(binding.toFile())) {
            var entry = jar.getJarEntry("META-INF/nnggram-tdlib.properties");
            if (entry == null) throw new IllegalStateException("Binding provenance is missing");
            try (InputStream input = jar.getInputStream(entry)) { provenance.load(input); }
            names = jar.stream().map(e -> e.getName())
                .filter(n -> n.equals("org/drinkless/tdlib/TdApi.class") ||
                    (n.startsWith("org/drinkless/tdlib/TdApi$") && n.endsWith(".class")) ||
                    n.equals("org/drinkless/tdlib/Client.class") ||
                    n.equals("org/drinkless/tdlib/Client$LogMessageHandler.class"))
                .map(n -> n.substring(0, n.length() - 6).replace('/', '.')).sorted().toList();
        }
        String commit = pin.getProperty("commit");
        if (commit == null || !commit.matches("[a-f0-9]{40}") ||
            !commit.equals(provenance.getProperty("java.api.commit")) ||
            !commit.equals(provenance.getProperty("source.commit")) ||
            !"absolute-path-v1".equals(provenance.getProperty("loader.patch"))) {
            throw new IllegalStateException("Binding and pinned source do not match");
        }
        List<String> entries = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{binding.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> apiObject = Class.forName(PACKAGE + "TdApi$Object", false, loader);
            for (String name : names) {
                Class<?> type = Class.forName(name, false, loader);
                // Generated JNI serializers use AllocObject, bypassing Java constructors.
                // FindClass/field registration alone does not make a class allocatable in Native Image.
                List<String> members = new ArrayList<>();
                members.add("\"name\":" + quote(name));
                if (apiObject.isAssignableFrom(type) && !type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
                    // JNI's registry also registers unsafe allocation. Avoid a second reflective
                    // class registration, which expands every TdApi nested class's entire nest.
                    members.add("\"unsafeAllocated\":true");
                }
                List<String> fields = Arrays.stream(type.getDeclaredFields())
                    .filter(f -> !Modifier.isStatic(f.getModifiers()) || f.getName().equals("GIT_COMMIT_HASH"))
                    .sorted(Comparator.comparing(Field::getName))
                    .map(f -> "{\"name\":" + quote(f.getName()) + "}").toList();
                if (!fields.isEmpty()) members.add("\"fields\":[" + String.join(",", fields) + "]");
                List<String> methods = Arrays.stream(type.getDeclaredMethods())
                    .filter(m -> Modifier.isNative(m.getModifiers()) || m.getName().equals("getConstructor") ||
                        (name.equals(PACKAGE + "Client$LogMessageHandler") && m.getName().equals("onLogMessage")))
                    .sorted(Comparator.comparing(Method::toGenericString))
                    .map(m -> method(m.getName(), Arrays.stream(m.getParameterTypes()).map(Class::getTypeName).toList())).toList();
                if (!methods.isEmpty()) members.add("\"methods\":[" + String.join(",", methods) + "]");
                entries.add("  {" + String.join(",", members) + "}");
            }
        }
        // tl_jni_object.cpp resolves these arrays and boxed primitive accessors eagerly in JNI_OnLoad.
        for (String name : List.of("KeyboardButton", "InlineKeyboardButton", "PageBlockTableCell")) {
            entries.add("  {\"name\":" + quote(PACKAGE + "TdApi$" + name + "[]") + "}");
        }
        String[] boxed = {"Boolean", "Integer", "Long", "Double"};
        String[] accessors = {"booleanValue", "intValue", "longValue", "doubleValue"};
        for (int i = 0; i < boxed.length; i++) {
            entries.add("  {\"name\":" + quote("java.lang." + boxed[i]) + ",\"methods\":[" + method(accessors[i], List.of()) + "]}");
        }
        entries.add("  {\"name\":\"java.lang.String\"}");
        Path metadata = resources.resolve("META-INF/native-image/com.badnng.moe/tdlib");
        write(metadata.resolve("jni-config.json"), "[\n" + String.join(",\n", entries) + "\n]\n", check);
        // Clear the old duplicate reflection registry during metadata regeneration.
        // Keep a checked, empty file so stale incremental resources cannot reintroduce it.
        write(metadata.resolve("reflect-config.json"), "[]\n", check);
        write(metadata.resolve("resource-config.json"), """
            {"resources":{"includes":[
              {"pattern":"\\\\Qtdlib-source.properties\\\\E"},
              {"pattern":"\\\\Qtdlib-native-image.properties\\\\E"},
              {"pattern":"\\\\QMETA-INF/nnggram-tdlib.properties\\\\E"}
            ]}}
            """, check);
        write(metadata.resolve("native-image.properties"),
            "# TDLib's static initializers load a validated external JNI bundle only at application runtime.\n" +
            "Args = --initialize-at-run-time=org.drinkless.tdlib,com.badnng.moe.tdlib.NativeBundle\n", check);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(binding)));
        write(resources.resolve("tdlib-native-image.properties"),
            "# Generated by scripts/generate-tdlib-native-metadata.ps1; no native code is executed.\n" +
            "source.commit=" + commit + "\njava.api.commit=" + commit + "\nloader.patch=absolute-path-v1\n" +
            "binding.sha256=" + sha + "\njni.type.count=" + names.size() + "\n", check);
        System.out.println((check ? "Verified" : "Generated") + " pinned TDLib JNI metadata for " + names.size() + " binding types");
    }

    private static String method(String name, List<String> parameters) {
        return "{\"name\":" + quote(name) + ",\"parameterTypes\":[" + String.join(",", parameters.stream().map(GenerateTdlibNativeMetadata::quote).toList()) + "]}";
    }

    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    private static void write(Path file, String content, boolean check) throws Exception {
        if (check) {
            if (!Files.isRegularFile(file) || !Files.readString(file, StandardCharsets.UTF_8).equals(content)) {
                throw new IllegalStateException("Stale TDLib Native Image metadata: " + file.getFileName());
            }
        } else {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }
    }
}
