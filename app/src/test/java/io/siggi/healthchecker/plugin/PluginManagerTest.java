package io.siggi.healthchecker.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.siggi.healthchecker.api.CheckPlugin;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.api.Checker;
import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.api.EndpointConfig;
import io.siggi.healthchecker.api.SettingSpec;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.Visibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginManagerTest {
    @TempDir
    Path tmp;

    private static final ClassLoader APP = PluginManagerTest.class.getClassLoader();

    // ---- test plugin jars, compiled during the test --------------------------------------

    /** Source of a plugin class {@code testplugins.<cls>} with the given type and API version. */
    private static String pluginSource(String cls, String type, int apiVersion) {
        return """
                package testplugins;

                import com.google.gson.Gson;
                import io.siggi.healthchecker.api.*;
                import java.time.Duration;
                import java.util.List;
                import java.util.Map;

                public class %s implements CheckPlugin {
                    @Override public String type() { return "%s"; }
                    @Override public int apiVersion() { return %d; }
                    @Override public String displayName() { return "Test %s"; }
                    @Override public List<SettingSpec> settings() {
                        return List.of(SettingSpec.string("value", "Value").asRequired());
                    }
                    @Override public Checker create(EndpointConfig config) throws ConfigException {
                        String value = config.getString("value");
                        // Uses Gson from the parent (app) class loader.
                        String json = new Gson().toJson(Map.of("value", value));
                        return timeout -> CheckResult.ok(json);
                    }
                    public static Class<?> gsonClass() { return Gson.class; }
                }
                """.formatted(cls, type, apiVersion, type);
    }

    /** Compiles {@code testplugins.<cls>} classes and packs them with a services file into a jar. */
    private Path buildJar(Path dir, String jarName, String... clsTypeVersion) throws IOException {
        Path src = Files.createTempDirectory(tmp, "src");
        Path classes = Files.createTempDirectory(tmp, "classes");
        Files.createDirectories(src.resolve("testplugins"));
        List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "-classpath", compileClasspath(),
                "--release", "21", "-proc:none"));
        List<String> classNames = new ArrayList<>();
        for (int i = 0; i < clsTypeVersion.length; i += 3) {
            String cls = clsTypeVersion[i];
            Path file = src.resolve("testplugins").resolve(cls + ".java");
            Files.writeString(file, pluginSource(cls, clsTypeVersion[i + 1], Integer.parseInt(clsTypeVersion[i + 2])));
            args.add(file.toString());
            classNames.add("testplugins." + cls);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int rc = compiler.run(null, diagnostics, diagnostics, args.toArray(String[]::new));
        assertEquals(0, rc, diagnostics.toString(StandardCharsets.UTF_8));

        Files.createDirectories(dir);
        Path jar = dir.resolve(jarName);
        try (OutputStream fos = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(fos)) {
            out.putNextEntry(new JarEntry("META-INF/services/" + CheckPlugin.class.getName()));
            out.write((String.join("\n", classNames) + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            try (Stream<Path> files = Files.walk(classes)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    out.putNextEntry(new JarEntry(classes.relativize(f).toString().replace('\\', '/')));
                    out.write(Files.readAllBytes(f));
                    out.closeEntry();
                }
            }
        }
        return jar;
    }

    /** The API and Gson locations, so the test plugin compiles exactly like a real one. */
    private static String compileClasspath() {
        try {
            return Path.of(CheckPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    + java.io.File.pathSeparator
                    + Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- in-process built-ins ----------------------------------------------------------

    private record FakePlugin(String type, int apiVersion) implements CheckPlugin {
        FakePlugin(String type) {
            this(type, 1);
        }

        @Override
        public List<SettingSpec> settings() {
            return List.of();
        }

        @Override
        public Checker create(EndpointConfig config) {
            return timeout -> CheckResult.ok("fine");
        }
    }

    private static EndpointDefinition endpoint(String id, String type) {
        return endpoint(id, type, new JsonObject());
    }

    private static EndpointDefinition endpoint(String id, String type, JsonObject settings) {
        return new EndpointDefinition(id, id, type, null, Visibility.PUBLIC, 60, 5000, 1,
                List.of(), List.of(30), 0, settings);
    }

    // ---- tests ---------------------------------------------------------------------------

    @Test
    void loadsPluginJarUsingGsonFromTheParentLoader() throws Exception {
        Path plugins = tmp.resolve("plugins");
        Path jar = buildJar(plugins, "test-plugin.jar", "EchoPlugin", "echo", "1");

        try (PluginManager pm = PluginManager.load(List.of(new FakePlugin("http")), plugins, APP)) {
            assertEquals(List.of("echo", "http"), pm.availableTypes());
            assertEquals(List.of(), pm.warnings());

            List<PluginManager.LoadedPlugin> loaded = pm.plugins();
            assertEquals(2, loaded.size());
            assertEquals(PluginManager.BUILT_IN, loaded.get(0).source());
            PluginManager.LoadedPlugin echo = loaded.get(1);
            assertEquals("echo", echo.type());
            assertEquals(jar.toString(), echo.source());
            assertTrue(echo.describe().contains("testplugins.EchoPlugin"), echo.describe());
            assertTrue(echo.describe().contains("test-plugin.jar"), echo.describe());

            CheckPlugin plugin = pm.require("echo");
            assertEquals("Test echo", plugin.displayName());
            ClassLoader pluginLoader = plugin.getClass().getClassLoader();
            assertNotSame(APP, pluginLoader);
            assertSame(APP, pluginLoader.getParent());
            // API and Gson classes are shared with the app, not loaded again from the jar.
            assertSame(CheckPlugin.class, pluginLoader.loadClass(CheckPlugin.class.getName()));
            assertSame(Gson.class, plugin.getClass().getMethod("gsonClass").invoke(null));

            JsonObject settings = new JsonObject();
            settings.addProperty("value", "hi");
            JsonEndpointConfig config = new JsonEndpointConfig("e", "E", settings);
            try (Checker checker = plugin.create(config)) {
                CheckResult result = checker.check(Duration.ofSeconds(1));
                assertTrue(result.success());
                assertEquals("{\"value\":\"hi\"}", result.message());
            }
            config.checkNoUnreadKeys();
        }
    }

    @Test
    void severalPluginsInOneJarAndSeveralJars() throws Exception {
        Path plugins = tmp.resolve("plugins");
        buildJar(plugins, "b.jar", "Beta", "beta", "1", "Gamma", "gamma", "1");
        buildJar(plugins, "a.jar", "Alpha", "alpha", "1");
        Files.writeString(plugins.resolve("notes.txt"), "not a jar");
        try (PluginManager pm = PluginManager.load(List.of(), plugins, APP)) {
            assertEquals(List.of("alpha", "beta", "gamma"),
                    pm.plugins().stream().map(PluginManager.LoadedPlugin::type).toList());
        }
    }

    @Test
    void missingPluginsDirMeansNoPlugins() throws Exception {
        try (PluginManager pm = PluginManager.load(List.of(new FakePlugin("http")), tmp.resolve("nope"), APP)) {
            assertEquals(List.of("http"), pm.availableTypes());
        }
    }

    @Test
    void pluginsDirThatIsAFileIsAnError() throws Exception {
        Path file = Files.writeString(tmp.resolve("plugins"), "");
        PluginLoadException e = assertThrows(PluginLoadException.class,
                () -> PluginManager.load(List.of(), file, APP));
        assertTrue(e.getMessage().contains("not a directory"), e.getMessage());
    }

    @Test
    void jarWithoutPluginsIsAWarning() throws Exception {
        Path plugins = Files.createDirectories(tmp.resolve("plugins"));
        try (OutputStream fos = Files.newOutputStream(plugins.resolve("empty.jar"));
             JarOutputStream out = new JarOutputStream(fos)) {
            out.putNextEntry(new JarEntry("readme.txt"));
            out.closeEntry();
        }
        try (PluginManager pm = PluginManager.load(List.of(), plugins, APP)) {
            assertEquals(List.of(), pm.availableTypes());
            assertEquals(1, pm.warnings().size());
            assertTrue(pm.warnings().get(0).contains("empty.jar"), pm.warnings().get(0));
        }
    }

    @Test
    void parentServicesAreNotCountedAsTheJars() throws Exception {
        Path parentJar = buildJar(tmp.resolve("parent"), "parent.jar", "Parent", "parent", "1");
        Path plugins = tmp.resolve("plugins");
        buildJar(plugins, "child.jar", "Child", "child", "1");
        try (URLClassLoader parent = new URLClassLoader(new URL[]{parentJar.toUri().toURL()}, APP);
             PluginManager pm = PluginManager.load(List.of(), plugins, parent)) {
            assertEquals(List.of("child"), pm.availableTypes());
        }
    }

    @Test
    void duplicateTypesAcrossJarsNameBothJars() throws Exception {
        Path plugins = tmp.resolve("plugins");
        buildJar(plugins, "one.jar", "First", "dup", "1");
        buildJar(plugins, "two.jar", "Second", "dup", "1");
        PluginLoadException e = assertThrows(PluginLoadException.class,
                () -> PluginManager.load(List.of(), plugins, APP));
        assertEquals(1, e.errors().size(), e.getMessage());
        String error = e.errors().get(0);
        assertTrue(error.startsWith("duplicate type \"dup\""), error);
        assertTrue(error.contains("one.jar") && error.contains("testplugins.First"), error);
        assertTrue(error.contains("two.jar") && error.contains("testplugins.Second"), error);
    }

    @Test
    void duplicateOfABuiltInType() throws Exception {
        Path plugins = tmp.resolve("plugins");
        buildJar(plugins, "http.jar", "MyHttp", "http", "1");
        PluginLoadException e = assertThrows(PluginLoadException.class,
                () -> PluginManager.load(List.of(new FakePlugin("http")), plugins, APP));
        String error = e.errors().get(0);
        assertTrue(error.contains("duplicate type \"http\": built-in"), error);
        assertTrue(error.contains("http.jar"), error);
    }

    @Test
    void duplicateBuiltIns() {
        PluginLoadException e = assertThrows(PluginLoadException.class,
                () -> PluginManager.load(List.of(new FakePlugin("http"), new FakePlugin("http")), null, APP));
        assertTrue(e.errors().get(0).startsWith("duplicate type \"http\""), e.getMessage());
    }

    @Test
    void tooNewApiVersionIsRejected() throws Exception {
        Path plugins = tmp.resolve("plugins");
        buildJar(plugins, "future.jar", "Future", "future", String.valueOf(PluginManager.API_VERSION + 1));
        PluginLoadException e = assertThrows(PluginLoadException.class,
                () -> PluginManager.load(List.of(), plugins, APP));
        assertEquals(1, e.errors().size());
        String error = e.errors().get(0);
        assertTrue(error.contains("future.jar"), error);
        assertTrue(error.contains("needs plugin API version " + (PluginManager.API_VERSION + 1)), error);
        assertTrue(error.contains("supports up to " + PluginManager.API_VERSION), error);
    }

    @Test
    void currentAndOlderApiVersionsAreAccepted() throws Exception {
        try (PluginManager pm = PluginManager.load(
                List.of(new FakePlugin("old", 0), new FakePlugin("now", PluginManager.API_VERSION)), null, APP)) {
            assertEquals(List.of("now", "old"), pm.availableTypes());
        }
    }

    @Test
    void invalidTypeNamesAreRejected() {
        PluginLoadException e = assertThrows(PluginLoadException.class,
                () -> PluginManager.load(List.of(new FakePlugin("Bad Type"), new FakePlugin(null)), null, APP));
        assertEquals(2, e.errors().size(), e.getMessage());
        assertTrue(e.getMessage().startsWith("2 plugin errors:"), e.getMessage());
    }

    @Test
    void allErrorsAreReportedTogether() throws Exception {
        Path plugins = tmp.resolve("plugins");
        buildJar(plugins, "a.jar", "Future", "future", "99", "Dup", "http", "1");
        PluginLoadException e = assertThrows(PluginLoadException.class,
                () -> PluginManager.load(List.of(new FakePlugin("http")), plugins, APP));
        assertEquals(2, e.errors().size(), e.getMessage());
    }

    @Test
    void unknownTypeListsAvailableTypes() throws Exception {
        try (PluginManager pm = PluginManager.load(List.of(new FakePlugin("http"), new FakePlugin("dns")), null, APP)) {
            ConfigException e = assertThrows(ConfigException.class, () -> pm.require("minecraft"));
            assertEquals("unknown type \"minecraft\" (available: dns, http)", e.getMessage());
            assertEquals("type", e.settingKey().orElseThrow());
            assertTrue(pm.find("minecraft").isEmpty());
            assertTrue(pm.find("http").isPresent());

            assertEquals(List.of("endpoints[1] \"survival\": unknown type \"minecraft\" (available: dns, http)"),
                    pm.validateEndpoints(List.of(endpoint("web", "http"), endpoint("survival", "minecraft"))));
        }
    }

    @Test
    void validateEndpointsRunsPluginValidationAndRejectsUnreadKeys() throws Exception {
        Path plugins = tmp.resolve("plugins");
        buildJar(plugins, "echo.jar", "EchoPlugin", "echo", "1");
        try (PluginManager pm = PluginManager.load(List.of(), plugins, APP)) {
            JsonObject ok = new JsonObject();
            ok.addProperty("value", "x");
            JsonObject typo = ok.deepCopy();
            typo.addProperty("valeu", "y");
            List<String> errors = pm.validateEndpoints(List.of(
                    endpoint("good", "echo", ok), endpoint("missing", "echo", new JsonObject()),
                    endpoint("typo", "echo", typo)));
            assertEquals(List.of(
                    "endpoints[1] \"missing\": missing required setting \"value\"",
                    "endpoints[2] \"typo\": unknown setting \"valeu\""), errors);
        }
    }

    @Test
    void unknownTypeWithNoPluginsAtAll() throws Exception {
        try (PluginManager pm = PluginManager.load(List.of(), null, APP)) {
            ConfigException e = assertThrows(ConfigException.class, () -> pm.require("http"));
            assertEquals("unknown type \"http\" (available: none)", e.getMessage());
        }
    }

    @Test
    void defaultLoadFindsNoTestServicesAndAcceptsAMissingDir() throws Exception {
        try (PluginManager pm = PluginManager.load(tmp.resolve("none"))) {
            assertFalse(pm.availableTypes().contains("echo"));
        }
    }
}
