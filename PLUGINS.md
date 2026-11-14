# Writing a HealthChecker plugin

HTTP checks are built in. Every other check type is a plugin: a jar dropped into the
`plugins/` directory (`plugins.dir` in `config.json`) that provides one or more
`CheckPlugin` implementations. The built-in `http` type uses this same API.

A complete, real plugin is [MinecraftChecker](https://github.com/WesternIcelander/MinecraftChecker)
(the `minecraft` type). Read it alongside this document.

## Contents

- [Project setup](#project-setup)
- [The API](#the-api)
- [Settings: `settings()` and `SettingSpec`](#settings-settings-and-settingspec)
- [Reading and validating settings in `create()`](#reading-and-validating-settings-in-create)
- [Targets: `declareTarget`](#targets-declaretarget)
- [Results: messages and details](#results-messages-and-details)
- [Threading, timeouts and lifecycle](#threading-timeouts-and-lifecycle)
- [Registering the plugin: the services file](#registering-the-plugin-the-services-file)
- [Gson](#gson)
- [Minimal example](#minimal-example)
- [Testing](#testing)
- [Checklist](#checklist)

## Project setup

A plugin is a standalone Maven project. It compiles against `HealthChecker-API` and,
optionally, Gson, both with `provided` scope: the app supplies them at runtime, so they
must not be bundled into the plugin jar.

Install the API into your local Maven repository first:

```sh
cd HealthChecker
mvn install
```

Then, in the plugin's `pom.xml`:

```xml
<properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
</properties>

<dependencies>
    <dependency>
        <groupId>io.siggi</groupId>
        <artifactId>HealthChecker-API</artifactId>
        <version>1.0-SNAPSHOT</version>
        <scope>provided</scope>
    </dependency>
    <!-- Only if you use Gson. Must be the app's version, see "Gson" below. -->
    <dependency>
        <groupId>com.google.code.gson</groupId>
        <artifactId>gson</artifactId>
        <version>2.13.2</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

Java 21 is required. `mvn package` produces the plugin jar; copy it into `plugins/` and
restart HealthChecker. At startup each loaded plugin is logged with its type, class and
jar.

Other libraries are allowed, but you have to bundle them yourself (e.g. with the shade
plugin, relocated so they can't clash with another plugin's copy). Each plugin jar gets
its own class loader whose parent is the app's class loader, so classes the app already
has (the API, Gson, the JDK) always come from the app.

Plugins are trusted code running in the HealthChecker JVM with its full permissions.

## The API

Everything lives in the package `io.siggi.healthchecker.api` (artifact
`HealthChecker-API`, no dependencies):

| Type | Role |
|------|------|
| `CheckPlugin` | One check type. Describes its settings and builds a `Checker` per endpoint. Found via `ServiceLoader`. |
| `Checker` | One per endpoint. Runs a single check and returns a `CheckResult`. `AutoCloseable`. |
| `CheckResult` | Pass/fail, a short message and optional details. |
| `EndpointConfig` | Read-only view of one endpoint's type-specific settings, plus `declareTarget`. |
| `SettingSpec` | Describes one setting so the dashboard can render a form field for it. |
| `ConfigException` | An invalid setting, optionally naming the setting key. |

```java
public interface CheckPlugin {
    String type();                                  // config "type", e.g. "minecraft"
    default int apiVersion() { return 1; }          // highest API version this plugin needs
    default String displayName() { return type(); } // shown in the dashboard's type dropdown
    List<SettingSpec> settings();                   // for the dashboard's endpoint form
    Checker create(EndpointConfig config) throws ConfigException;
}

public interface Checker extends AutoCloseable {
    CheckResult check(Duration timeout) throws Exception;
    @Override default void close() throws Exception {}
}
```

- `type()` must match `[a-z0-9-]+` and be unique across all plugins and built-ins
  (`http` is taken). A duplicate type is a startup error naming both jars.
- `apiVersion()`: the current API version is **1**. Leave the default unless a later API
  version adds something you use; the app refuses to load a plugin that needs a newer
  version than it supports.
- The plugin class needs a public no-argument constructor (a `ServiceLoader`
  requirement). One instance is created per app run and shared by all endpoints of that
  type.

## Settings: `settings()` and `SettingSpec`

An endpoint is a flat JSON object. The app owns the common keys (`id`, `name`, `type`,
`category`, `visibility`, `notify`, `reminders`, `intervalSeconds`, `timeoutMillis`,
`failureThreshold`, …); every other key belongs to the plugin:

```json
{ "id": "survival", "name": "Survival", "type": "minecraft",
  "host": "mc.example.com", "minOnline": 1,
  "visibility": "public-status" }
```

Here `host` and `minOnline` are the plugin's settings. The plugin never sees the common
keys.

`settings()` describes the plugin's settings for the dashboard's endpoint editor. It
doesn't validate anything; that happens in `create()`. Build each one with a factory and
the copy-returning modifiers:

```java
@Override
public List<SettingSpec> settings() {
    return List.of(
            SettingSpec.string("host", "Host").asRequired()
                    .help("Host name or IP address"),
            SettingSpec.integer("port", "Port").asRequired()
                    .help("1-65535"),
            SettingSpec.bool("tls", "Use TLS").defaultValue(false),
            SettingSpec.stringList("expect", "Expected lines")
                    .help("One per line"),
            SettingSpec.choice("mode", "Mode", "fast", "thorough").defaultValue("fast"));
}
```

| Factory | `Kind` | JSON value | Form field | Read with |
|---------|--------|------------|------------|-----------|
| `string(key, label)` | `STRING` | string | text input | `getString` / `optString` |
| `integer(key, label)` | `INTEGER` | whole number | numeric input | `getInt` / `optInt` |
| `bool(key, label)` | `BOOLEAN` | `true` / `false` | not set / yes / no | `optBoolean` |
| `stringList(key, label)` | `STRING_LIST` | array of strings | textarea, one per line | `optStringList` |
| `choice(key, label, choices…)` | `CHOICE` | one of the choices | dropdown | `getString` / `optString` |

Modifiers (each returns a copy):

- `asRequired()` marks the field as required in the form. (It isn't called `required()`
  because that's the record's accessor.)
- `defaultValue(v)` is shown as the default in the form. It must fit the kind: `String`,
  `Integer`, `Boolean`, `List<String>`, or one of the choices for `CHOICE`.
- `help(text)` is a short help text shown under the field.

Things to know:

- **Defaults are display-only.** The app doesn't fill them in: an empty form field is
  left out of the stored endpoint, exactly like a key missing from `config.json`. Apply
  the same default in `create()` (e.g. `config.optBoolean("tls", false)`).
- A spec whose key is a common endpoint key, or a duplicate key, is ignored.
- If `settings()` throws, the type is still usable from `config.json` but the dashboard
  shows no fields for it (and logs a warning).
- `settings()` should be cheap and return the same list every time.

## Reading and validating settings in `create()`

`create(EndpointConfig)` validates one endpoint's settings and returns its `Checker`. It
is called:

- at startup, to validate every endpoint (that checker is closed again right away) and
  to build the checkers that actually run;
- whenever an endpoint is created or edited in the dashboard.

So `create()` must be **quick and side-effect free**: validate, declare targets, build
the checker. Don't start threads, open connections or contact the target in `create()`
or in the `Checker` constructor; do that lazily in `check()`. (MinecraftChecker makes
one exception, a time-limited SRV lookup to declare the SRV targets, and treats a failed
lookup as "nothing to declare", never as a config error.)

`EndpointConfig` getters:

| Method | Absent key | Wrong type |
|--------|------------|------------|
| `getString(key)` | `ConfigException` | `ConfigException` |
| `optString(key)` | `Optional.empty()` | `ConfigException` |
| `getInt(key)` | `ConfigException` | `ConfigException` |
| `optInt(key)` | `OptionalInt.empty()` | `ConfigException` |
| `optBoolean(key, def)` | `def` | `ConfigException` |
| `optStringList(key)` | empty list | `ConfigException` |

Also `id()`, `name()` (the endpoint's id and display name) and `keys()` (every
type-specific key present).

- JSON `null` counts as absent.
- Types are strict: `"5"` is not an integer and `"true"` is not a boolean. Integers must
  be whole and fit in an `int` (`1e3` is fine, `1.5` isn't).
- The exceptions the getters throw already carry the setting key and a readable
  message; just let them propagate.
- **Unknown keys are rejected.** After `create()` returns, any key that no getter asked
  for is reported as "unknown setting(s) …", which catches typos. Calling a getter marks
  its key as read even when the value is absent, so read every setting you support, even
  ones you only need in some cases. `keys()` doesn't mark anything.
- For your own validation errors, throw `new ConfigException(key, message)` so the
  dashboard shows the error next to that field, or `new ConfigException(message)` for an
  error not tied to one field. Don't put secrets in messages; configuration errors are
  shown to administrators only, but they do end up in logs.
- Values may come from `${env:…}` substitution in `config.json`; the plugin sees the
  substituted value.

## Targets: `declareTarget`

The dashboard is public, and **endpoint targets (URLs, hosts, IP addresses, ports) must
never be shown to anonymous visitors.** Call `config.declareTarget(label, value)` in
`create()` for every value that identifies the target:

```java
config.declareTarget("host", host);
config.declareTarget("port", Integer.toString(port));
```

Declared targets are:

- shown to logged-in users who may see private details, labelled with `label`;
- used as the public **leak filter**: before a check result is shown to someone who may
  not see targets, every message, detail key and detail value is compared
  (case-insensitively) with every declared value. A match hides that entry entirely.

The leak filter is a safety net, not a licence: write messages that never contain the
target in the first place (see below). Declare targets that are derived from settings
too, such as resolved names (MinecraftChecker declares the SRV target host and port),
but only what's known when `create()` runs; values discovered later aren't declared, so
keep them out of messages and details.

## Results: messages and details

```java
return CheckResult.ok("12 ms handshake")
        .with("version", "1.21.4")
        .with("playersOnline", 3);

return CheckResult.fail("connection refused");
```

- `message`: a short human-readable sentence. `null` becomes `""`.
- `details`: extra values, kept in insertion order. Values must be `String`, `Number` or
  `Boolean` (anything else throws `IllegalArgumentException`). `with(key, value)` returns
  a copy with one detail added or replaced.
- Depending on the endpoint's `visibility`, message and details **may be shown publicly**.
  Never include the target. In particular, **don't copy exception messages** into
  results: JDK exceptions routinely name the host or address
  (`UnknownHostException: mc.example.com`, `Connection refused: /10.0.0.9:25565`). Map
  them to generic texts instead: "host not found", "connection refused",
  "connection failed", "timeout after 5000 ms", "TLS error", "invalid response".
- Latency is measured by the app around `check()`; don't report it as a detail unless you
  measure something more specific.
- A pass is `success = true`. Whether a failure makes the endpoint DOWN is decided by the
  app (`failureThreshold`, maintenance windows); the plugin just reports each check.

Throwing from `check()` also counts as a failure. The app records it as
"check failed (ExceptionClassName)" and logs the full exception, so throwing is fine for
unexpected errors; return `CheckResult.fail(...)` with a clear message for expected ones.
Returning `null` is recorded as "check returned no result".

## Threading, timeouts and lifecycle

- **One checker per endpoint, one check at a time.** `check()` is never called
  concurrently on the same `Checker`, but it may be called from a different thread each
  time. Fields used only by the checker need no locking (successive checks are ordered by
  the app); use `volatile` or locks if `close()` touches the same state.
- **Different checkers run in parallel**, and all of a type's endpoints share the one
  `CheckPlugin` instance. Anything the plugin instance holds (clients, caches, random
  sources) must be thread-safe.
- Each `check()` runs on its own **virtual thread**. Blocking I/O is fine; avoid
  `synchronized` blocks around long blocking calls (they pin the carrier thread), and
  don't block forever.
- **Honour the timeout.** `check(Duration timeout)` receives the endpoint's
  `timeoutMillis`. Apply it to everything that can block: connect timeouts, socket read
  timeouts (`Socket.setSoTimeout`), `HttpClient` request timeouts, DNS lookups. Treat it as
  a budget for the whole check, not per step.
- The app enforces it too: after `timeout` plus a **1 second grace period** the check's
  thread is interrupted and the run is recorded as "timeout after N ms". Blocking socket
  I/O doesn't react to interrupts, which is why your own timeouts matter. If a check is
  still stuck when the next one is due, that run is skipped and counted as a failure
  ("skipped, previous check still stuck").
- If you catch `InterruptedException`, restore the interrupt flag
  (`Thread.currentThread().interrupt()`) and return or throw promptly.
- Any `Throwable` from `check()` becomes a failed result and never stops the scheduler,
  but don't rely on that for normal operation.
- **`close()`** is called when the endpoint is removed or replaced (an edit that changes
  its type, settings, interval, timeout or failure threshold builds a new checker), on
  shutdown, and right after a validation-only `create()`. Release sockets, clients and
  threads there. When an endpoint is replaced while a check is running, that check is
  interrupted and `close()` runs when it returns, or after the grace period at the latest,
  so `close()` can overlap a still-running `check()`. Make `close()` idempotent and quick.
- The first check runs after a random delay of up to `min(interval, 10 s)`, then at a
  fixed rate of `intervalSeconds`.

## Registering the plugin: the services file

Plugins are found with `java.util.ServiceLoader`. List your `CheckPlugin`
implementation(s), fully qualified, one per line, in:

```
src/main/resources/META-INF/services/io.siggi.healthchecker.api.CheckPlugin
```

```
com.example.tcpcheck.TcpCheckPlugin
```

A jar may provide several types. A jar without any `CheckPlugin` in its services file is
loaded with a warning and contributes nothing. Jars load in file-name order; loading
problems (duplicate type, too-new API version, invalid type name, broken services file)
are collected and reported together at startup.

If a plugin jar is removed, endpoints of its type defined in `config.json` stop startup
with "unknown type" (listing the available types); dashboard-defined endpoints are
loaded as disabled instead.

## Gson

Gson is part of the plugin contract. The app ships **Gson 2.13.2**, not relocated
(package `com.google.gson`), so plugins can use it with `provided` scope instead of
bundling their own copy. MinecraftChecker parses the server's JSON status this way.

- Depend on exactly the version above, `provided`. Don't shade Gson into your jar: the
  app's class loader is the parent, so its copy wins anyway.
- If a future HealthChecker changes the Gson version, this document changes with it.
- Gson is for the plugin's own use (e.g. parsing a protocol response). The API itself has
  no Gson types; settings come through `EndpointConfig`.

## Minimal example

A `tcp` check that passes when a TCP connection to `host:port` succeeds.

`pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>TcpCheck</artifactId>
    <version>1.0</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>io.siggi</groupId>
            <artifactId>HealthChecker-API</artifactId>
            <version>1.0-SNAPSHOT</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>
</project>
```

`src/main/java/com/example/tcpcheck/TcpCheckPlugin.java`:

```java
package com.example.tcpcheck;

import io.siggi.healthchecker.api.CheckPlugin;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.api.Checker;
import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.api.EndpointConfig;
import io.siggi.healthchecker.api.SettingSpec;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;

public final class TcpCheckPlugin implements CheckPlugin {

    @Override
    public String type() {
        return "tcp";
    }

    @Override
    public String displayName() {
        return "TCP port";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.string("host", "Host").asRequired().help("Host name or IP address"),
                SettingSpec.integer("port", "Port").asRequired().help("1-65535"));
    }

    @Override
    public Checker create(EndpointConfig config) throws ConfigException {
        String host = config.getString("host").strip();
        if (host.isEmpty()) {
            throw new ConfigException("host", "host must not be empty");
        }
        int port = config.getInt("port");
        if (port < 1 || port > 65535) {
            throw new ConfigException("port", "port must be 1-65535, got " + port);
        }
        config.declareTarget("host", host);
        config.declareTarget("port", Integer.toString(port));
        return new TcpChecker(host, port);
    }

    private record TcpChecker(String host, int port) implements Checker {
        @Override
        public CheckResult check(Duration timeout) {
            int millis = (int) Math.min(Integer.MAX_VALUE, Math.max(1, timeout.toMillis()));
            try (Socket socket = new Socket()) {
                // Resolving here (not in create()) picks up DNS changes; the message never names the host.
                InetSocketAddress address = new InetSocketAddress(host, port);
                if (address.isUnresolved()) {
                    return CheckResult.fail("host not found");
                }
                socket.connect(address, millis);
                return CheckResult.ok("connected");
            } catch (SocketTimeoutException e) {
                return CheckResult.fail("timeout after " + millis + " ms");
            } catch (UnknownHostException e) {
                return CheckResult.fail("host not found");
            } catch (ConnectException e) {
                return CheckResult.fail("connection refused");
            } catch (IOException e) {
                return CheckResult.fail("connection failed");
            }
        }
    }
}
```

`src/main/resources/META-INF/services/io.siggi.healthchecker.api.CheckPlugin`:

```
com.example.tcpcheck.TcpCheckPlugin
```

Build with `mvn package`, copy `target/TcpCheck-1.0.jar` into `plugins/`, restart, and
use it:

```json
{ "id": "db", "name": "Database", "type": "tcp", "host": "10.0.0.7", "port": 5432 }
```

(Name resolution in `new InetSocketAddress(host, port)` isn't bounded by the timeout; a
production plugin should resolve with its own time limit, as MinecraftChecker does for
SRV lookups.)

## Testing

- Unit-test `create()` against a small in-memory `EndpointConfig` (missing and wrongly
  typed settings, range checks, declared targets) and `check()` against a fake server on
  an ephemeral port (`new ServerSocket(0)`) in the test itself.
- Don't contact real services in tests. Put DNS and other lookups behind an interface you
  can replace with a fake (MinecraftChecker's `SrvResolver`).
- Test that failure messages don't contain the configured host, address or port.
- For an end-to-end check, run HealthChecker with your jar in `plugins/` and a
  configuration pointing at a fake server; MinecraftChecker's `scripts/it-minecraft.sh`
  does exactly that.

## Checklist

- [ ] `type()` is lowercase (`[a-z0-9-]+`) and unique; public no-arg constructor.
- [ ] Services file lists the plugin class.
- [ ] `HealthChecker-API` (and Gson 2.13.2, if used) with `provided` scope; neither bundled.
- [ ] `settings()` describes every setting; defaults applied again in `create()`.
- [ ] `create()` reads every supported key, validates with `ConfigException(key, …)`,
      declares every target value, and has no side effects.
- [ ] Messages and details never contain the target or exception texts.
- [ ] `check()` honours the timeout everywhere it can block and reacts to interrupts.
- [ ] Plugin-level state is thread-safe; `close()` releases resources and is idempotent.
