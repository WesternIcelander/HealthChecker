# HealthChecker — Plan

A small, self-hosted Java daemon that periodically checks configured endpoints,
records their uptime and downtime history, and shows it on a public status
dashboard.

- **HTTP** endpoints are checked by the built-in `http` type: `GET /` with a maximum
  response code class.
- **Everything else** is checked by plugins. Each plugin is its own project and jar,
  dropped into `plugins/`. The first one is **MinecraftChecker**.
- **History**: every outage is recorded as a downtime event with start, end and
  duration. Uptime % is shown for the last 24 hours and the last 60 days.
- **Dashboard**: the main page lists all visible services, grouped into ordered
  categories, each with a 60-day bar (one segment per day). Each service has a page with an outage list and a calendar.
  It's public, but each endpoint chooses how much is visible without logging in.
- **Login**: built-in (username/password) and/or OIDC / OAuth 2.0. Logged-in users
  can rename events and add public or private notes to them.
- **Notifications** on state changes and repeat reminders go to email (via a
  SimpleMailer instance) and Discord webhooks, chosen per endpoint.

Built on Java 21, Maven, Gson, and SiggiHTTP for the web server.

## Repositories

| Repo | Path | Remote | Contents |
|------|------|--------|----------|
| HealthChecker | `./` | `github.com/WesternIcelander/HealthChecker` | parent pom, API module, app module |
| MinecraftChecker | `./plugin-projects/MinecraftChecker` | `github.com/WesternIcelander/MinecraftChecker` | the `minecraft` plugin |

`plugin-projects/` holds separate repos nested inside the HealthChecker folder. It's
excluded locally via `.git/info/exclude`, not `.gitignore`.

### Maven coordinates (group `io.siggi`)

| Directory | artifactId | Packaging | Notes |
|-----------|------------|-----------|-------|
| `./` | `HealthChecker-Parent` | pom | parent, dependency versions |
| `./api` | `HealthChecker-API` | jar | plugin API, no dependencies |
| `./app` | `HealthChecker` | jar | the daemon, shaded and runnable |
| `MinecraftChecker` | `MinecraftChecker` | jar | plugin |

Plugins depend on `HealthChecker-API` and Gson with `<scope>provided</scope>`.
Both come from the local Maven repo (`mvn install` in HealthChecker); nothing is
deployed to repo.siggi.io for now.

## Goals

1. Endpoints are defined in a config file; no code changes needed to add one.
2. Every endpoint has a `type`. `http` is built in; every other type is a plugin jar.
3. Plugins compile against `HealthChecker-API` (plus Gson if they want it) only.
4. Checks run on an interval with a timeout, concurrently. A slow or buggy check
   (including a plugin) never delays or breaks the others.
5. Per-endpoint state (UP / DOWN / UNKNOWN) with flap protection.
6. Persistent history: downtime events and monitored time, surviving restarts.
   Uptime % over 24 h and 60 days.
7. Public dashboard with 60-day bars, per-service list and calendar views, and
   per-endpoint visibility.
8. Built-in login and OIDC/OAuth login. Logged-in users can rename events and
   add public or private notes.
9. Notifications (state changes plus configurable reminders) via SimpleMailer email and
   Discord webhooks, routed per endpoint.
10. Single runnable jar: `java -jar HealthChecker.jar [config.json]`.

### Non-goals (v1)

- Hot-loading/unloading plugins, or reloading `config.json` while running (restart to
  apply). Endpoints and categories managed in the dashboard apply immediately.
- Sandboxing plugins: they're trusted code in the same JVM.
- User management UI, self-registration, password reset (users live in config).
- Per-endpoint access: roles grant permissions across all endpoints, not per endpoint.
- Deleting recorded events, or manually creating past ones (scheduled maintenance
  covers planned future downtime).
- Clustering; per-check metrics graphs (latency charts).

## Plugin API (`io.siggi.healthchecker.api`, artifact `HealthChecker-API`)

```java
/** Discovered via ServiceLoader (META-INF/services/io.siggi.healthchecker.api.CheckPlugin). */
public interface CheckPlugin {
    /** Config "type" value this plugin handles, e.g. "minecraft". Lowercase, unique. */
    String type();

    /** Highest API version this plugin needs. The app refuses plugins newer than itself. */
    default int apiVersion() { return 1; }

    /** Human-readable name for the dashboard's type dropdown, e.g. "Minecraft (Java Edition)". */
    default String displayName() { return type(); }

    /** Describes the type-specific settings, so the dashboard can render a form for them. */
    List<SettingSpec> settings();

    /**
     * Validate this endpoint's type-specific settings and build a checker. Called at
     * startup, and whenever an endpoint is created or edited in the dashboard.
     */
    Checker create(EndpointConfig config) throws ConfigException;
}

/** One type-specific setting, for form rendering. Validation still happens in create(). */
public record SettingSpec(String key, String label, Kind kind, boolean required,
                          Object defaultValue, String help, List<String> choices) {
    public enum Kind { STRING, INTEGER, BOOLEAN, STRING_LIST, CHOICE }
    // builder-style factories: SettingSpec.string("host", "Host").asRequired().help("…") …
}

/** One per endpoint. Called from one thread at a time, never concurrently. */
public interface Checker extends AutoCloseable {
    /** One check. Should honour the timeout; the app enforces it too. Throwing = failure. */
    CheckResult check(Duration timeout) throws Exception;
    @Override default void close() throws Exception {}
}

public record CheckResult(boolean success, String message, Map<String, Object> details) {
    public static CheckResult ok(String message) { ... }
    public static CheckResult fail(String message) { ... }
    public CheckResult with(String key, Object value) { ... }   // add a detail
}

/** Read-only view of one endpoint's type-specific settings. */
public interface EndpointConfig {
    String id();
    String name();
    String getString(String key);  Optional<String> optString(String key);
    int getInt(String key);        OptionalInt optInt(String key);
    boolean optBoolean(String key, boolean def);
    List<String> optStringList(String key);
    Set<String> keys();
    /** Values that identify the target (URL, host, …). Never shown publicly. */
    void declareTarget(String label, String value);
}

/** Can carry a per-setting key, so the dashboard shows the error next to that field. */
public class ConfigException extends Exception {
    public ConfigException(String message) { ... }
    public ConfigException(String settingKey, String message) { ... }
}
```

- Common settings (`id`, `name`, `type`, `visibility`, `notify`, `reminders`,
  `intervalSeconds`, `timeoutMillis`, `failureThreshold`) are handled by the app.
  Plugins only see their own keys. Keys nobody reads are rejected, which catches typos.
- Plugins call `declareTarget` for every target value (e.g. `host`, `port`). The app
  shows these to logged-in users and uses them for the public leak filter.
- `details` are JSON-friendly values (strings, numbers, booleans).
- `message` and `details` may be shown publicly, so they must never contain the
  target. As a safety net, the app hides any entry containing a declared target value
  from anonymous viewers entirely.
- Latency is measured by the app around `check()`.
- **Gson** is on the app classpath and not relocated, so plugins may use it with
  `provided` scope. The app's Gson version is part of the plugin contract and is
  documented in `PLUGINS.md`.

## Plugin loading

1. Built-ins (`http`) via `ServiceLoader` on the app classpath.
2. Each `*.jar` in `plugins.dir` (default `./plugins`): its own `URLClassLoader`, with
   the app class loader as parent (shares the API and Gson), then
   `ServiceLoader.load(CheckPlugin.class, loader)`.
3. A duplicate `type` is a startup error naming both jars. An unknown `type` on an
   endpoint is a startup error listing the available types. A plugin that needs a
   newer API version is a startup error.
4. Each loaded plugin is logged: type, class, jar.

Runtime safety (enforced by the app):
- Each `check()` runs on a virtual thread. After `timeout` plus a grace period it's
  interrupted and recorded as a timeout failure.
- If the previous check is still stuck, the next run is skipped and counted as a failure.
- Any `Throwable` becomes a failed result and never kills the scheduler.
- `Checker.close()` is called on shutdown.

## Check types

### `http` (built in)

Sends `GET {url}/`. Passes when a response arrives and `statusCode / 100 <= maxClass`,
where `maxClass` ∈ {2, 3, 4, 5}:

| `maxClass` | Passes on | Fails on |
|-----------:|-----------|----------|
| 2 | 2xx | 3xx, 4xx, 5xx |
| 3 | 2xx, 3xx | 4xx, 5xx |
| 4 | 2xx–4xx | 5xx |
| 5 | any HTTP response | no response only |

- No response always fails: connection refused, DNS failure, TLS error, timeout,
  malformed response. So `5` means "the server answers HTTP at all".
- Redirects are not followed, so a 3xx is observable.
- `url`: absolute `http`/`https` with a host; path empty or `/`; no query or fragment.
- `maxClass` is required, with no default; values outside 2–5 are rejected.
- Details: `statusCode`. Declared targets: `url`, host.
- Implemented as a `CheckPlugin`, so the built-in type uses the same API as plugins.

### `minecraft` (MinecraftChecker plugin, separate repo and jar)

Minecraft Java Edition "Server List Ping" over TCP: handshake (next state = status),
status request, read the JSON status response, then an optional ping/pong.

- Settings: `host` (required), `port` (optional), optional `minOnline`, optional
  `expectVersionContains`.
- **SRV lookup**: when `port` is not set, look up `_minecraft._tcp.{host}` via JNDI DNS
  (`com.sun.jndi.dns`, JDK built-in). If a record exists, connect to its target and port
  (lowest priority first, weighted choice within a priority, next record on connect
  failure). If there's no record, use `host:25565`. When `port` is set, SRV is skipped,
  as in the vanilla client. `srv: false` disables SRV lookup.
- The handshake's server address field is the original `host`, not the SRV target, as
  the vanilla client sends it (matters for proxies such as BungeeCord/Velocity forced hosts).
- Passes when a valid status response is parsed and the optional conditions hold.
- Details: `version`, `protocol`, `playersOnline`, `playersMax`, `motd` (plain text,
  formatting codes and chat components flattened).
- Declared targets: `host`, the resolved SRV target and port.
- VarInt framing on `DataInputStream`/`DataOutputStream`, with a cap on packet size.
  JSON is parsed with the app's Gson (`provided` scope).

## History & uptime

### Endpoint identity

Every endpoint has a required, stable `id` (`[a-z0-9-]+`, unique). History is stored
under the `id`, so `name` can change freely. The `id` appears in public URLs
(`/service/{id}`), so don't put a hostname in it.

### What is recorded

- **Coverage**: periods when an endpoint was actually being monitored (app running,
  endpoint configured, checks completing). A heartbeat updates the current period's end
  every minute. A gap (app down, endpoint removed from config, scheduler stalled) is
  "no data", which counts as neither up nor down.
- **Downtime events**: one event per outage, while the endpoint was DOWN.
  - `start` = time of the first failed check in the streak that led to DOWN (so with
    `failureThreshold: 3` the outage starts at the first of the 3 failures, not the third).
  - `end` = time of the first successful check that brings it back UP; empty while ongoing.
  - `duration` = `end - start` (ongoing: until now).
  - If the app stops while an endpoint is DOWN, the event is closed at the last observed
    failed check and marked `endedUnobserved` on the next startup. If it's still down
    after restart, a new event starts.
  - Fields: `id` (UUID), `endpointId`, `kind` (`outage` | `maintenance`), `start`,
    `end`, `title` (null = default), `firstFailureMessage`, `lastFailureMessage`,
    `notes[]`, `endedUnobserved`, `endedByConfigChange`, `maintenanceId` (if any).
  - Downtime inside a scheduled maintenance window is recorded with
    `kind: maintenance` and linked to that window. An outage that runs past the end of
    a window is split: the part inside is `maintenance`, and the rest is a normal
    `outage` starting at the window's end. See
    [Scheduled maintenance](#scheduled-maintenance).

### Uptime %

`uptime(window) = 1 − (unplanned downtime ∩ window) / (coverage ∩ window)`, computed
exactly from events and coverage. Downtime of kind `maintenance` doesn't count as
downtime by default (`maintenance.countsAsDowntime: false` in config). Set it to `true` to count
it. Shown as:

- **24 h**: rolling, the last 24 hours up to now.
- **60 days**: rolling, the last 60 × 24 hours up to now.
- **Per day** (bars and calendar): one calendar day in the configured `timezone`.

If coverage in a window is zero, uptime is "no data" (shown as `—`, not 100 %).
Displayed with up to two decimals, never rounded up to 100 % if any downtime exists
(99.995 % shows as 99.99 %).

### Storage

Plain files under `dataDir` (default `data/`), with atomic writes (temp file + rename)
and no database:

```
data/
  endpoints/{id}/coverage.json         list of {start, end} periods
  endpoints/{id}/events/{yyyy}.jsonl   one event per line; rewritten on edit
  maintenance.json                     scheduled maintenance windows (versioned)
  endpoints.json                       endpoints and categories created in the dashboard
  layout.json                          dashboard layout edited by logged-in users
  audit.jsonl                          who changed what, append-only
  sessions/                            login sessions
  notifications/state.json             pending reminders, so they survive restarts
```

All of an endpoint's events are loaded into memory at startup and kept in memory;
outages are rare, so this stays small. Per-day summaries are computed on demand and
cached, then invalidated when an event changes or the day is today.

### Timezone

`timezone` in config (IANA name, default the system zone) decides where days start and
end, for bars, the calendar and per-day uptime. All timestamps are stored in UTC.

## Events: titles and notes

- **Title**: default is shown as `"{endpoint name} outage"`. A logged-in user can rename
  it. An empty rename resets it to the default. The title follows the endpoint's
  visibility (shown publicly for `public` and `public-status` endpoints).
- **Notes**: zero or more per event. Each has `id`, `author` (display name from login),
  `createdAt`, `editedAt`, `text` (plain text, newlines kept, HTML-escaped on output,
  max 10 000 chars) and `visibility`: `public` or `private`.
  - Logged-in users can add, edit, delete and toggle any note.
  - Anonymous visitors see only `public` notes, and only on events of `public` or
    `public-status` endpoints.
- All edits are POST forms with CSRF tokens. Each change is written to the events file
  before responding.

## Scheduled maintenance

Logged-in users can announce planned downtime ahead of time: a scheduled reboot,
maintenance, an upgrade, and so on.

### Maintenance windows

- Fields:
  - `id`
  - `title` (e.g. "Weekly reboot")
  - optional `description`
  - `start`, `end` (planned; entered in the configured timezone)
  - `endpoints`: one or more endpoint ids, or whole categories, which expand to the
    category's members at that moment and later
  - `notes[]` (public/private, as on events)
  - `notifyChannels` (optional)
  - `recurrence` (optional)
  - creator and timestamps
- **Recurrence** (for things like a weekly reboot): `none`, `daily`, `weekly` (chosen
  weekdays) or `monthly` (day of month, or "first Sunday" style), with optional `until`.
  Each occurrence keeps the same time of day in the configured timezone, across DST
  changes. Single occurrences can be skipped or edited without changing the series.
- Created from "Schedule maintenance" on the main page and on service pages. Also
  editable and cancellable there and on its own page `/maintenance/{id}`.
- **States**: `scheduled` → `in progress` (at `start`) → `completed` (at `end`), or
  `cancelled`.
  - "End now" finishes an in-progress window early.
  - "Extend" moves `end` later.
  - Editing `start` or `end` is only allowed while still in the future; past times are
    fixed.
- Times must be in the future when created; `end` > `start`; maximum length 7 days per
  occurrence (configurable).

### What happens during a window

- **Checks keep running.** The results are recorded and shown, so you can see whether
  the reboot actually happened and when the service came back.
- A DOWN during the window is recorded as a `maintenance` event, not an outage. By
  default it doesn't reduce uptime %.
- **Notifications** for DOWN and reminders on affected endpoints are suppressed during
  the window. Recovery inside the window is silent too.
- **If it's still down when the window ends**, it becomes a normal outage starting at
  the window's end: down notification and reminders as usual. The "End now" and
  "Extend" actions take effect immediately.
- **Announcements**: if `notifyChannels` is set, a message goes to those channels when
  the window is created, when it starts, and when it ends or is cancelled. Optionally a
  reminder goes out N hours before the start (`announceBeforeHours`). This is separate
  from endpoint `notify` routing, because who wants maintenance notices can differ from
  who gets alerts.

### Display

- **Main page**: an "Upcoming maintenance" box under the banner (next 7 days, plus
  anything in progress) with title, time range and affected services. Services in
  an active window show a blue "Maintenance" state instead of UP/DOWN.
- **Day bars and calendar**: days with maintenance, and no unplanned downtime, are
  **blue**. Unplanned downtime colours win. Future days with scheduled maintenance
  have a blue outline, so the calendar also shows what's coming. The tooltip/popover
  lists maintenance windows separately from outages.
- **Service page**: upcoming windows for this service at the top. In the list view,
  past maintenance appears among the events with a "Planned" badge, filterable
  (All / Outages / Maintenance).
- **Visibility**: a window is shown to anonymous visitors only on and for affected
  endpoints they can see. The affected-services list is filtered the same way, and a
  window affecting only invisible endpoints doesn't appear at all. Title and
  description follow that. Notes have their own public/private setting. Targets are
  never shown.

### Storage & audit

- Windows are stored in `data/maintenance.json`, versioned (409 on stale save) and written
  atomically. Past occurrences of a recurring series are materialised as their own
  records when they start, so later edits to the series don't rewrite history.
- Create, edit, cancel, end now, extend and notes are all written to the audit log.

## Managing endpoints and categories in the dashboard

Logged-in users can create, edit and delete endpoints and categories in the dashboard.
Changes take effect immediately, with no restart.

### Two sources

| Source | Where it's stored | Editable in the dashboard |
|--------|-------------------|---------------------------|
| Config file (`endpoints`, `categories` in `config.json`) | `config.json` | No: shown with a "config" badge and read-only fields. Layout (order/category) is still editable. |
| Dashboard | `data/endpoints.json` | Yes |

- Both sources are merged at startup. An id defined in both is a startup error naming
  the id, so neither silently wins.
- A config-defined endpoint has a "Copy to dashboard" action. It shows the config snippet
  to remove from `config.json`; after removal and a restart, the copy in
  `data/endpoints.json` takes over the same id and history. Without the removal, the
  startup error above applies.
- You can use either source alone: an empty config `endpoints` with everything in the
  dashboard is fine, and so is the reverse.

### Endpoint editor

- "Add service" on the main page (logged in) and "Edit" / "Delete" on each service page.
- Form fields:
  - **Common:** id (set at creation, then read-only, since history is stored under it),
    name, category, visibility, interval, timeout, failure threshold, `notify`
    (checkboxes of configured channels, "use defaults" or "none"), reminders.
  - **Type:** a dropdown of loaded types (built-in `http` plus plugins), using `displayName()`.
  - **Type-specific:** rendered from the plugin's `settings()`. Changing the type
    re-renders this part. Without JS, a two-step form: choose the type, then the settings.
- Each field that differs from `defaults` shows that it's overriding; blank means "use default".
- **Validation** runs through the same code as config loading, including the plugin's
  `create()`. Errors are shown next to their fields (via `ConfigException.settingKey`),
  and nothing is saved until everything is valid.
- **"Test now"**: runs one check with the form's current values without saving, and
  shows pass/fail, latency, message and details.
- **Save**: atomic write of `data/endpoints.json`, then the scheduler applies the change
  live:
  - New endpoint: starts checking (state UNKNOWN), and coverage begins.
  - Only presentation or routing changed (name, category, visibility, notify,
    reminders): applied in place; state, streaks and open events are kept.
  - Check changed (type, type settings, interval, timeout, threshold): the old checker
    is closed and a new one created. State resets to UNKNOWN. An open downtime event is
    closed at the last failed check and marked `endedByConfigChange`, and pending
    reminders for it are cancelled.
- **Delete**: asks for confirmation (type the id). The checker stops and coverage ends.
  History is kept on disk by default; "also delete history" is a separate checkbox.
  A deleted id can't be reused while its history still exists, to avoid mixing two
  services' history.
- **Concurrency**: `data/endpoints.json` has a version number. A save based on an older
  version gets `409`, and the editor shows what changed and lets you retry.

### Category editor

- In the layout editor: add, rename (name/description) and delete dashboard-defined
  categories. Config-defined categories are read-only, except for their position.
- Deleting a category moves its services to "Other" (after confirmation listing them).
- Category ids follow the same rules as endpoint ids and can't be changed after creation.

### Audit log

Every create, edit and delete of an endpoint or category, layout save, event rename,
note change and maintenance window change is appended to `data/audit.jsonl`: time, user, action, id, and before/after
values. Secrets are masked. Logged-in users can view it at `/admin/audit` (paged, newest
first).

### Security notes

- Logged-in users can make the server connect to arbitrary hosts and ports (via
  endpoints and "Test now"). That's inherent to the feature, so only trusted people
  should have accounts. The README says so.
- "Test now" is rate-limited per user.
- All forms are POST with CSRF tokens.

## Visibility

Each endpoint has `visibility` (default **`private`**, so nothing leaks by accident):

| | `public` | `public-status` | `private` |
|---|---|---|---|
| Listed on main page | yes | yes | no |
| Name, state | yes | yes | — |
| 24 h / 60 d uptime, 60-day bar | yes | yes | — |
| Service page: events (title, start, end, duration), calendar, day uptime | yes | yes | — |
| Public notes | yes | yes | — |
| Recent checks as pass/fail | yes | yes | — |
| Latency, check message, details, failure messages on events | yes (leak filter applied) | no | — |
| Target (URL, host, port) | **never** | **never** | **never** |

- Logged-in users with `view.private` see everything for every endpoint, including targets, private notes,
  visibility, and `notify`/`reminders` settings.
- A `private` endpoint's service page and API returns `404` to anonymous visitors, not
  `403`, so its existence isn't revealed.
- Filtering happens in one `ViewFilter` used by the HTML pages and the JSON API alike.
  It builds anonymous views by copying allowed fields (an allow-list), never by removing
  fields, so new fields are private by default.
- Leak filter: any `message`, `details` entry or failure message containing a declared
  target value is left out of the anonymous view entirely (no placeholder).
- The overall state and the `/api/status` HTTP code (200/503) come from the endpoints
  the requester can see.

## Dashboard

Server-rendered HTML with one small stylesheet and a little vanilla JS for popovers
and tooltips. No framework, and every page works without JS. Light and dark theme
via `prefers-color-scheme`. It auto-refreshes the main page every 60 s.

### Categories & ordering

Services are grouped into categories on the main page. Categories themselves (id,
name, description) are defined in the config file or created in the dashboard (see
[Managing endpoints and categories in the dashboard](#managing-endpoints-and-categories-in-the-dashboard)).
**Their order, the order of services
within each category, and which category each service is in can be edited on the
dashboard by any logged-in user.**

- `categories` is an array of `{ "id", "name", "description"? }`. Each endpoint has an
  optional `category`. Together these give the **default layout**: categories in array
  order, services in `endpoints` order.
- **Layout editing** (logged-in users, main page → "Edit layout"):
  - Drag and drop categories to reorder them. Drag services to reorder them within a
    category or move them to another category (including "Other").
  - Every item also has up/down buttons and a category dropdown, so it works by keyboard
    and without drag and drop.
  - The editor shows all services, including private ones, and marks each one's
    visibility.
  - "Save" sends the whole layout as one POST (JSON, CSRF token). "Cancel" discards.
    "Reset to config order" deletes the saved layout.
  - The layout carries a version number. If someone else saved in the meantime, the
    save is rejected with `409` and the editor offers to reload.
- The saved layout is stored in `data/layout.json` and **overrides the config defaults**:
  - Category order, plus the ordered service ids per category.
  - A service's category in the layout overrides its config `category`.
  - Categories or endpoints added to the config later that aren't in the layout are
    appended at the end (of the category list, or of their config category).
  - Ids in the layout that no longer exist in the config are ignored and dropped on the
    next save.
  - The layout is global. Sites (below) filter it but keep its order.
- Endpoints without a category go into an implicit "Other" category, shown last by
  default; the layout can move "Other" like any category. If no categories exist at
  all, the main page is a flat list with no headings, and the editor only reorders
  services.
- A category is shown only if the viewer can see at least one service in it, so a
  category of only private services is invisible to anonymous visitors.
- Category heading: name, optional description, and a summary of the viewer's visible
  services in it ("All operational" / "1 of 4 down").
- Unknown category ids and duplicate category ids are config errors. A category with
  no endpoints is a warning, not an error.

### Sites: scoping by Host header

One HealthChecker can serve several status pages on different hostnames, each
limited to certain categories and/or services. For example, `status.example.com` could
show everything, while `mc-status.example.com` shows only the Minecraft category.

- `sites` is an array of `{ "hosts", "title"?, "publicBaseUrl"?, "categories"?, "endpoints"? }`.
  - `hosts`: hostnames matched against the request's `Host` header (case-insensitive,
    port ignored). `*.example.com` matches any subdomain. Behind a trusted proxy,
    `X-Forwarded-Host` is used instead.
  - `categories` / `endpoints`: what the site includes, as category ids and/or
    endpoint ids (union of both). Omit both to include everything.
  - `title`: overrides `dashboard.title` on this site.
  - `publicBaseUrl`: this site's external URL (used for links and OIDC callbacks on this site).
- Category order and service order stay as globally configured; a site only filters.
- The first matching site wins. A request matching no site uses the default site:
  everything, with the top-level `dashboard` and `server.publicBaseUrl`. Set
  `server.unknownHosts: "reject"` to return `404` instead, so only configured hostnames work.
- Scoping applies to everything on that host: main page, service pages, event pages
  and JSON API. An endpoint outside the site's scope returns `404` there, even for
  logged-in users. Overall status and the 200/503 code are computed within the scope.
- Visibility rules still apply on top of the site scope.
- **Scoping is presentation, not access control.** The `Host` header is chosen by the
  client, so anyone can request another site's view. Only `visibility` and login
  protect anything. The README will say this plainly.
- The `Host` header is never used to build URLs. Links, redirects and OIDC callbacks use
  the matched site's configured `publicBaseUrl` (or the server default), preventing
  host-header injection.
- **Login per site**: session cookies are per hostname, so a login on one site doesn't
  carry over to another. OIDC/OAuth callback URLs are per site
  (`{site publicBaseUrl}/auth/callback/{providerId}`), and each must be registered with
  the provider. A provider can be limited to some sites with `sites: [...]` (default: all).
- Notification links use the site whose scope includes the endpoint (first match),
  otherwise the default `publicBaseUrl`.

### Main page `/`

Category headings in configured order, each followed by one row per visible service
in configured order:

```
● Website                         24h 100%   60d 99.87%
  ▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮
  60 days ago                                                         today
```

- The bar is 60 small rounded rectangles (CSS flex boxes with a small gap, not text
  characters), oldest on the left, today on the right.
- Segment colour per day:
  - green: no downtime
  - yellow: downtime, uptime ≥ 99 %
  - orange: uptime 95 – 99 %
  - red: uptime < 95 %
  - blue: scheduled maintenance that day, with no unplanned downtime
  - grey: no data
  - The thresholds are configurable.
- Hovering or focusing a segment shows a tooltip: date, uptime %, total downtime,
  number of outages. Clicking it goes to that day on the service's calendar.
- The row header shows the current state dot, name, 24 h % and 60 d %. Clicking it opens
  the service page.
- Top of the page: overall status banner ("All systems operational" / "1 service down" /
  "Maintenance in progress"), then the "Upcoming maintenance" box when there is any.
- On narrow screens the 60 segments shrink; they still fit without scrolling down to
  about 320 px wide.

### Service page `/service/{id}`

Header: name, state, 24 h %, 60 d %, the 60-day bar, and a strip of recent checks as
pass/fail. Logged-in users also see target, type, latency, last message and details.

Tabs (plain links, `?view=list` / `?view=calendar`; **list is the default**):

**List view** (`?view=list&page=N`)
- Upcoming maintenance windows for this service first (if any).
- Then downtime events, newest first, 25 per page, with previous/next links. Maintenance
  events have a "Planned" badge; filter All / Outages / Maintenance.
- Columns: title, start, end (or "ongoing"), duration (e.g. `1h 12m`), note count.
- Clicking an event expands it (`<details>`) to show notes. Logged-in users also see
  failure messages, the rename form and note forms.
- Event permalink: `/service/{id}/event/{eventId}`.

**Calendar view** (`?view=calendar&page=N`)
- Page 0 shows the current month and the two previous months. Page N shows the 3 months
  ending 3 × N months before the current one. "Earlier" and "Later" links; no "Later"
  on page 0.
- Pages before the endpoint's first coverage still render, with all days grey.
- Each month is a 7-column grid of small square blocks (weeks start on Monday) with no
  text inside, coloured as in the bars. Future days are empty outlines, or blue
  outlines when maintenance is scheduled.
- Clicking a day opens a small popover: the date, that day's uptime %, total downtime,
  and the events overlapping that day (title, start/end times clipped to the day,
  duration, public notes or all notes when logged in). Each event links to its permalink.
- Without JS, a day is a link to `/service/{id}/day/{yyyy-mm-dd}`, which renders the same
  content as a page.
- `?day=yyyy-mm-dd` opens the calendar on the page containing that day with its
  popover open (used by the main-page bar).

### JSON API

| Path | Contents |
|------|----------|
| `GET /api/status` | all visible endpoints: state, 24 h / 60 d uptime, 60 daily summaries; 200/503 |
| `GET /api/service/{id}` | one endpoint: as above plus recent checks |
| `GET /api/service/{id}/events?page=N` | event list page |
| `GET /api/service/{id}/days?from=…&to=…` | per-day summaries (max 400 days per request) |
| `GET /api/service/{id}/event/{eventId}` | one event with its visible notes |
| `GET /api/maintenance?from=…&to=…` | visible maintenance windows (default: in progress + next 30 days) |
| `GET /api/maintenance/{id}` | one window with its visible notes and affected visible services |

Anonymous example (`Website` is `public`, `Survival` is `public-status`):

Endpoints are grouped by category, in configured order:

```json
{
  "overall": "UP",
  "viewer": "anonymous",
  "categories": [
    { "id": "web", "name": "Websites", "summary": { "up": 1, "down": 0 },
      "endpoints": [ "…ordered endpoint objects, as below…" ] }
  ],
  "endpoints": [
    { "id": "website", "name": "Website", "type": "http", "state": "UP",
      "since": "2026-11-13T09:00:00Z",
      "uptime": { "24h": 100.0, "60d": 99.87 },
      "days": [ { "date": "2026-09-15", "uptime": 100.0, "downtimeSeconds": 0, "events": 0 },
                "…60 entries…" ],
      "last": { "at": "2026-11-13T16:41:30Z", "latencyMs": 84, "success": true,
                "message": "status 200", "details": { "statusCode": 200 } } },
    { "id": "survival", "name": "Survival", "state": "UP",
      "uptime": { "24h": 100.0, "60d": 99.5 },
      "days": [ "…" ] }
  ]
}
```

(The flat `endpoints` array above is shown for field detail. In the real response, the
endpoint objects appear only inside `categories[].endpoints`.)

## Authentication

### Sessions

- SiggiHTTP's new sessions API. The app owns one `Sessions`, built with
  `new SessionsBuilder().setDirectory(data/sessions).setExpireTime(sessionDays)
  .setCookieMaxAge(sessionDays).setSameSite(SameSite.LAX).build()`, so logins survive
  restarts. Don't use the deprecated API (`Sessions.create`, `request.getSession()`,
  `request.resetSession()`, `HTTPServerBuilder.setSessions`).
- Access: `sessions.readSession(request)` for reads (no lock, never creates a session or
  sets a cookie, so anonymous visitors get no cookie); `sessions.openSession(request)` in
  try-with-resources only where the session is written, closed as soon as possible;
  `sessions.resetSession(request)` at login (new ID, against session fixation);
  `sessions.deleteSession(request)` at logout.
- Cookie: `Path=/; HttpOnly; SameSite=Lax`, plus `Secure` over HTTPS. CSRF tokens
  protect all state-changing POSTs regardless. If `request.isSecure()` doesn't reflect
  `X-Forwarded-Proto` from trusted proxies, work around it in HealthChecker (record it
  under Decisions); don't patch SiggiHTTP. Any other cookie HealthChecker sets
  is built with `CookieBakery`.
- Logout is a POST with a CSRF token.
- Configurable lifetime (default 7 days, sliding: idle timeout, and the cookie's
  `Max-Age` is refreshed on each read).
- `server.trustedProxies` controls which `X-Forwarded-For`/`-Proto` headers are trusted.

### Built-in login

- A user with a `password` can log in with their username and that password. The hash
  is PBKDF2-HMAC-SHA256 (JDK only), ≥ 600k iterations, random salt, stored as
  `pbkdf2-sha256$<iter>$<salt b64>$<hash b64>`.
- `java -jar HealthChecker.jar hash-password` reads a password from the console (no
  echo) and prints the hash.
- `/login` form with a CSRF token and constant-time comparison. A dummy hash is checked
  for unknown users, so timing doesn't reveal which usernames exist.
- Rate limiting per IP and per username (e.g. 10 failures / 15 min), returning 429.
- Can be disabled (`auth.local.enabled: false`) when only OIDC/OAuth is used.

### OIDC / OAuth 2.0 login

Any number of providers, each shown as a "Sign in with …" button.

- **`oidc`**: discovery via `{issuer}/.well-known/openid-configuration`. Authorization
  code flow with **PKCE (S256)**, `state` and `nonce`. The `id_token` is verified with
  **SimpleJWT** (`io.siggi:SimpleJWT:1.1`) against the provider's JWKS (cached, refreshed
  when an unknown `kid` appears). The app checks `iss`, `aud`, `exp`/`iat` (with clock
  skew), `nonce` and the algorithm allow-list (RS256/384/512, ES256/384/512, which SimpleJWT supports; never `none`, HS* or anything else).
- **`oauth2`** for providers that aren't OIDC (e.g. GitHub, Discord): `authorizeUrl`,
  `tokenUrl`, `userinfoUrl`, `scopes`, `idField`, `nameField`. Code flow, with PKCE where
  supported.
- **How a person is identified**, per provider, via `identifyBy`:
  - `oidc`: `sub` (default, stable and recommended), `email` (accepted only when
    `email_verified` is true), or any other claim name.
  - `oauth2`: the `idField` value from the userinfo response (e.g. GitHub's numeric `id`).
  - The resulting **identifier** is `{providerId}:{value}`, e.g. `google:110248495921238986420`
    or `github:123456`. That's what goes in a user's `identities` list (see
    [Users, roles & permissions](#users-roles--permissions)).
- **Unknown identity**: if the identifier doesn't match any user or rule, no session is
  created. Instead, a page shows:
  > You signed in with **Google** as *Jane Doe*, but this account doesn't have access yet.
  > Send this identifier to the administrator and ask them to allow it:
  > `google:110248495921238986420` [Copy]
  - It shows the provider's display name/email only to the person themselves, as a
    hint for which account they used, plus a "Sign in with a different account" link.
  - It never reveals anything about configured users, roles or the reason beyond "not
    allowed yet".
  - The attempt is logged and recorded in the audit log (provider, identifier, display
    name, time, IP), so the admin can verify the identifier they're sent. Logging is
    rate-limited per identifier to avoid log spam.
  - The same page is used when an identity matches but its user is `disabled`. The
    wording is then "access has been disabled".
- Callback URL: `{publicBaseUrl}/auth/callback/{providerId}`.
- Secrets can come from the environment: `"${env:NAME}"` works for every config string.

### Users, roles & permissions

Not every logged-in user can do everything. Each user has a **role**, and a role is a
set of **permissions**.

**Permissions**

| Permission | Allows |
|------------|--------|
| `view.private` | see `private` endpoints, targets, private notes, failure messages, details, `notify`/`reminders` settings, channel errors |
| `events.annotate` | rename events; add, edit and delete notes on events and maintenance windows |
| `maintenance.manage` | create, edit, cancel, end and extend maintenance windows |
| `layout.edit` | reorder categories and services, move services between categories |
| `endpoints.manage` | create, edit and delete endpoints and categories, "Test now", "Copy to dashboard" |
| `history.delete` | the "also delete history" option when deleting an endpoint |
| `notifications.test` | "Send test notification" |
| `audit.view` | `/admin/audit`, including rejected-login records |

**Built-in roles** (can't be redefined):

| Role | Permissions |
|------|-------------|
| `viewer` | `view.private` |
| `editor` | `viewer` + `events.annotate`, `maintenance.manage` |
| `admin` | all |

Custom roles in config, e.g. `"roles": { "ops": ["view.private", "maintenance.manage"] }`.
Unknown permission names are a config error. A role without `view.private` is allowed:
that user sees what anonymous visitors see, plus whatever their other permissions allow.

**Users**: one list covering all login methods:

```json
"users": [
  { "id": "siggi", "displayName": "Siggi", "role": "admin",
    "password": "pbkdf2-sha256$600000$...$...",
    "identities": ["google:110248495921238986420", "github:123456"] },
  { "id": "jane", "displayName": "Jane", "role": "editor",
    "identities": ["google:109876543210987654321"] },
  { "id": "old-intern", "role": "viewer", "identities": ["github:42"], "disabled": true }
]
```

- A person can log in by password (if `password` is set) or with any of their `identities`.
- An identifier may belong to only one user (config error otherwise).
- `disabled: true` blocks login and ends existing sessions on the next request.
- **Rules** (optional) grant access without listing each person, e.g.
  `{ "provider": "google", "emailDomain": "example.com", "role": "viewer" }`, or a
  `group` claim match. An explicit user entry always wins over a rule. Rules are checked
  in order; the first match gives the role.
- A role or user change in config applies after restart. Sessions store the user id, not
  the permissions, so they're looked up fresh on every request.

**Enforcement**

- Checked on the server for every page, form POST and API call. Hiding buttons in the UI
  is only convenience.
- Missing permission: `403` with a short message for logged-in users. Anonymous
  visitors are redirected to `/login`. For a `private` endpoint without `view.private`,
  it's `404`, as for anonymous visitors.
- The header shows the user's name and role.
- Throughout this plan, "logged-in users can …" means users whose role has the matching
  permission from the table above.

## Notifications

### Routing: which endpoints notify, and where

Channels are named, configured destinations: one email recipient list, one Discord
webhook, and so on. You can have several of the same type. Each endpoint picks any mix
with `notify`:

| Endpoint `notify` | Effect |
|-------------------|--------|
| omitted | uses `defaults.notify` |
| `["ops-email"]` | email only |
| `["discord"]` | Discord only |
| `["mc-discord", "ops-email"]` | those two |
| `false` (or `[]`) | never notifies; still checked, recorded and shown |

`defaults.notify` follows the same rules (`false` there makes notifications opt-in per
endpoint). Unknown channel names are a config error.

### What triggers a notification

- **Down**: the endpoint goes UP → DOWN or UNKNOWN → DOWN.
- **Reminders while still down**: `reminders` is a list of minutes after the outage
  started. Default `[30, 60, 120]` = reminders at 0:30, 1:00 and 2:00 into the outage,
  then no more. Set in `defaults.reminders` and overridable per endpoint; `[]` disables.
  Optional `reminderRepeatMinutes` (default off) keeps repeating at that interval
  after the last listed reminder.
- **Recovered**: DOWN → UP, including the total outage duration.
- UNKNOWN → UP at startup is silent.
- During a scheduled maintenance window, down, reminder and recovery notifications for
  affected endpoints are suppressed. Still down at the window's end means a normal down
  notification then. Maintenance announcements go to the window's own `notifyChannels`.
- Pending reminders are stored in `data/notifications/state.json`, so a restart
  doesn't lose or re-send them. Reminders already overdue after a restart are sent once
  (not one per missed slot).

### Delivery

- **Batching**: notifications to the same channel within a short window (default 30 s)
  are combined into one message, so an outage hitting 20 endpoints sends one email,
  not 20.
- Asynchronous with retry and backoff; never blocks checks. Failures are logged, and the
  last error per channel is shown to logged-in users.
- Messages include endpoint name, what happened (down / still down for 1h / recovered
  after 2h 14m), the time, the latest failure message, and a link to the event permalink.
- "Send test notification" per channel for logged-in users (POST + CSRF).

### `email` (via SimpleMailer)

SimpleMailer is a separate service with an HTTP API (`../SimpleMailer`), called over
HTTP. It is not a library dependency.
- Settings: `baseUrl`, `apiKey` (bearer), `from`, `to` (list), optional `requireTls`.
- `POST {baseUrl}/v1/messages` with `{id, from, to, subject, text, html}`. `id` is a UUID
  per notification, reused on retries, so SimpleMailer's idempotency prevents
  duplicates.
  - `202` / `200`: accepted.
  - `409`: logged as a bug.
  - `400` / `401`: permanent failure, no retry.
  - `5xx` / timeout: retry.
- Subject e.g. `[HealthChecker] Website down` or `[HealthChecker] 2 down, 1 recovered`.
  Text and HTML bodies.

### `discord` (webhook)

- Settings: `webhookUrl`, optional `username`, optional `mention` (e.g. a role mention;
  sent with down events and reminders, not recoveries).
- Embeds: red for down, orange for reminders, green for recovered. Max 10 per message;
  splits if more. `allowed_mentions` is set explicitly so only the configured mention
  pings anyone.
- Honours `429` `retry_after`; retries `5xx`; other `4xx` are permanent.

## Configuration

`config.json`:

```json
{
  "server": {
    "port": 8080, "bind": "0.0.0.0",
    "publicBaseUrl": "https://status.example.com",
    "trustedProxies": ["127.0.0.1"]
  },
  "dataDir": "data",
  "timezone": "Atlantic/Reykjavik",
  "plugins": { "dir": "plugins" },
  "dashboard": {
    "title": "Status",
    "colors": { "yellowBelow": 100.0, "orangeBelow": 99.0, "redBelow": 95.0 }
  },
  "maintenance": {
    "countsAsDowntime": false, "maxLengthDays": 7, "upcomingDays": 7, "announceBeforeHours": 24
  },
  "defaults": {
    "intervalSeconds": 30, "timeoutMillis": 5000, "failureThreshold": 1,
    "visibility": "private",
    "notify": ["ops-email", "discord"],
    "reminders": [30, 60, 120]
  },
  "auth": {
    "sessionDays": 7,
    "local": { "enabled": true },
    "roles": { "ops": ["view.private", "maintenance.manage", "events.annotate"] },
    "users": [
      { "id": "siggi", "displayName": "Siggi", "role": "admin",
        "password": "pbkdf2-sha256$600000$...$...",
        "identities": ["google:110248495921238986420", "github:123456"] },
      { "id": "jane", "displayName": "Jane", "role": "ops",
        "identities": ["google:109876543210987654321"] }
    ],
    "rules": [
      { "provider": "google", "emailDomain": "example.com", "role": "viewer" }
    ],
    "providers": [
      { "id": "google", "type": "oidc", "label": "Google",
        "issuer": "https://accounts.google.com",
        "clientId": "...", "clientSecret": "${env:GOOGLE_CLIENT_SECRET}",
        "identifyBy": "sub" },
      { "id": "github", "type": "oauth2", "label": "GitHub",
        "authorizeUrl": "https://github.com/login/oauth/authorize",
        "tokenUrl": "https://github.com/login/oauth/access_token",
        "userinfoUrl": "https://api.github.com/user",
        "scopes": ["read:user"], "idField": "id", "nameField": "login",
        "clientId": "...", "clientSecret": "${env:GITHUB_CLIENT_SECRET}" }
    ]
  },
  "notifications": {
    "batchSeconds": 30,
    "channels": {
      "ops-email":  { "type": "email", "baseUrl": "https://mail-api.example.com",
                      "apiKey": "${env:SIMPLEMAILER_KEY}",
                      "from": "HealthChecker <health@example.com>", "to": ["ops@example.com"] },
      "discord":    { "type": "discord", "webhookUrl": "${env:DISCORD_WEBHOOK}",
                      "mention": "<@&123456789>" },
      "mc-discord": { "type": "discord", "webhookUrl": "${env:MC_DISCORD_WEBHOOK}" }
    }
  },
  "sites": [
    { "hosts": ["mc-status.example.com"], "title": "Minecraft status",
      "publicBaseUrl": "https://mc-status.example.com",
      "categories": ["minecraft"] },
    { "hosts": ["web-status.example.com"], "title": "Website status",
      "publicBaseUrl": "https://web-status.example.com",
      "endpoints": ["website"] }
  ],
  "categories": [
    { "id": "web",       "name": "Websites" },
    { "id": "minecraft", "name": "Minecraft servers", "description": "Java Edition" },
    { "id": "internal",  "name": "Internal" }
  ],
  "endpoints": [
    { "id": "website", "name": "Website", "type": "http", "category": "web",
      "url": "https://example.com", "maxClass": 2,
      "visibility": "public", "notify": ["ops-email"] },
    { "id": "admin", "name": "Admin panel", "type": "http", "category": "internal",
      "url": "http://10.0.0.5:8080", "maxClass": 4,
      "intervalSeconds": 10, "notify": ["discord"], "reminders": [15, 30, 60, 120] },
    { "id": "survival", "name": "Survival", "type": "minecraft", "category": "minecraft",
      "host": "mc.example.com",
      "visibility": "public-status", "notify": ["mc-discord", "ops-email"] },
    { "id": "test-mc", "name": "Test server", "type": "minecraft", "category": "minecraft",
      "host": "10.0.0.9", "port": 25566, "notify": false }
  ]
}
```

Validation fails fast at startup and reports all errors together, each prefixed with
its path (e.g. `endpoints[2] "survival": unknown type "minecraft" (available: http)`).
Checks include:
- unique endpoint ids (format `[a-z0-9-]+`) and names
- unique category ids (same format); every endpoint `category` refers to a known category
- sites: non-empty `hosts`, no host listed in two sites (warning for wildcard overlaps),
  `publicBaseUrl` required on a site when any OIDC/OAuth provider is available on it.
  Unknown category/endpoint ids in a site are a warning, not an error, because
  dashboard-managed ones can be deleted at runtime.
- no id defined in both `config.json` and `data/endpoints.json`
- dashboard-defined endpoints are validated with the same rules. If one fails at startup
  (e.g. its plugin jar was removed), it's loaded as **disabled**: shown to logged-in
  users with the error, and not checked. One bad entry never prevents startup.
- known types and channel names
- common settings in range
- `reminders` strictly increasing positive integers
- valid IANA `timezone`
- plugin validation
- `publicBaseUrl` required when any OIDC/OAuth provider is set
- users: unique ids, known roles, no identifier on two users, identifiers referring to
  configured providers; roles: known permission names; rules: known provider and role
- a provider with no users or rules is a warning, not an error: everyone signing in
  through it gets the "send this identifier to the admin" page, which is how you onboard
  people
- unresolved `${env:…}` references

Endpoints whose history exists in `data/` but which are no longer configured (in
either source) are kept on disk and logged at startup, not deleted.

## Architecture

```
Main  (subcommands: run [config], hash-password)
 ├─ ConfigLoader ──► Config
 ├─ PluginManager ──► Map<type, CheckPlugin>  (built-in http + plugins/*.jar)
 ├─ EndpointStore             (config.json entries, read-only + data/endpoints.json, editable)
 ├─ EndpointRegistry          (live set of Endpoints; add / update / remove at runtime)
 │    └─ EndpointFactory ──► Endpoint(id, name, type, visibility, notify, reminders, targets, Checker)
 ├─ CheckScheduler            (virtual threads, timeout enforcement; reschedules on registry changes)
 ├─ StatusRegistry            (live state, failure streaks, recent checks)
 │    └─ StateChange ─┬─► HistoryStore   (coverage, events, notes; files under data/)
 │                    └─► NotificationDispatcher (routing, reminders, batching, retry)
 │                            ├─ EmailNotifier   (SimpleMailer HTTP API)
 │                            └─ DiscordNotifier (webhook)
 ├─ UptimeCalculator          (24h, 60d, per-day summaries; cached)
 └─ WebServer (SiggiHTTP)
      ├─ AuthService: sessions, LocalLogin, OidcLogin/OAuth2Login, CSRF, RateLimiter
      ├─ ViewFilter:  data × viewer → visible view (allow-list, leak filter)
      ├─ Pages: /, /service/{id}[?view=list|calendar&page=N&day=…],
      │         /service/{id}/day/{date}, /service/{id}/event/{eventId}
      ├─ Event edits (POST, logged in): rename, add/edit/delete/toggle note
      ├─ Maintenance (/maintenance/{id}, /admin/maintenance…) ──► MaintenanceStore (data/maintenance.json)
      │     └─ MaintenanceScheduler: window start/end → StatusRegistry, HistoryStore, NotificationDispatcher
      ├─ Layout editor (POST /admin/layout, logged in) ──► LayoutStore (data/layout.json)
      ├─ Endpoint/category editor (/admin/endpoints…, /admin/categories…, test-now) ──► EndpointStore
      ├─ /admin/audit ◄── AuditLog (data/audit.jsonl)
      ├─ JSON:  /api/status, /api/service/{id}[/events|/days|/event/{eventId}]
      ├─ /health, /login, /logout, /auth/start/{id}, /auth/callback/{id}
      └─ /admin/notify-test   POST, logged in
```

App packages (`io.siggi.healthchecker`): `config`, `plugin`, `check.http`, `schedule`,
`status`, `history`, `notify`, `auth`, `admin`, `web`, `web.view`.

## Milestones

Each `- [ ]` is one commit-and-push step (see CLAUDE.md). Each step includes the tests
listed after **Tests:**, and they must pass before the step is committed. Steps with no
test list are build, docs or manual steps.

### M1 — Skeleton, modules & config
- [x] Add `.DS_Store` to `.gitignore`.
- [x] Convert to multi-module: `HealthChecker-Parent` (root), `HealthChecker-API` (`api/`), `HealthChecker` (`app/`, move `Main`); shading in app only, Gson not relocated. Add Gson and JUnit 5 + surefire. **Tests:** a trivial smoke test so `mvn test` runs in both modules.
- [x] `ConfigLoader`: server, dataDir, timezone, dashboard, defaults, sites, categories, endpoints (common fields incl. `id`, `category`, `reminders`), `${env:…}` substitution, collected error reporting. **Tests:** loading a full config; defaults applied; each validation rule; all errors reported together; env substitution and unresolved `${env:…}`.
- [x] `Main` with `run` (default) and `hash-password` subcommands (hasher is a stub until M9); `config.example.json` in repo root. **Tests:** `config.example.json` loads without errors.

### M2 — Plugin API & loading
- [x] API module: `CheckPlugin` (incl. `displayName`, `settings`, `apiVersion`), `SettingSpec`, `Checker`, `CheckResult`, `EndpointConfig` (incl. `declareTarget`), `ConfigException` (with optional setting key). **Tests:** `CheckResult`/`SettingSpec` builders.
- [x] `EndpointConfig` implementation over `JsonObject` with unknown-key detection. **Tests:** typed getters; missing required key; wrong type; unread keys reported.
- [x] `PluginManager`: built-ins + `plugins/*.jar`, duplicate/unknown type errors, version check. **Tests:** load a test plugin jar built during the test from a temp dir (using Gson from the parent loader); duplicate types; unknown type message lists available types; too-new API rejected.

### M3 — Built-in `http` check
- [x] `HttpCheckPlugin`: shared `HttpClient`, `Redirect.NEVER`, timeout, `maxClass` rule, URL validation, declared targets. **Tests:** against a local SiggiHTTP server, the 200/301/404/503 × `maxClass` 2–5 matrix; refused and timeout always fail; redirect not followed; `maxClass` 1 and 6 rejected; invalid URLs (path, query, scheme) rejected.

### M4 — Scheduling & live state
- [x] `StatusRegistry`: `failureThreshold` state machine, failure streak start time, recent checks (last 100), `StateChange` events, transition logging. **Tests:** thresholds 1 and 3; streak start time; UNKNOWN → UP/DOWN on first result; recent checks capped at 100.
- [x] `EndpointRegistry` with runtime add / update-in-place / replace-checker / remove; `CheckScheduler` follows it (per-endpoint schedule, jitter, virtual threads, enforced timeout, skip-if-stuck). **Tests:** plugin that throws; hangs; ignores interrupts (skip-if-stuck); endpoint added/replaced/removed while running (old checker closed, no stray runs).

### M5 — History store & uptime
- [x] `HistoryStore`: coverage periods with minute heartbeat; events (open/close, `start` = first failure of streak); yearly JSONL files; atomic writes; load at startup. Restart handling: close open events as `endedUnobserved`; coverage gap while app was down. **Tests:** write/read round trip; restart while down; restart while up leaves a gap; crash mid-write leaves the previous file intact.
- [x] `UptimeCalculator`: exact downtime ∩ coverage for 24 h, 60 d and any day in the configured timezone; "no data" handling; never round up to 100 %; per-day cache. **Tests:** event across midnight split between days; DST transition day (23 h / 25 h); no-coverage days; partial coverage; rounding (99.995 % → 99.99 %); cache invalidated on event change.

### M6 — Public main page & status API
- [x] `WebServer` on SiggiHTTP: port/bind, responders, `listen()` thread, `trustedProxies`, static CSS/JS; `GET /health`. **Tests:** `/health` 200; `X-Forwarded-For` honoured only from trusted proxies.
- [x] `ViewFilter` (allow-list copy) for `public` / `public-status` / `private`; targets never anonymous; leak filter omits entries containing declared targets. **Tests:** private endpoints absent; `public-status` fields exactly as in the visibility table; leaking message/detail omitted without placeholder while other details remain.
- [x] `LayoutStore` (read side) and category grouping: default layout from config, merged with `data/layout.json` if present; implicit "Other"; flat list with no categories; hide categories with no visible services; heading summary. **Tests:** default ordering from config; saved layout overrides order and category; new config entries appended; stale ids ignored; uncategorized last by default; category with only private services hidden from anonymous.
- [x] Sites: Host / trusted `X-Forwarded-Host` matching (exact, wildcard, port stripped), default site, `unknownHosts: "reject"`, scope filter applied before visibility, per-site title and base URL. **Tests:** scoping on main page and API; wildcard and port handling; unknown host default vs reject; `X-Forwarded-Host` ignored from untrusted IPs; links never built from the Host header.
- [x] Main page and `GET /api/status`: overall banner, category sections, rows with state dot, name, 24 h / 60 d %, 60-segment day bar with configurable colours, tooltips, links; responsive down to 320 px; light/dark. **Tests:** overall state and 200/503 from visible endpoints only; bar has 60 segments with correct colours for fixture data; no declared target anywhere in anonymous HTML or JSON (search output for every configured URL/host).

### M7 — Service page: list & calendar views
- [x] `/service/{id}` header (state, uptimes, bar, recent pass/fail strip); `404` for private when anonymous or out of site scope. **Tests:** 404 cases; header fields per visibility.
- [x] List view (default): paginated events, newest first, durations, expandable notes (public only when anonymous), event permalink page. **Tests:** paging; duration formatting; anonymous sees only public notes.
- [x] Calendar view: 3 months per page (current + 2 previous on page 0), Earlier/Later paging, colour-only day blocks, Monday-first grid. **Tests:** page 0/1/2 month ranges; year rollover; no "Later" on page 0; days before first coverage grey.
- [x] Day popover (JS) and `/service/{id}/day/{date}` fallback page: uptime, downtime, events clipped to the day; `?day=` deep link from the main-page bar. **Tests:** event spanning two days shown on both, clipped; `?day=` opens the right page.
- [x] JSON: `/api/service/{id}`, `/events`, `/days`, `/event/{eventId}`. **Tests:** same visibility and scope rules as the HTML; `/days` range limit.

### M8 — MinecraftChecker plugin (repo `plugin-projects/MinecraftChecker`)
- [x] `mvn install` HealthChecker so `HealthChecker-API` is in the local repo. Plugin project: pom (`io.siggi:MinecraftChecker`, API + Gson `provided`), `META-INF/services` entry, `.gitignore`, README. **Tests:** smoke test that the plugin is found by `ServiceLoader`.
- [x] Server List Ping: VarInt framing with packet size cap, handshake (original host), status request/response, MOTD flattening, optional ping. **Tests:** against a fake in-process status server: valid response; chat-component MOTD; garbage; oversized packet; timeout.
- [x] SRV lookup via JNDI DNS when `port` unset (priority/weight ordering, fallback on connect failure, `srv: false`); declared targets incl. resolved target. **Tests:** with an injected resolver: ordering, weighted choice, fallback to next record, no record → 25565, `port` set skips SRV.
- [x] Settings `host`, `port`, `srv`, `minOnline`, `expectVersionContains` with validation (per-field `ConfigException`s), `settings()` specs and `displayName()`. **Tests:** each invalid setting reports its key; too few players fails; version mismatch fails.
- [x] Integration check: build the plugin jar, copy it into a temp `plugins/` dir, start HealthChecker against a fake status server, and confirm via `/api/status` that the endpoint goes UP. Script it as `scripts/it-minecraft.sh` in the plugin repo.

### M9 — Sessions, built-in login, roles & permissions
- [x] Sessions via `SessionsBuilder` (no deprecated session API), cookie flags, ID rotation with `resetSession`, sliding expiry, CSRF tokens. **Tests:** cookie flags (`HttpOnly`, `SameSite=Lax`, `Secure` over HTTPS); anonymous page views set no cookie; ID changes on login; expiry; logout clears the cookie; CSRF rejection.
- [x] PBKDF2 hashing + `hash-password` subcommand. **Tests:** hash/verify round trip; wrong password; malformed hash string.
- [x] `/login` (form, constant-time, dummy hash for unknown users), `/logout` (POST + CSRF), rate limiter. **Tests:** success; wrong password; unknown user; rate limit per IP and per username; logout requires CSRF.
- [x] Users, roles and permissions: built-in `viewer`/`editor`/`admin`, custom roles, `disabled`, permission lookup per request; one `Permission` helper used by every responder. Logged-in view gated by `view.private`; header with user, role and logout. **Tests:** role expansion; unknown permission rejected at config load; disabled user (incl. existing session ended); user without `view.private` sees the anonymous view; private endpoint 404 without `view.private`.

### M10 — Dashboard editing: layout, event titles & notes
- [x] Layout editor on the main page (drag and drop plus up/down buttons and category dropdown; private services with visibility markers) and `POST /admin/layout` (CSRF, version check → 409, "Reset to config order", atomic write). **Tests:** save and reload; move service between categories; reorder categories incl. "Other"; conflicting save gets 409; no `layout.edit` → 403; unknown ids rejected; reset restores config order.
- [x] Rename event (empty = reset to default). **Tests:** rename visible publicly; reset; no `events.annotate` → 403.
- [x] Notes: add / edit / delete; public/private toggle; author and timestamps; length limit; forms in list view, event page and day popover; all POST + CSRF; persisted before responding. **Tests:** private note hidden from anonymous in HTML and JSON; edits survive restart; HTML escaping of note text; length limit.

### M11 — Endpoint & category management in the dashboard
- [x] `EndpointStore`: merge `config.json` (read-only) and `data/endpoints.json` (editable); duplicate-id startup error; disabled entries for ones failing validation; versioned atomic writes. **Tests:** merge; duplicate id error; disabled entry when its plugin is missing; stale-version write → 409.
- [x] Endpoint editor: common fields, type dropdown, type-specific form generated from `settings()`, field-level errors, no-JS two-step fallback, read-only view with "config" badge. **Tests:** form rendered from a test plugin's `settings()`; plugin validation error shown on its field; config-defined endpoint can't be edited via POST; no `endpoints.manage` → 403.
- [x] Save applies live via `EndpointRegistry` (in-place vs replace-checker rules; `endedByConfigChange`; reminder cancellation). **Tests:** create → checked within one interval; name edit keeps state and open event; host edit replaces checker and closes event as `endedByConfigChange`; no target leaks into public pages after edits.
- [x] "Test now" (no save, rate-limited). **Tests:** returns result without persisting; rate limit.
- [x] Delete with confirmation; keep or delete history (`history.delete`); block id reuse while history exists. **Tests:** history kept by default; deleted with permission; id reuse blocked.
- [x] Category editor in the layout editor (add/rename/delete dashboard categories; delete moves services to "Other"), and "Copy to dashboard" for config-defined endpoints/categories. **Tests:** delete moves services; config categories read-only; copy produces an equivalent entry and the removal instructions.
- [x] `AuditLog` (`data/audit.jsonl`) for all dashboard edits incl. layout, events and notes; `/admin/audit` page (`audit.view`). **Tests:** each edit type writes an entry with before/after; secrets masked; page requires `audit.view`.

### M12 — Scheduled maintenance
- [x] `MaintenanceStore`: windows (endpoints and/or categories, start/end, title, description, notes, `notifyChannels`), validation, versioned atomic writes, audit entries. **Tests:** cannot create in the past or longer than the limit; `end` > `start`; stale save 409; audit entries.
- [x] Recurrence: daily / weekly / monthly rules with `until`, DST-safe expansion in the configured timezone, skip/edit single occurrence, materialise occurrences when they start. **Tests:** weekly recurrence across a DST change keeps local time; "first Sunday" monthly; skip and edit one occurrence; editing a series doesn't change past occurrences.
- [x] `MaintenanceScheduler`: start/end transitions; "End now" and "Extend"; affected endpoints show state `MAINTENANCE`. History integration: DOWN during a window → `kind: maintenance` event; split at window end into a normal `outage`; uptime excludes maintenance downtime unless `countsAsDowntime`. **Tests:** with a fake clock: transitions; end now; extend; down inside window → maintenance event, no outage; down past window end → outage starting at window end; uptime with and without `countsAsDowntime`.
- [x] Dashboard and JSON: "Schedule maintenance" form (main page and service page), `/maintenance/{id}` page, edit/cancel; "Upcoming maintenance" box; blue day segments and calendar outlines; "Planned" badge and All/Outages/Maintenance filter; popover lists maintenance separately; `/api/maintenance`, `/api/maintenance/{id}`, maintenance state in `/api/status`. **Tests:** anonymous sees windows only for visible endpoints, with a filtered affected list; window with no visible endpoints hidden; no `maintenance.manage` → 403.

### M13 — OIDC / OAuth 2.0 login
- [x] Provider config + validation; `identifyBy` (`sub` default, verified `email`, other claim; `idField` for oauth2); identifier format `{providerId}:{value}`. **Tests:** config validation; identifier construction per mode.
- [x] OIDC: discovery, PKCE, state, nonce, code exchange, JWKS cache, `id_token` verification with SimpleJWT. **Tests:** with an in-process fake OIDC provider (SiggiHTTP + test RSA key): success; bad signature; wrong `aud`/`iss`; expired; nonce/state mismatch; `alg: none` rejected; unknown `kid` triggers a JWKS refresh.
- [x] Generic `oauth2`: code flow (+PKCE where supported), userinfo, `idField`/`nameField`. **Tests:** fake OAuth2 provider: success; userinfo missing `idField` fails cleanly.
- [x] Identity → user lookup, then rules (first match); "send this identifier to the admin" page (copy button, different-account link, no session); rejected attempts logged and audited, rate-limited; login page with provider buttons; local form hidden when disabled. **Tests:** unknown identity shows its exact identifier and creates no session; `email` identifier ignored when not verified; rule grants role; explicit user beats rule; disabled user gets the disabled page; rejected attempt appears in the audit log.
- [x] Per-site callback URLs from the site's `publicBaseUrl`; provider `sites` restriction. **Tests:** callback URL per site; provider hidden and rejected on sites it isn't enabled for.

### M14 — Notifications: framework, reminders & Discord
- [x] `NotificationDispatcher`: `notify` routing (`false`/`[]` off, omitted = defaults), batching, async retry/backoff, last-error tracking. **Tests:** routing cases (`false`, omitted, defaults `false` + opt-in); batching window combines; retry with backoff; last error recorded.
- [x] Reminders: schedule from outage start per `reminders` (+ optional `reminderRepeatMinutes`); cancel on recovery; persisted in `data/notifications/state.json`; overdue-after-restart sent once. **Tests:** with a fake clock: reminders at 30/60/120 and none after; none after recovery; repeat interval; restart mid-outage sends the overdue one once.
- [x] `DiscordNotifier`: embeds (down/reminder/recovered), splitting, `allowed_mentions`, 429 handling; "Send test notification" (`notifications.test`). **Tests:** fake webhook server: payload shape; >10 embeds split; mention only on down/reminder; 429 then success; 4xx permanent; test button requires permission.
- [x] Maintenance: suppress down/reminder/recovery for affected endpoints during a window; down at window end if still down; announcements (created, `announceBeforeHours`, started, ended, cancelled) to the window's `notifyChannels`. **Tests:** no alerts during maintenance; alert at window end if still down; announcements go to window channels only.

### M15 — Email via SimpleMailer
- [x] `EmailNotifier`: `POST /v1/messages`, stable UUID per notification across retries, response-code handling; text + HTML bodies, subject summary, event permalink. **Tests:** fake SimpleMailer API: 202; retry after 503 reuses the same id; 401 and 400 permanent; payload shape; HTML escaping of endpoint names and messages.

### M16 — Packaging & ops
- [x] Shutdown hook: final coverage heartbeat, stop scheduler, flush pending notifications (bounded), close checkers, kill HTTP server. **Tests:** shutdown closes checkers and writes final coverage within the time limit.
- [x] `mvn package` → runnable app jar; verify by starting it with `config.example.json`-derived test config and the MinecraftChecker jar in `plugins/`, checking `/health`, `/api/status` and `/`, then stopping it cleanly.
- [x] README: build, configure (`maxClass`, visibility, roles, history, auth, notifications, reminders, maintenance, sites), run, reverse proxy notes, backup of `data/`; sample systemd unit.
- [x] `PLUGINS.md`: writing a plugin (API, `settings()` / `SettingSpec`, `declareTarget`, Gson contract, services file, threading/timeout rules, minimal example, pointer to MinecraftChecker).

### M17 — Acceptance with real services (you)

These steps need your accounts, servers or secrets. The autonomous run stops before
them; see CLAUDE.md.

- [ ] **(you)** Run HealthChecker with your real config behind your reverse proxy; check the public page from a logged-out browser shows no targets.
- [ ] **(you)** Configure a real OIDC/OAuth provider (e.g. Google or GitHub), sign in once to get your identifier from the "send this to the admin" page, add it to your user, restart, sign in.
- [ ] **(you)** Point an email channel at your real SimpleMailer instance and a Discord channel at a real webhook; use "Send test notification" on each and confirm they arrive.
- [ ] **(you)** Add a real Minecraft server (with an SRV record, if you have one) and confirm it shows UP.

### Later
- Per-user / per-group access to private endpoints.
- Manually created or deleted events.
- iCal feed of scheduled maintenance.
- More plugins (raw TCP, DNS, TLS expiry, WebSocket, Minecraft Bedrock).
- Hot-reload of `config.json`.
- Managing notification channels, sites and users in the dashboard, including approving
  rejected identifiers from a "pending access requests" list with one click.
- Plugins contributing notifier types.

## Decisions

- `HealthChecker-API` is installed locally with `mvn install`; not deployed for now.
- Artifact names: `HealthChecker-Parent`, `HealthChecker-API`, `HealthChecker`, `MinecraftChecker`.
- Gson lives in the main app, not relocated; plugins use it with `provided` scope.
- Versions are managed in the parent pom: Gson 2.13.2 (the plugin contract version),
  JUnit 5.13.4 (test scope in every module), Java release 21. Only `app` shades; it
  doesn't write a dependency-reduced pom.
- Endpoint targets (URLs, hosts, ports) are never public; only names (and ids, in URLs) are.
- Uptime shown for rolling 24 h and 60 days; per-day uptime in the configured timezone.
- Reminders are minutes after the outage started; default `[30, 60, 120]`, configurable.
- Minecraft SRV records supported.
- Categories group services on the main page. Categories are defined in the config,
  which also sets the default order. Logged-in users can reorder categories and
  services, and move services between categories, on the dashboard. That layout is
  saved in `data/layout.json` and overrides the config.
- Endpoints and categories can come from `config.json` (read-only in the dashboard) or
  be created and edited in the dashboard (stored in `data/endpoints.json`, applied live).
  An id in both is a startup error.
- Logged-in users can schedule maintenance windows (one-off or recurring). Downtime
  inside a window is recorded as planned, doesn't count against uptime by default, and
  doesn't trigger alerts.
- Logged-in users have roles (`viewer`, `editor`, `admin`, or custom) made of
  permissions, enforced server-side. Users are configured in `config.json`.
- An OIDC/OAuth login that isn't allowed yet shows the person their identifier
  (`{providerId}:{value}`) to send to the admin, who adds it to a user's `identities`.
- Sites scope the view by `Host` header to chosen categories/services. This is
  presentation only, not a security boundary.
- `ConfigLoader` validates what it can on its own. Checks that need other components run
  there: known endpoint types and plugin settings (M2 `PluginManager`/`EndpointConfig`),
  users/roles/providers and "`publicBaseUrl` required with OIDC/OAuth" (M9/M13), channel
  settings (M14), ids in both config and `data/endpoints.json` (M11). `auth` and
  `notifications` are kept as raw, env-substituted JSON for those modules; `notify` names
  are checked against the keys of `notifications.channels`.
- Config is strict JSON (no comments or trailing commas). Unknown keys are errors in every
  section the loader parses; endpoint keys other than the common ones are passed on as
  type-specific settings.
- Ranges: `intervalSeconds` 1–86400, `timeoutMillis` 100–300000, `failureThreshold` 1–100,
  reminder minutes 1–525600, `reminderRepeatMinutes` 0 (= off, the default) or more,
  `maintenance.maxLengthDays`/`upcomingDays` 1–366. Dashboard colour thresholds 0–100 with
  `yellowBelow ≥ orangeBelow ≥ redBelow`.
- `defaults.notify` omitted means no notifications. Endpoint names must be unique
  (case-sensitive); endpoint `type` must match `[a-z0-9-]+`.
- `${env:NAME}` may appear anywhere inside any string value (not keys); `NAME` is
  `[A-Za-z_][A-Za-z0-9_]*`; substituted values aren't scanned again; an empty variable counts
  as set. There's no escape syntax.
- `timezone` must be an IANA region id (`UTC` works; bare offsets don't). `dataDir` and
  `plugins.dir` are relative to the working directory.
- Site hosts are lowercased, without ports; a wildcard is only allowed as a leading `*.`.
- Command line: `[run] [config.json]` (default `config.json` in the working directory); a
  single non-option argument is shorthand for `run <file>`. Config errors and warnings go
  to stderr and `run` exits with status 1 on errors; bad arguments print usage and exit 2.
  `hash-password` asks for the password twice via `Console.readPassword` and fails when
  there's no console. Until later milestones, `run` only loads and validates the config.
- `config.example.json` uses `${env:…}` for every secret, including client ids and the
  password hash, so it loads only when those variables are set.
- Plugin API details: `SettingSpec`'s modifiers (`asRequired()`, `defaultValue(v)`, `help(t)`)
  return copies; the "required" modifier is `asRequired()` because `required()` is the record
  accessor. A default must fit the kind (`String`, `Integer`, `Boolean`, `List<String>`, or one
  of the choices); only `CHOICE` has choices, and it needs at least one. `CheckResult` turns a
  `null` message into `""`, keeps details in insertion order, makes them immutable, and rejects
  values other than `String`/`Number`/`Boolean`. `EndpointConfig` getters throw
  `ConfigException` (with the key) for missing or wrongly typed values.
  `ConfigException.settingKey()` returns an `Optional<String>`.
- `JsonEndpointConfig` (app, package `plugin`): JSON `null` counts as absent; types are strict
  (no string-to-number/boolean conversion; integers must be whole and within `int` range,
  so `1e3` is fine but `1.5` and `"5"` aren't). Any getter call marks its key as read, even
  when it then fails on the type; `keys()` doesn't. `checkNoUnreadKeys()` reports every
  unread key in one `ConfigException` ("unknown setting(s) …") whose setting key is the
  first one. Declared targets are kept in order without duplicates.
- `PluginManager` (app, package `plugin`): the app's plugin API version is
  `PluginManager.API_VERSION` (1). A missing plugins directory means no plugins; a path that
  isn't a directory is an error. Jars load in file-name order, each only contributing the
  providers its own loader defines (services files the parent also sees are skipped). A jar
  without any `CheckPlugin` is a warning. Plugin types must match `[a-z0-9-]+`. All loading
  problems are collected and reported together; jar class loaders stay open until shutdown.
  `run` loads plugins after the config, logs each one, then validates every endpoint (known
  type, the plugin's `create()`, no unread settings; the checker is closed again) and reports
  those failures as configuration errors in the `endpoints[i] "id": …` format.
- `http` check (app, package `check.http`, registered via the app's `META-INF/services`): one
  `HttpClient` per plugin instance, created on first use, HTTP/1.1 only (no h2c upgrade or ALPN
  surprises), `Redirect.NEVER`, `User-Agent: HealthChecker`, response body discarded. The timeout
  is applied to the request and as a hard bound on the whole exchange (cancelled when it expires).
  The URL scheme is case-insensitive; user info (`user:pw@`) and ports outside 1–65535 are
  rejected. Failure messages are generic ("connection failed", "host not found", "TLS error",
  "timeout after N ms", …) and never include exception texts, which can name the target.
  Messages: `status N` on pass, `status N, expected at most Mxx` on fail. Declared targets: `url`
  as written, and `host` (IPv6 without brackets).
- `StatusRegistry` (app, package `status`): states `UNKNOWN`, `UP`, `DOWN` (`MAINTENANCE` comes
  with M12). The first result decides the state regardless of `failureThreshold` (a failure goes
  UNKNOWN → DOWN at once); UP → DOWN needs `failureThreshold` consecutive failures; DOWN → UP on
  the first success. A `StateChange` carries the triggering check plus the streak start, first
  failure message and failure count (for DOWN → UP: those of the outage that ended). Listeners
  run on the recording thread, in order per endpoint; a throwing listener is logged and skipped.
  Re-registering an endpoint only updates its threshold (state kept, applied from the next check);
  removing it drops its status, and late results for it are ignored. Transitions are logged via
  `System.Logger` (DOWN as WARNING, others INFO).
- `EndpointRegistry` / `CheckScheduler` (app, package `schedule`): `EndpointFactory` builds an
  `Endpoint` (definition, declared targets, checker) the same way validation does (unknown type,
  plugin `create()`, unread settings; a rejected checker is closed again). An update rebuilds the
  checker only when type, settings, interval, timeout or failure threshold changed; otherwise it's
  applied in place (same checker, state kept). An identical definition is a no-op; a failed update
  changes nothing. Registry listeners run in order under the registry lock; the scheduler owns
  the checkers it runs and closes retired ones. The first check runs after a random delay of up
  to `min(interval, 10 s)`, then at a fixed rate. The timeout grace period is 1 s. While the
  previous check is still running the next run is skipped: silently if that check is still within
  timeout + grace, counted as a failure ("skipped, previous check still stuck") once it has timed
  out. A thrown exception becomes "check failed (ExceptionClass)" (the full exception is only
  logged), a `null` result "check returned no result", a timeout "timeout after N ms". On replace
  or remove the in-flight check is interrupted, its result discarded, no new check starts, and the
  checker is closed when that check returns, or after the grace period at the latest (logged).
  `close()` stops everything, waits up to the grace period, and closes every checker. `run` doesn't
  start the scheduler yet; that comes with the web server and shutdown handling.
- `HistoryStore` (app, package `history`): a coverage period starts at the first check recorded
  for an endpoint, is extended by each check (to its end) and by the minute heartbeat (to now),
  and ends on `stopMonitoring` (endpoint removed/replaced) or `close()` (final heartbeat). Loaded
  periods are all closed, so a restart leaves a gap from the last heartbeat to the first new check.
  Events also store `lastFailureAt` (time of the last failed check); opening and closing an event
  is written at once, while `lastFailureAt`/`lastFailureMessage` of an ongoing event and coverage
  are written by the heartbeat. Events open at load are closed at `lastFailureAt` with
  `endedUnobserved` and written back; `stopMonitoring` closes an open event the same way. Events
  go into `events/{yyyy}.jsonl` by the UTC year of `start`; enums are lowercase, timestamps
  ISO-8601 UTC, nulls omitted. A malformed file, an event in the wrong year file or endpoint
  directory, or a duplicate event id is a startup error naming the file (no silent skipping, so
  a rewrite can't drop data). Atomic writes go through `AtomicFiles` (temp file in the same
  directory, fsync, atomic rename); leftover `.{name}.*.tmp` files are deleted at load. A failed
  write is logged and retried on the next heartbeat. `run` loads the history after validating
  endpoints (startup error if it can't).
- `UptimeCalculator` (app, package `history`): uptime = 1 − (counted downtime ∩ coverage ∩ window) /
  (coverage ∩ window), in exact nanoseconds. Counted downtime is every `outage` event plus
  `maintenance` events when `maintenance.countsAsDowntime`; overlapping events are merged so no time
  counts twice; an ongoing event runs until now, and so does the current coverage period while the
  endpoint is monitored (the heartbeat only writes it once a minute). Windows never extend past now.
  Rolling windows are `[now − 24 h, now)` and `[now − 60 × 24 h, now)`; a day is `[start of day,
  start of next day)` in the configured timezone, so DST days have 23 or 25 hours. Percentages are
  always truncated to two decimals (never rounded), so any downtime shows below 100 %; formatted as
  `100%`, `99.5%`, `99.99%`, and `—` without data. A day summary has the uptime, the number of
  counted events overlapping the day and the number of uncounted maintenance events. Day summaries
  are cached per endpoint once the day ended at least 10 minutes ago (a check started before
  midnight may be recorded after it); an endpoint's cache is dropped (by generation counter, so an
  in-flight computation can't store a stale entry) when one of its events opens or closes, or via
  `invalidate(id)`. `run` doesn't create it yet; the web server does.
- `WebServer` (app, package `web`): a SiggiHTTP `HTTPServer` from `HTTPServerBuilder` (virtual-thread
  executor) fed by HealthChecker's own platform accept thread (`HealthChecker-HTTP`) on `bind:port`,
  since SiggiHTTP's port-based constructors and `listen()` are deprecated. `close()` stops accepting.
  Built-in routes: `GET /health` (200, `text/plain` `ok`, `no-store`) and `/static/{style.css,app.js}`
  from classpath resources (allow-listed names, `Cache-Control: public, max-age=3600`); anything else
  under `/static/` is 404. `run` doesn't start the web server yet; it's started with the scheduler
  and shutdown handling.
- `server.trustedProxies` entries are IP addresses or CIDR ranges (IPv4 or IPv6, literals only, no DNS),
  validated at config load; an empty list trusts nobody. SiggiHTTP always trusts loopback and
  Cloudflare for `X-Forwarded-For`/`-Proto`, so `request.getIPAddress()` and `request.isSecure()` are
  not used: `TrustedProxies` starts from the socket peer (`getNextHopAddress()`) and, while the
  current hop is trusted, walks `X-Forwarded-For` (all header lines, comma-separated) from the right;
  it stops at the first untrusted hop or at an entry that isn't an IP literal (then the last trusted
  hop is the client). HTTPS = the nearest (last) `X-Forwarded-Proto` entry from a trusted peer is
  `https`, since HealthChecker never terminates TLS itself. SiggiHTTP's own trusted list is narrowed
  from its private-range defaults to the configured proxies.
- `ViewFilter` (app, package `web.view`): builds `EndpointView` / `CheckView` / `EventView` / `NoteView`
  records from an `Endpoint`, its `EndpointStatus`, `CheckRecord`s and `HistoryEvent`s for a `Viewer`
  (user name + `view.private`); a field the viewer may not see is `null`. Uptime and day summaries
  aren't part of the views (callers compute them for visible endpoints only). Without `view.private`,
  logged in or not: `public-status` shows id, name, state, `since`, recent checks as time + pass/fail
  (no `last` check), and events with title (custom or `"{name} outage"`), kind, start, end and public
  notes (author and timestamps included); `public` adds `type`, the last check, latency, message,
  details and event failure messages, leak-filtered. Never for those viewers: targets, visibility,
  `notify`/`reminders`, the custom-title flag, `endedUnobserved`/`endedByConfigChange`,
  `maintenanceId` (M12 decides what maintenance links anonymous visitors get) and note visibility.
  The leak filter compares case-insensitively and checks message text, each detail's key and value
  (`String.valueOf`) and failure messages against every non-empty declared target value; a match drops
  that item only.
- Layout (app, package `web.layout`, a new subpackage of `web`): `LayoutStore` reads
  `{dataDir}/layout.json` (`{"version": n, "categories": [{"id", "services": [...]}]}`); "Other" is stored
  as `"_other"`, which can't clash with a category id. A missing file means no saved layout (version 0);
  a malformed file (bad JSON, wrong types, unknown keys, negative or fractional version) is an error
  naming the file, not silently ignored, so a later save can't drop it. `ResolvedLayout.resolve` merges it
  with the current categories and endpoints: saved category order, service order and category win;
  missing categories are appended in config order, then "Other" if missing; missing endpoints are appended
  to their configured category in config order; stale or repeated saved ids are ignored (first wins); an
  endpoint whose category no longer exists goes to "Other". "Other" is always part of the resolved layout
  (possibly empty, for the editor). With no categories at all the layout is flat: every endpoint is in
  "Other" (a saved `_other` order still applies). `run` doesn't load it yet; the web server does.
- `CategoryGrouping` (app, package `web.view`): turns the resolved layout into `CategorySection`s for a
  viewer, using `ViewFilter` per endpoint; categories with no visible service are left out, the flat
  layout gives one section without a heading, and "Other" is headed "Other". Heading summary over the
  visible services: "{d} of {n} down" if any is DOWN, else "{u} of {n} not checked yet" if any is
  UNKNOWN, else "All operational" (maintenance is added with M12).
- Sites (app, package `web.site`): `SiteResolver` takes the host from `Host`, or from the nearest (last)
  `X-Forwarded-Host` entry when the socket peer is in `server.trustedProxies` (SiggiHTTP's own
  `X-Forwarded-Host` handling always trusts loopback, so it isn't used). Hosts are compared lowercase,
  without port (bracketed IPv6 keeps its brackets) and without a trailing dot; `*.example.com` matches
  subdomains at any depth but not `example.com` itself. A missing `Host` matches no site. A site's scope
  uses each endpoint's category in the effective layout (so a service moved by the layout editor follows
  it); "Other" can only be included by endpoint id. `Site.scope` filters the resolved layout before
  `CategoryGrouping` applies visibility. `Site.link(path)` is the only way links are built: the site's
  `publicBaseUrl`, else `server.publicBaseUrl`, else a root-relative path (paths starting with `//` are
  rejected). `server.unknownHosts: "reject"` gives `404` on dashboard pages and the API; `/health` and
  `/static/` answer on any host. `siteFor(endpoint)` (first site including it, else default) is ready for
  notification links.
- A minimal `StatusPage` (app, package `web.page`) serves `/` and `/api/status` so scoping is testable
  end to end; the full main page and API fields come with the next step. Overall state is DOWN if any
  service the viewer sees in scope is down (`/api/status` → 503), else UNKNOWN if any isn't checked
  yet, else UP; the HTML page is always `200`. The viewer comes from a request → `Viewer` function
  (anonymous until M9). Both responses are `Cache-Control: no-store`.
- Main page and `/api/status` (app, package `web.page`): the bar covers today and the 59 days before it in the
  configured timezone (today up to now). `DayColor`: grey without data; blue with maintenance and no counted
  downtime; otherwise red/orange/yellow below `redBelow`/`orangeBelow`/`yellowBelow` (only when there was
  downtime), else green; compared on the truncated percentage. Each segment is a link to
  `/service/{id}?view=calendar&day={date}` with the tooltip text as `title` and `aria-label` ("{date}: 99.3% uptime,
  10m down, 1 outage", or "no data"); `app.js` swaps `title` for a tooltip that also shows on keyboard focus and stays
  in the viewport. Durations are shown in at most two units, truncated (`45s`, `12m`, `1h 12m`, `2d 3h`). The page
  auto-refreshes with `<meta http-equiv="refresh" content="60">`, so it works without JS. JSON: uptimes and a day's
  `uptime` are numbers or `null` without data; each day also has `color` and, when non-zero, `maintenanceEvents`;
  a category `summary` also has `unknown`; `last`, `visibility` and `targets` appear only when `ViewFilter` provides
  them. Banner text: "N service(s) down", else "Waiting for the first checks", else "All systems operational".
- Service page header (app, `web.page.ServicePage`; shared HTML helpers moved to `PageSupport`): `/service/{id}` answers
  `404` (by writing nothing, so the body is the same as for an unknown id) for a rejected host, an id not matching
  `[a-z0-9-]+`, extra path segments, an unknown id, an endpoint outside the site scope (even with `view.private`), and an
  endpoint the viewer can't see. The header shows name, state with "since", 24 h / 60 d uptime, the same 60-day bar as
  the main page, and the recent checks (oldest first) as pass/fail blocks; beyond that it shows exactly the fields
  `ViewFilter` provides: type, last check (time, pass/fail, latency, message, details) for `public` endpoints, plus
  visibility and targets for `view.private`. Times are shown as `yyyy-MM-dd HH:mm:ss` in the configured timezone.
  Check tooltips show time, pass/fail and, when visible, latency and message. The service page doesn't auto-refresh.
- Service page list view (`web.page.ServicePage`, which now also takes the `HistoryStore` and a `Clock`): events sorted by
  start, newest first (ties by id), 25 per page; `?page=N` is 0-based, anything that isn't a non-negative number means 0,
  and a page past the last shows the last page. "← Newer" / "Older →" links (`rel="prev"`/`"next"`, to `#events`) and
  "Page n of m" appear only with more than one page. Each event is a `<details>` whose summary shows title, start – end
  (or "ongoing"), duration (`Durations.format`, ongoing ones up to now) and the number of notes the viewer can see; the
  body shows the failure messages `ViewFilter` provides, how the event ended (`endedUnobserved` / `endedByConfigChange`,
  privileged only), the visible notes (author, created/edited time, text with newlines kept via `white-space: pre-wrap`,
  plus a public/private badge for privileged viewers only) and a permalink. Until the calendar view exists, any `?view=`
  shows the list. Maintenance windows, the "Planned" badge and the filter come with M12; rename and note forms with M10.
- Event permalink `/service/{id}/event/{eventId}`: the same `404` rules as the service page, plus `404` for an event id
  not matching `[A-Za-z0-9-]{1,64}`, an unknown id, or an event of another endpoint. Shows the title, the service (link),
  start, end, duration, failure messages and end flags as visible, and the notes.
- Calendar view (`web.page.ServicePage`, `?view=calendar&page=N`; any other `?view=` shows the list): "List" / "Calendar"
  tabs are plain links (`?view=list` / `?view=calendar`, the current one with `aria-current="page"`) between the header and
  the view. A page's three months are shown oldest first (like the bar), each a 7-column Monday-first grid with `Mo`–`Su`
  labels above it, blank cells before the 1st, and one empty block per day coloured by `DayColor` (`cal-{colour}`), with
  the bar's tooltip text as `title`/`aria-label` (`role="img"`); days after today are outlined `cal-future` blocks
  ("{date}: upcoming"). Days before the endpoint's first coverage (and every day of an endpoint without coverage) are grey
  without being computed, so old pages don't fill the day cache. `page` is parsed like the list's and clamped to 4000
  (1000 years); "← Earlier" (`rel="next"`) is left out only on that last page and "Later →" (`rel="prev"`) on page 0, both
  linking to `#calendar`. Day blocks up to today are links to their day page (future days stay plain blocks).
- Day page and popover (`web.page.ServicePage`): `/service/{id}/day/{yyyy-mm-dd}` has the service page's `404` rules, plus
  `404` for anything that isn't a real `yyyy-mm-dd` date and for days after today (until M12 gives future days content).
  It shows the long date, the day's uptime, total counted downtime ("none" / `—` without data), the number of outages
  (and planned maintenance, when any), then every event (any kind) overlapping the day, oldest first: title linking to
  the permalink, times within the day (`00:00:00` when it started earlier, `24:00:00` when it went on later, "ongoing" on
  today), the duration within the day, a "Whole outage" line with the full start – end and duration when clipped, and the
  visible notes. Days before the first coverage aren't computed (no data). The popover reuses the day page: `app.js`
  fetches it and shows its `.day-detail` section next to the day (close button, Escape, click outside; a failed fetch
  navigates to the page), so there's one renderer and no JSON needed. `?day=` (a valid date up to today) selects the
  calendar view on the page containing that day, overriding `?view=` and `?page=`, marks the day (`cal-selected`,
  `aria-current="date"`) and renders the same section in a panel below the calendar with a "Close" link; `app.js` moves it
  into the popover. An invalid or future `?day=` is ignored.
- Service JSON API (`web.page.ServiceApi`, registered by `ServicePage`; shared JSON in `JsonViews`, also used by
  `/api/status`): every path uses the service page's `404` rules (same lookup, so an API path answers exactly when the
  matching HTML page does; unknown sub-paths are `404` too). `/api/service/{id}` is the `/api/status` endpoint object
  plus `recentChecks` (oldest first, fields as `ViewFilter` provides), `viewer`, and for `view.private` also `notify`,
  `reminders` and `reminderRepeatMinutes`; it answers `503` while the endpoint is DOWN, like `/api/status`. `/events?page=N`
  has `page`, `pages`, `pageSize`, `total` and `events`, paged like the list view. An event has `id`, `title`, `kind`
  (lowercase), `start`, `end` (`null` while ongoing), `ongoing`, `durationSeconds` (up to now while ongoing), `notes`
  (`id`, `author`, `createdAt`, `editedAt`?, `text`, `visibility` for privileged viewers only) and, where provided, the
  failure messages and privileged fields; `/event/{eventId}` adds `service`. `/days?from=…&to=…`: `to` defaults to today,
  `from` to the 59 days before `to`; a bad date, `from` after `to` or more than 400 days is `400` with
  `{"error": …}` (checked after the `404` rules); days after today are left out, days before the first coverage are
  no-data without being computed. The response echoes `from` and `to`.
- MinecraftChecker pom is standalone (no parent): it pins Java 21, `HealthChecker-API` 1.0-SNAPSHOT and Gson 2.13.2
  (both `provided`) and JUnit 5.13.4 itself. Package `io.siggi.minecraftchecker`, plugin class `MinecraftCheckPlugin`.
- Server List Ping (`ServerListPing`, `PacketIO`, `ServerStatus`, `Motd` in MinecraftChecker): the handshake sends
  protocol version `-1` (the convention when pinging to find the version) and the caller-supplied host and port; one
  deadline covers connect and the whole exchange. Received packets are capped at 2 MiB (mod lists can be large), checked
  from the length prefix before anything is allocated; a string can't be longer than the rest of its packet. A valid
  response is a JSON object with a `version` object; `players`, `protocol` and `description` are optional (missing or
  wrong-typed values are left out of the details). The ping is optional and never fails the check: a missing, wrong or
  late pong just leaves the round-trip time out. MOTD: component `text` (or `translate`'s `fallback`, else the key) and
  `extra` are concatenated, `§` codes stripped, each line trimmed. Protocol errors are `PingException` (an
  `IOException`) with messages that never contain the target.
- SRV lookup (MinecraftChecker: `SrvResolver`, `JndiSrvResolver`, `SrvOrdering`, `ServerAddressResolver`,
  `MinecraftChecker`): SRV is skipped when `port` is set, `srv` is `false`, or `host` is an IP literal. Records are
  ordered as in RFC 2782 (priority ascending; within a priority, zero-weight records first, then a running-sum
  weighted pick, repeated without replacement). Records with target `.` or port 0 are dropped; with no usable record,
  or when the lookup fails (not just NXDOMAIN), `host:25565` is used, like the vanilla client. The lookup gets half of
  the check timeout; the rest is shared for connecting (each attempt's connect limit is the remaining time divided by
  the addresses left), while the exchange may use all remaining time. Only connect failures (refused, unreachable,
  unresolvable target, connect timeout: `ConnectFailedException`) move on to the next record; a protocol error or a
  timeout after connecting fails the check. The handshake carries the configured `host` and the configured port, or
  25565 when it's unset (as the modern vanilla client sends it). Declared targets are fixed when `create()` runs, so
  `create()` does a best-effort SRV lookup (2 s limit; a failure is ignored, not a config error) and declares `host`,
  `port` (when set) and each record's `srv target` and `srv port`. Records that change later aren't declared;
  messages never contain targets anyway.
- MinecraftChecker settings (`MinecraftSettings`): validated in key order, the first invalid one throws a
  `ConfigException` with its key. `host`: a host name (dot-separated labels of letters, digits, `_` and inner `-`,
  1-63 characters each, at most 253 in total, no trailing dot), an IPv4 literal or an IPv6 literal (brackets optional
  and removed); a `host:port` form is rejected with a hint to use `port`. `port` 1-65535. `minOnline` >= 0; a hidden
  player count fails the check when `minOnline` is set. `expectVersionContains`: non-empty, at most 256 characters,
  case-sensitive substring of `version.name`. The version condition is checked before the player count; failures keep
  the status details, with messages `unexpected version` and `N players online, expected at least M`.
  `displayName()` is "Minecraft (Java Edition)"; `srv`'s spec has default `true`.
- `run` now starts the service (needed by the M8 integration check): endpoints from the config are added to an
  `EndpointRegistry`, `CheckScheduler` checks them, and each recorded result also goes to the `HistoryStore` through a
  scheduler `CheckListener` (`stopped` on replace/remove → `stopMonitoring`); the history heartbeat runs; the web server
  serves the main page, `/api/status` and the service pages, with every viewer anonymous until M9. It prints
  `Listening on bind:port` and runs until the JVM shuts down; a basic shutdown hook closes web server, scheduler
  (checkers) and history (final heartbeat). M16 extends that hook (notifications) and adds its tests. `Main.execute`
  takes a `Runner` so tests can start and stop the daemon in-process.
- MinecraftChecker integration check: `scripts/it-minecraft.sh` builds both projects (`mvn install` HealthChecker with
  tests skipped, `mvn package` for the plugin), runs `FakeStatusServerMain` (test classes; prints its port and a free
  port for HealthChecker) and the app jar against a generated config in a temp dir, polls `/api/status` until the
  endpoint is UP, then stops HealthChecker with SIGTERM.
- Sessions (app, `auth.SessionManager`): one SiggiHTTP `Sessions` from `SessionsBuilder` in `{dataDir}/sessions`,
  idle lifetime and cookie `Max-Age` both `auth.sessionDays` (a whole number 1–365, default 7, reported as a configuration
  error by `run`), `SameSite=Lax`, default cookie name `SessID`. Session keys: `user` (user id) and `csrf` (32 random
  bytes, hex). `read`/`userId` use `readSession` (no cookie for anonymous visitors, sliding expiry with a refreshed
  `Max-Age`); `login` uses `resetSession` (new ID and new CSRF token); `logout` uses `deleteSession`; `csrfToken` opens a
  session only when there's no token yet (the login form), so a pre-login session exists and is replaced at login.
  CSRF tokens come from the `csrf` form field or the `X-CSRF-Token` header (JSON POSTs), compared with
  `MessageDigest.isEqual`; `requireCsrf` answers `403` (plain text) on a missing or wrong token. Workaround for
  SiggiHTTP: `Sessions` decides `Secure` with `request.isSecure()`, which always trusts loopback and Cloudflare, so after
  every session call `SessionManager` rewrites the session's `Set-Cookie` headers with `Secure` exactly when
  `TrustedProxies.isSecure` says HTTPS (keeping only the last session cookie header when a read and a write both set
  it). `run` creates the `SessionManager`; the viewer stays anonymous until users and login are wired up in the next
  M9 steps.
- Password hashing (app, `auth.PasswordHasher`): JDK `PBKDF2WithHmacSHA256`; new hashes use 600000 iterations, a
  16-byte `SecureRandom` salt and a 32-byte hash, written as `pbkdf2-sha256$<iter>$<salt>$<hash>` in standard Base64
  with padding (padding optional when parsing). Parsing is strict: exact lowercase prefix, four `$`-separated parts,
  iterations 600000–10000000 (weaker hashes are rejected rather than silently accepted; the cap bounds login cost),
  salt 16–64 bytes, hash exactly 32 bytes. A malformed hash is an `IllegalArgumentException` whose message never echoes
  the hash (config validation and login use it); `verify` compares with `MessageDigest.isEqual`. `hash-password` reads
  through a small `PasswordPrompt` interface (backed by `Console.readPassword`) so tests can drive it; it rejects an
  empty or mismatched password and prints only the hash to stdout.
- Built-in login (app, `auth.LocalLogin`, `auth.LoginRateLimiter`, `web.page.LoginPage`): the username is the user id,
  matched exactly. Every attempt that isn't rate limited costs one PBKDF2 verification: unknown users, users without a
  password, disabled users and passwords over 1024 characters are checked against a dummy hash (a random password hashed
  once with the default parameters), and all failures get the same "Wrong username or password." (`401`, form shown
  again with the username kept). Failures are counted in a sliding 15-minute window per client IP (from
  `TrustedProxies`; IPv6 grouped by /64) and per username (case-insensitive, first 256 characters); at 10 failures for
  either key, attempts get `429` with `Retry-After` and aren't checked at all (and don't count again). A success clears
  the username's failures, not the IP's. The limiter is in memory (a restart resets it); expired keys are swept every
  1024 failures. `GET /login` shows the form (its CSRF token starts the pre-login session) or, when signed in, the
  user and a "Sign out" button; `POST /login` needs the CSRF token (`403`), then answers `303` to `next`. `next` is
  only a path on the same site (single leading `/`, printable ASCII, no backslash, ≤ 2048 characters; anything else
  means `/`), and the redirect is built with `Site.link`. `POST /logout` (CSRF) deletes the session and answers `303`
  to `/`; other methods get `405`. Both pages are `no-store` and can't be framed. With `auth.local.enabled: false` the
  form isn't shown and `POST /login` is `404`. Until the users and roles step, `run` reads only what login needs from
  `auth` (`local.enabled`, each user's `id`, `password` (validated hash) and `disabled`); failed sign-ins are logged
  with the client IP only (never the username or password); the audit log records come with `/admin/audit`.
- Users, roles and permissions (app, `auth.Permission`, `Role`, `User`, `UserDirectory`, `Permissions`): `Permission` is
  an enum of the eight config names; `Role.VIEWER`/`EDITOR`/`ADMIN` are fixed (`admin` = every permission). Custom roles
  in `auth.roles` are lists of permission names only (no role inheritance); names match `[a-z0-9][a-z0-9._-]{0,63}`, a
  built-in name is an error, an unknown permission is an error naming the known ones (case-sensitive), an empty list is
  allowed. User ids use the same pattern (it's also the password-login username). User keys: `id`, `displayName`
  (non-blank, defaults to the id), `role` (required, built-in or custom), `password` (valid hash), `identities` and
  `disabled`; anything else is an unknown-key error, as is any key in `auth.local` but `enabled`. An identifier is
  `providerId:value` (both parts non-empty), its provider must be the `id` of an entry in `auth.providers` (only the ids
  are read until M13 validates providers), and it may appear once in the whole user list. `auth.rules` and
  `auth.providers` are still left alone (M13). `run` reports all of these with the other configuration errors;
  `UserDirectory` replaces the interim user parsing in `LocalLogin`, which now only reads `auth.local.enabled`.
  `Permissions` is the one helper every responder uses: it reads the session's user id, looks the user up in the
  directory on each request, and ends the session (`deleteSession`, cookie cleared) when that user is disabled or no
  longer configured, treating the request as anonymous. `viewer(request)` gives the pages' `Viewer` (display name, role,
  `view.private`, and the session's CSRF token for the header); `require(request, site, permission)` answers `303` to
  `/login?next=<path and query>` (for a GET, else `next=/`) for anonymous visitors and `403` (plain text) for signed-in
  users without the permission. The page header shows the display name, the role and a "Sign out" button (POST with
  the CSRF token), or a "Sign in" link for anonymous visitors (no session is started for them); the sign-in pages have
  no user area. A signed-in user without `view.private` gets exactly the anonymous data in HTML and JSON (the JSON
  `viewer` field is their display name).
- Layout editor (app, `web.page.LayoutEditor`; saving in `LayoutStore.save`): the main page in edit mode is
  `/?edit=layout` (no auto-refresh, `frame-ancestors 'none'`), linked as "Edit layout" from the main page for users who
  may use it. It needs `layout.edit` **and** `view.private`, since it lists every service including private ones (a
  role with only `layout.edit` gets `403`; anonymous visitors are sent to `/login`). It always shows the whole global
  layout, whatever site it's opened on. Each service shows its visibility as a badge, a category menu and up/down
  buttons; categories (including "Other") have up/down buttons; HTML5 drag and drop on top. The editor needs JS (Save
  is a JSON POST, as planned); without it the page shows a notice and hides the controls. `POST /admin/layout` takes
  `{"version", "categories": [{"id", "services"}]}` or `{"version", "reset": true}` as `application/json` (`415`
  otherwise, `413` over 256 KiB) with the CSRF token in `X-CSRF-Token`; permission is checked before CSRF. Unknown
  keys, unknown or repeated category/service ids (in a flat layout only `_other`) are `400` `{"error"}`; ids left
  out are added as `ResolvedLayout.resolve` does, and the whole effective layout is stored (so stale ids are
  dropped). A save based on another version is `409` `{"error", "version"}`. "Reset to config order" stores the next
  version with an empty category list (which resolves exactly like no saved layout) instead of deleting the file,
  so versions never go back and an editor opened before the reset still gets `409`. Saves are serialized in
  `LayoutStore`, written with `AtomicFiles`; leftover `.layout.json.*.tmp` files are removed at load. After a save
  the editor returns to the main page; on `409` it offers "Reload".
- Event rename (app, `web.page.EventEditor`, wired into `ServicePage`; `HistoryStore.rename`): a plain form POST to
  `/service/{id}/event/{eventId}/title` with `title`, `csrf` and `next`. The service page's `404` rules come first (also
  for the event: unknown, of another endpoint, or not visible), then `events.annotate` (`303` to `/login` for anonymous
  visitors, `403` otherwise), then CSRF. The title is trimmed; empty means reset (stored as `null`); more than 200
  characters (code points) or any control character is `400`. `HistoryStore.rename` rewrites the event's year file
  synchronously and undoes the in-memory change if that fails (`500`), so the change is on disk before the `303` to
  `next` (checked like the login's `next`; without one, the event permalink). The rename form is shown in the list
  view and on the permalink page to users with `events.annotate`, prefilled with the stored custom title (also for
  users without `view.private`, who may rename anyway) and the default title as placeholder; the list returns to the
  same page and event. The audit entry (`event.rename`) is written by `AuditLog` (M11).
- Event notes (app, `web.page.EventEditor`, routed by `ServicePage`; `HistoryStore.addNote` / `editNote` /
  `deleteNote`, which share the rename's write-then-undo-on-failure path): plain form POSTs with `csrf` and `next` to
  `/service/{id}/event/{eventId}/notes` (add: `text`, `visibility`, default `private`),
  `.../note/{noteId}` (edit: optional `text` and `visibility`, a missing field is kept, so the "Make public/private"
  toggle is a form with only `visibility`) and `.../note/{noteId}/delete`. Order of checks as for the rename: the
  service page's `404` rules (also for the note: unknown, or not visible to the viewer, so a user with
  `events.annotate` but without `view.private` can't edit, toggle or delete private notes they can't see, but may add
  notes of either visibility), then `events.annotate`, then CSRF. Text: `\r\n`/`\r` become `\n`, surrounding
  whitespace is stripped; empty, more than 10 000 characters (code points) or a control character other than newline
  and tab is `400`; an unknown visibility is `400`. The author is the user's display name and stays unchanged when
  someone else edits the note; `editedAt` is set only when the text changes (not on a visibility toggle). Without
  `next` the redirect goes to the event permalink (`#note-{id}`). Forms appear for users with `events.annotate` in the
  list view, on the permalink page and in the day details (day page, `?day=` panel and popover, which return to
  `?day={date}`); `app.js` asks before deleting (`data-confirm`) and opens the event's `<details>` for an
  `#event-`/`#note-` fragment. The audit entries (`note.add`/`note.edit`/`note.delete`) are written by `AuditLog` (M11).
- `EndpointStore` (app, package `config`): `{dataDir}/endpoints.json` is `{"version", "categories": [{"id", "name",
  "description"?}], "endpoints": [...]}`; endpoint entries have the same keys as in `config.json` and are stored as
  entered (no `${env:…}` substitution), so `defaults` apply to whatever they leave out. Both are parsed by the same
  `ConfigLoader` code (`parseEndpoint` / `parseCategory`) and plugin validation (`PluginManager.validate`). Errors at
  startup, all reported together and naming the file: bad JSON, unknown root keys, a bad `version`, an endpoint entry
  without a valid id, an id repeated in the file, any endpoint or category id defined in both sources, and an invalid
  dashboard category (no plugin involved, so only hand edits can break one). Every other problem of a dashboard endpoint
  (unknown type, plugin rejection, unknown category or channel, a name also used elsewhere, …) loads it **disabled**: kept
  with its errors (startup warning), not checked, and written back unchanged by later saves; showing it to logged-in
  users comes with the endpoint editor. Merged order: config entries first, then the file's. Writes (`putEndpoint`,
  `removeEndpoint`, `putCategory`, `removeCategory`) take the version they're based on (`VersionConflictException`, for
  `409`, otherwise), validate first (`InvalidEntryException` with `key: message` errors; config-defined ids are
  read-only), then write the next version with `AtomicFiles`; leftover `.endpoints.json.*.tmp` files are removed at
  load. Removing a dashboard category also drops `category` from its dashboard endpoints in the same write (they go to
  "Other"). Config endpoints may only use config categories (config validation doesn't see the file). `run` loads the
  store after validating the config's endpoints and checks the merged, enabled endpoints; layout and pages use the
  merged lists. Live application of later edits comes with the "Save applies live" step.
- Endpoint editor (app, `web.page.EndpointEditor`): `GET /admin/endpoints` lists every endpoint of both sources
  ("config" badge, disabled ones with their errors) with "Add service"; `/admin/endpoints/new` and `/admin/endpoints/{id}`
  are the forms (GET shows, POST submits; plain form POSTs with `csrf`). Access needs `endpoints.manage` **and**
  `view.private` (the editor shows private endpoints and targets), like the layout editor: anonymous visitors go to
  `/login`, others get `403`; then CSRF. The main page shows "Add service" / "Manage services" and the service page an
  "Edit" link to users who may use it. Field names: the common JSON keys, `notifyMode` (`default` / `none` / `channels`)
  with `notify.{channel}` checkboxes, `s.{key}` for type settings, `settingsFor` (the type whose fields were sent),
  `version` and `action` (`type` = re-render, `save`). Blank common fields are left out of the entry (so `defaults`
  apply; fields that are set show "overrides default"); reminders are comma-separated minutes or `none` (`[]`). Settings
  by kind: text inputs for STRING/INTEGER, a not set/yes/no select for BOOLEAN, a select for CHOICE, a textarea (one per
  line) for STRING_LIST; empty values are left out; a plugin's `defaultValue` pre-fills the field when a type is first
  chosen. Non-numeric numbers are form errors; everything else is validated by `EndpointStore.putEndpoint` (config
  parser + plugin `create()`), whose `key: message` errors are shown on the field with that key (others above the
  form); `400` with the values kept, `409` (stale `version`) re-renders with the current version. Keys of a stored entry
  that the type's `settings()` doesn't describe aren't shown and are dropped on save. Without JS the form has two steps
  ("Continue" posts `action=type`); with JS, `app.js` swaps in the type's fields from a `<template>`. A config-defined
  endpoint is a read-only view (disabled fieldset, effective values) and its POST is `403`; a new id already used by
  either source is a field error. After saving, the editor redirects to `/admin/endpoints/{id}?saved=1`; applying the
  change live comes with the next step.
- Save applies live (app, `Daemon`): `EndpointStore` runs change listeners after every successful write, still under
  its lock (so writes apply in order); the daemon's listener calls `EndpointRegistry.sync(store.definitions())`, which
  removes endpoints no longer listed, adds new ones and `update`s the rest (in place or replaced by the existing
  rules). An add/update that fails there (the store already validated it, so only a flaky plugin `create()`) is
  logged and leaves that endpoint as it was; the save itself still succeeded. `CheckScheduler.CheckListener.stopped`
  now gets a `StopReason` (`REPLACED` / `REMOVED`); on `REPLACED` the history closes an open event at its last failure
  with `endedByConfigChange` (not `endedUnobserved`), via `HistoryStore.stopMonitoring(id, configChange)`. Reminders
  don't exist yet: M14 cancels them on that event close (history listeners see it), so nothing more is done here.
  Past targets: event and check messages recorded before a host/URL edit name the old target, which the leak filter
  (current targets only) would let through. So `HistoryStore` remembers every target value an endpoint ever declared
  (`endpoints/{id}/targets.json`, written when an endpoint is added, updated or replaced; it goes when its history is deleted),
  pages get the endpoint with those as `Endpoint.pastTargets`, and `ViewFilter.leaks` checks current and past values.
  Past targets are never shown, only filtered. Targets declared before this change (config edits plus a restart)
  aren't known; they're remembered from the first start with this version on.
- "Test now" (app, `web.page.EndpointEditor`, `schedule.OneOffCheck`, `auth.ActionRateLimiter`): a "Test now" button
  (`action=test`) next to Save on the add/edit form, shown once a type is chosen; same access rules and CSRF as saving,
  so config-defined endpoints (read-only, POST `403`) can't be tested from the dashboard. Every test request counts
  against the user's limit first (10 per sliding minute per user id, in memory; invalid forms count too, since
  validation already runs the plugin's `create()`); over the limit the form comes back with `429`, `Retry-After` and
  the values kept. The form is then validated exactly like a save (`EndpointStore.check`, which `putEndpoint` now
  shares, without the version check), and `OneOffCheck` creates a fresh checker, runs one check on its own virtual
  thread with the scheduler's rules (timeout + 1 s grace, then interrupted; "timeout after N ms", "check failed (…)",
  "check returned no result") and closes it (when the check returns, or after the grace period at the latest). The
  form is shown again with the result above it: passed/failed, latency, message and details, and "Nothing was saved."
  Nothing is written, registered or recorded in the history or status. The request waits for the check (at most the
  endpoint's timeout plus grace).
- Delete (app, `web.page.EndpointEditor`, `HistoryStore.deleteHistory`, `EndpointStore.reserveIdsWithHistory`):
  `GET /admin/endpoints/{id}/delete` is the confirmation page (same access as the editor; config-defined endpoints get
  `403` on GET and POST), linked as "Delete…" from the edit form and "Delete" on the service page for dashboard
  endpoints. `POST` (CSRF) needs `confirm` = the id (else `400`, nothing deleted) and the `version` (`409`); it removes
  the entry with `EndpointStore.removeEndpoint`, whose change listener stops the checks and ends coverage (an open event
  closes as `endedUnobserved`), then redirects to `/admin/endpoints?deleted={id}` with a notice. The "also delete
  history" checkbox (`deleteHistory=on`) is only shown with `history.delete`; sent without it, the request is `403`
  and nothing is deleted. Deleting history renames `endpoints/{id}` to `endpoints/.deleted-{id}-{uuid}` first, then
  removes it (a leftover is removed at the next load), drops it from memory and tells delete listeners (the uptime
  cache). Id reuse: `EndpointStore.putEndpoint` refuses a **new** endpoint whose id `HistoryStore.hasHistory` (any
  stored coverage, events or targets, also of an endpoint removed from `config.json`); the error is on the `id` field.
  "Test now" isn't affected. There's no dashboard action to delete the history of an already-deleted endpoint: remove
  `data/endpoints/{id}` while the app is stopped to free the id.
- Category editor (app, `web.page.CategoryEditor`, `LayoutStore.moveToOther`): plain form pages under
  `/admin/categories` (`new`, `{id}` = rename, `{id}/delete`, `{id}/copy`), reached by links in the layout editor, so
  they work without JS; changes are saved right away and return to `/?edit=layout` (the editor's unsaved-changes warning
  covers leaving it). Same access as the endpoint editor (`endpoints.manage` + `view.private`; the layout editor only
  shows the links to such users), CSRF, and the endpoints `version` (`409` when stale). The id is set at creation only;
  a new id used by either source is an error. Config categories get a "config" badge and a read-only view; their rename
  and delete are `403`. Deleting asks for confirmation listing the category's services (layout order, plus disabled
  dashboard endpoints in it); `EndpointStore.removeCategory` drops `category` from its dashboard endpoints, then
  `LayoutStore.moveToOther` moves the saved layout's services of that category to the end of "Other" (created last if
  the saved layout lacks it) as the next layout version, so config endpoints the layout had put there go to "Other"
  too instead of back to their config category. If that layout write fails, the deletion stands and it's logged.
- "Copy to dashboard" (app, `EndpointStore.copyEndpoint`/`copyCategory`, `web.page.CopyToDashboard`): `Config` now keeps
  each config endpoint/category entry as written and as env-substituted (`Config.Entry`). The copy is the substituted
  entry (the dashboard file isn't substituted, so placeholders' current values are stored; the page says so), not the
  effective values, so `defaults` keep applying as before. It's validated as a dashboard entry as it will be after the
  restart and must parse to exactly the same endpoint/category. Copies are written into `data/endpoints.json` next to
  the dashboard entries but held aside until restart (later writes keep them; nothing changes at runtime, the config
  entry stays read-only and checked). The page then shows the removal instructions: the snippet as written in
  `config.json` (with its placeholders), "restart", for a category the config services still using it (copy or
  re-categorise them first, else `config.json` no longer loads), and "Cancel copy" (removes the pending copy).
  Restarting without removing the config entry is the existing "defined in both" startup error. Pending copies are
  marked in the services list and the layout editor. Copied entries go after the file's other entries, so with no saved
  layout an endpoint's default position moves to after the config endpoints.
- Audit log (app, `audit.AuditLog`, `web.page.AuditPage`, `web.page.Audits`): `data/audit.jsonl`, one JSON object per
  line: `time`, `user` (id; `null` when nobody is signed in), `userName` (display name at the time), `action`, `id`,
  `before`, `after` (`null` for none, e.g. on create/delete). Each entry is appended and forced to disk; a line cut
  short by a crash is skipped when reading, and the next entry starts on a new line. A failed audit write is logged and
  doesn't undo the change (it's already saved). The editors record after a successful write, holding the store's lock
  (`synchronized` on the `EndpointStore`/`LayoutStore`/`HistoryStore`, whose writes are synchronized on themselves) so
  `before` is exactly what the write replaced; refused edits (403, 409, 400) write nothing. Actions and values:
  `layout.save` / `layout.reset` (id `layout`; the saved layout, `before` `null` when there was none),
  `layout.move-to-other` (id = deleted category; written by a category delete that changed the saved layout),
  `event.rename` (`{endpoint}/{event}`; `{"title"}`, `null` = default), `note.add` / `note.edit` / `note.delete`
  (`{endpoint}/{event}/{note}`; author, createdAt, editedAt, visibility, text), `endpoint.create` / `endpoint.edit` /
  `endpoint.delete` (the stored dashboard entry), `endpoint.history.delete` (no values), `category.create` /
  `category.edit` / `category.delete` (the category entry), `endpoint.copy` / `category.copy` and their `.cancel` (the
  entry **as written in `config.json`**, so `${env:…}` values substituted into the stored copy never reach the log),
  and `login.failed` (failed built-in sign-in, which the M9 login decision deferred to here: id = client address, never
  the username or password; rate-limited attempts aren't recorded, which bounds the log). Masking is applied to every
  entry: a non-null value under a key whose name (lowercased, without `-`/`_`/`.`) contains `password`, `passwd`,
  `passphrase`, `secret`, `token`, `apikey`, `accesskey`, `privatekey`, `credential`, `authorization`, `webhook` or
  `cookie` becomes `"***"` (whole objects/arrays too), and in any other string the password of a URL's user info
  (`scheme://user:pw@`) does. Plugins can't mark a setting secret (the API has no flag), so plugin authors should name
  secret settings accordingly. `GET /admin/audit` shows 50 entries per page, newest first (`?page=N`, 0 = newest;
  invalid or past-the-end pages are `404`), times in the configured timezone, before/after as pretty JSON in a
  `<details>`, `no-store`, can't be framed. It needs `audit.view` **and** `view.private` (entries show private
  services, targets and private notes), like the editors; anonymous visitors go to `/login`, others get `403`. The main
  page shows an "Audit log" link to users who may open it. The file is read whole for each page view (edits are rare;
  no rotation in v1). M12 (maintenance) and M13 (rejected OIDC logins) add their own actions.
- `MaintenanceStore` (app, package `maintenance`, with `MaintenanceWindow`): `{dataDir}/maintenance.json` is
  `{"version", "windows": [{"id", "version", "title", "description"?, "start", "end", "endpoints", "categories",
  "notifyChannels", "notes", "createdBy", "createdByName", "createdAt", "updatedAt", "cancelledAt"?}]}`; strict
  decoding like the other stores (unknown keys, bad types or a duplicate id fail the load, naming the file), leftover
  `.maintenance.json.*.tmp` files removed at load, every write via `AtomicFiles`. Ids are UUIDs. The file `version`
  counts writes; the 409 check uses each window's own `version` (edit and cancel name it; `VersionConflictException`),
  so editing one window doesn't make forms for other windows stale. Notes work like event notes (`EventNote`, same
  text rules and 10 000 limit, no version check). Status is derived: `cancelledAt` set → cancelled, else scheduled
  before `start`, in progress from `start` (inclusive) to `end` (exclusive), then completed; a category covers whatever
  is in it at the moment (`affects(endpoint, category)`). Validation (`InvalidWindowException`, `key: message`): title
  trimmed, required, ≤ 200 characters, no control characters; description optional, ≤ 10 000; `start` strictly after now
  on create; `end` > `start` and ≤ `maxLengthDays` (only checked when the times change, so lowering the limit doesn't
  lock existing windows); at least one endpoint or category; endpoint, category and channel ids must exist, except ids
  the window already had (they may have been deleted since); duplicates and blanks are dropped. On edit, a `start` or
  `end` that has passed is fixed and a changed one must be in the future, and once a window has ended its endpoints and
  categories are fixed too (title, description, channels and notes can still be corrected). Cancelled windows can't be
  edited (notes still can). Only a window that hasn't started can be cancelled; one in progress is ended with "End now"
  (scheduler step). The store records the audit entries itself, after a successful write and under its lock, with an
  `Actor` (user id, display name): `maintenance.create` / `.edit` / `.cancel` (id = window; the window without notes),
  `maintenance.note.add` / `.edit` / `.delete` (`{window}/{note}`; the note as for events). The store isn't wired into
  `run` yet; that comes with the scheduler and dashboard steps, which supply `References` (endpoint store, channels).
- Recurrence (app, package `maintenance`: `Recurrence`, `OccurrenceChange`): a window with `recurrence` is a series.
  Rules: `daily`, `weekly` (weekdays), `monthly` by `dayOfMonth` (1–31, months without it skipped) or by `week` (1–5,
  -1 = last; months without a 5th skipped) + `weekday`; optional inclusive `until` date; no "every N" interval. The
  series' `start`/`end` are its first possible occurrence; the rule picks dates from that date on (the first date
  needn't match it), each occurrence at the same local start time and local length in the configured timezone (a time
  in the spring gap moves forward by the gap; absolute length if the local one would be ≤ 0). An occurrence is
  `{seriesId}_{date}`. Validation: rule complete, `until` not before the first date, at least one occurrence, and an
  occurrence no longer than the rule's smallest gap (daily 1 day, weekly the closest weekdays, monthly 28 days) so
  they never overlap. A window can't become a series or back after creation. A series is never in progress itself
  (status `scheduled` or `cancelled`); it can be cancelled at any time, which stops future occurrences only. Its
  times may only change to a first occurrence in the future; title, services, channels and rule can change anytime.
- Single occurrences (`skipOccurrence` / `editOccurrence` / `restoreOccurrence`, each naming the series `version` and
  bumping it): an edit sets its own title, description, start and end (rules of a new window); services and channels
  follow the series. Only occurrences that haven't started can be changed. Stored on the series as `exceptions`
  (`{"date", "skip": true}` or `{"date", "title", "description"?, "start", "end"}`); audited as
  `maintenance.occurrence.skip` / `.edit` / `.restore` with id `{series}/{date}`.
- Materialising: an occurrence is stored as its own window (`seriesId`, `occurrence`, id `{seriesId}_{date}`, version
  1, no notes) once it has started, via `MaintenanceStore.materialise()` (for the scheduler) and at the start of
  every store write. So everything of a series that started is stored whenever the series is written, and a series
  only yields occurrences starting after its `updatedAt`: rule or time edits never create or change past
  occurrences. Materialising isn't audited (no user action). `occurrences(from, to)` lists stored windows plus
  not-yet-stored occurrences; `occurrence(id)` finds either. The store takes the configured `ZoneId`.
- `MaintenanceScheduler` (app, package `maintenance`): `tick()` materialises started occurrences, takes the windows in
  progress and gives each endpoint the one it's in (endpoint ids, or its category in the effective layout at that moment;
  overlapping windows: the earliest start, then id). A change applies at the window's own start or end when that has
  passed (a late tick still splits at the right moment), at "now" when an endpoint joins a window that was already running
  (moved into its category, added) or leaves one by an edit. Started, it ticks at every window start/end (looked ahead at
  most 30 s; otherwise every 30 s) and, via a `MaintenanceStore` change listener (run after every write, under the store's
  lock, so it only queues a tick), after every store change. "End now" (`MaintenanceStore.endNow`: end = now) and "Extend"
  (`extend`: a later end within `maxLengthDays` of the start) need the window's `version` and an in-progress window
  (also a materialised occurrence); audited `maintenance.end` / `maintenance.extend`; the scheduler's wrappers tick at
  once. `run` loads `data/maintenance.json` (references: merged endpoints and categories, configured channels) and starts
  the scheduler before the checks. `StatusRegistry.setMaintenance` (kept apart from registration) makes the shown state
  `MAINTENANCE` with `since` = the window start; `EndpointStatus` adds `checkState` (the UP/DOWN/UNKNOWN machine, which
  keeps running and reporting `StateChange`s) and `maintenanceId`. `HistoryStore.setMaintenance` (in memory only,
  re-applied by the scheduler's first tick after a restart) decides the kind of new events (`maintenance` with
  `maintenanceId`, or `outage`) and splits an open event of the other kind at the change: closed there (not unobserved)
  and a new event starting there with the old one's last failure message; an open event that started at or after the
  change just changes kind. This applies at a window's start too (an outage ongoing when a window starts becomes
  maintenance from then on, symmetric to the end). A failure streak that began before the latest change and reaches
  DOWN after it is split the same way (a closed event of the earlier kind up to the change).
- Maintenance in the dashboard (app, `web.page`: `MaintenanceViews`, `MaintenancePage`): `MaintenanceViews` decides what a
  viewer sees: a window's affected list is the services it covers (endpoint ids, or their category in the current
  effective layout) that are in the site scope and visible to the viewer, in layout order; a window with none is hidden
  everywhere (`404` on its page and API). Only viewers with `view.private` also see windows that cover no existing
  service at all (e.g. an empty category), so they can still be edited or cancelled. Cancelled windows are left out of
  the box, the lists and the day colours, but their own page still answers. Raw endpoint/category ids, channels, the
  creator and the rule are `view.private` only; notes follow their own visibility; there are no targets on windows.
  Maintenance events keep `maintenanceId` privileged-only, so anonymous visitors get no link from an event to its window.
- "Schedule maintenance" is a link (main page; service page with `?service={id}` preselected) to `/maintenance/new`, a
  plain POST form (CSRF): title, description, start/end as `datetime-local` in the configured timezone, services and
  whole categories as checkboxes (`endpoint.{id}`, `category.{id}`), announcement channels (`channel.{name}`, shown to
  `view.private` only), and a repeat rule (none, daily, weekly with weekdays, monthly by day or by "n-th weekday", optional
  until). Only the services/categories the viewer can see in scope can be picked; on edit, ids they can't see are kept
  as they were, and any other unknown id is `400`. A time field left showing the stored value (minutes only) keeps the
  stored instant. Errors re-render the form with `400`; a stale `version` is `409`. `/maintenance/{id}` shows status,
  times, affected services, description and notes; with `maintenance.manage` also the edit form (`POST /maintenance/{id}`),
  Cancel (scheduled), End now / Extend (in progress, via the scheduler so they apply at once), and for series the next
  10 occurrences with Skip and the skipped dates with Restore (`/maintenance/{series}/occurrence/{date}/skip|restore|edit`;
  a not-yet-stored occurrence's page edits it through its series). Notes: `/notes`, `/note/{id}`, `/note/{id}/delete`
  with `events.annotate`, only on stored windows. Order of checks: rejected host / hidden window `404`, then the
  permission (anonymous → `/login`, else `403`), then CSRF.
- "Upcoming maintenance" box (main page under the banner, service page under the header for that service): windows in
  progress or starting within `maintenance.upcomingDays`, with status, time range, affected services, and for managers
  Edit and Cancel (Skip for an occurrence) buttons. Banner/overall: DOWN wins, then "Maintenance in progress"
  (`overall: "MAINTENANCE"`, still `200`), then "Waiting for the first checks"; category summaries add
  "{m} of {n} in maintenance" and a `maintenance` count. `/api/status` adds a top-level `maintenance` array (the box's
  windows) and, per endpoint in a window, `maintenance: {id, title, start, end}`.
- Days: a day with a (not cancelled) window covering the service is blue when it has no unplanned downtime, even if
  nothing went down during the window (grey without data still wins); day JSON adds `"maintenance": true`. Future
  calendar days with planned maintenance are blue-outlined links to their day page, which now answers for future days
  that have maintenance (still `404` otherwise) and lists the windows ("Planned maintenance") apart from the events,
  also on past days. List view: maintenance events get a "Planned" badge; `?filter=all|outages|maintenance` (also on
  `/api/service/{id}/events`) filters before paging, and the paging links keep it.
- `/api/maintenance?from=&to=`: without both, `[now, now + 30 days)` echoed as instants; otherwise inclusive dates in
  the configured timezone (`from` defaults to today, `to` to `from` + 30 days), at most 400 days, else `400`. Windows by
  start with `id`, `title`, `description`?, `status` (`scheduled`/`in_progress`/`completed`/`cancelled`), `start`, `end`,
  `series`, `seriesId`/`occurrence` for occurrences and `affected` `[{id, name}]`; `/api/maintenance/{id}` (one-off,
  occurrence or series) adds the visible `notes`.
- OIDC/OAuth providers (app, `auth.ProviderConfig`, `auth.AuthProviders`): `id` uses the user-id pattern
  `[a-z0-9][a-z0-9._-]{0,63}` (so never a `:`), unique; `type` is `oidc` or `oauth2`; keys are checked per type
  (unknown-key errors, e.g. `identifyBy` on `oauth2` or `idField` on `oidc`). Common: `label` (default the id, ≤ 100
  characters), `clientId` (required), `clientSecret` (optional, for public clients), `scopes` (deduplicated; `oidc`
  default `openid email profile` and must include `openid`; `oauth2` default none), `sites` (non-empty list of hosts
  exactly as listed in some site's `hosts`, case-insensitive; the provider is offered only on the sites owning those
  hosts and never on the default site). `oidc`: `issuer` (kept exactly as written, since `iss` must equal it; no query
  or fragment) and `identifyBy` (default `sub`). `oauth2`: `authorizeUrl`, `tokenUrl`, `userinfoUrl` (query allowed),
  `idField` (required, a top-level userinfo field), `nameField` (optional) and `pkce` (default `true`; `oidc` always
  uses PKCE). Claim/field names are 1–256 printable characters without spaces. All provider URLs must be https, except
  `localhost`/`*.localhost`/`127.x.x.x`/`[::1]` (checked without DNS), with no credentials and no fragment. Every site
  a provider is offered on needs a callback base URL: the site's `publicBaseUrl`, else `server.publicBaseUrl`; an
  unrestricted provider also needs `server.publicBaseUrl` unless `unknownHosts` is `reject`. A provider that no user
  identity and no rule's `provider` mentions is a warning. `run` reports these with the other configuration errors.
  Identifier construction (`ProviderConfig.identify`): `oidc` takes the `identifyBy` claim, and for `email` only when
  `email_verified` is `true` (boolean, or the string `"true"`, which some providers send); `oauth2` takes `idField`. A
  value must be a string or a whole number (written without decimals, e.g. GitHub's `id`), non-empty, at most 512
  characters and without control characters; otherwise there is no identifier (sign-in fails). The identifier is
  `{providerId}:{value}`, value unchanged (no case folding).
- OIDC flow (app, `auth.OidcLogin`, `auth.Jwks`): `start` fetches discovery (`{issuer without trailing /}/.well-known/openid-configuration`,
  cached 1 h; its `issuer` must equal the configured one exactly; endpoints must be https, or http on loopback as for
  provider URLs) and returns the authorization URL plus a `Pending` (provider, 32-byte random `state`, `nonce` and PKCE
  verifier, redirect URI, start time) that the caller keeps in the visitor's session, single-use, valid 10 minutes.
  `finish` checks `state` (constant-time) before anything else, then a provider `error`, the RFC 9207 `iss` parameter
  when sent, and exchanges the code (PKCE verifier; `client_secret_basic` when the discovery document allows it, which
  is also its default, else `client_secret_post`; public clients send only `client_id`). The `id_token` is checked
  for: alg in RS256/384/512, ES256/384/512 (from the header, before any key lookup; a `crit` header is refused);
  signature via SimpleJWT against JWKS keys with the token's `kid` (or all suitable keys without one); `iss` exactly
  the configured issuer; `aud` containing the client id, `azp` equal to it when present and required with several
  audiences; `exp` and `iat` required, `nbf` honoured, all with 60 s skew; `nonce`; non-empty `sub`. JWKS is cached
  per URI (24 h), and fetched again when a token names an unknown `kid`, at most once per 30 s. JWKs with `use` other
  than `sig`, RSA keys under 2048 bits, curves other than P-256/384/521 and unparseable keys are skipped. HTTP: 10 s
  timeouts, no redirects, responses over 1 MiB refused. Failures are `OidcException`s with log-only messages.
  SimpleJWT 1.1 brings SimpleECDSA and BouncyCastle (`bcprov`), shaded unrelocated into the app jar. Worked around in
  SimpleJWT: `getHeaderValue` throws for a missing key (the header object is read instead), and ES verification
  accepts any even signature length (the length is checked against the curve first).
- OAuth 2.0 flow (app, `auth.OAuth2Login`): `start` builds the `authorizeUrl` request (its own query kept) with
  `response_type=code`, `client_id`, `redirect_uri`, `scope` (omitted when there are no scopes), a 32-byte random
  `state` and, when `pkce` is on, an S256 challenge; the `Pending` (provider, state, verifier or `null`, redirect URI,
  start time) is single-use and valid 10 minutes, as for OIDC. `finish` checks `state` (constant-time) first, then a
  provider `error` and the code, and exchanges it at `tokenUrl` with `client_secret_post` (no discovery to say what
  the provider supports, and GitHub/Discord accept it; public clients send only `client_id`), `Accept:
  application/json` (GitHub otherwise answers form-encoded) and the verifier when PKCE is on. A token response with
  an `error` member fails even with HTTP 200 (GitHub does that); `access_token` must be RFC 6750 token characters and
  `token_type`, when sent, `bearer` (any case). Userinfo is a `GET` with `Authorization: Bearer …`; the identifier is
  `ProviderConfig.identify` (`idField`), and a response without a usable value fails with
  `userinfo has no usable "…"`; `nameField` gives an optional display-name hint. Same HTTP rules and `OidcException`
  (log-only messages) as the OIDC flow, sharing its helpers; requests send `User-Agent: HealthChecker` (GitHub's API
  requires one).
- Identity resolution and provider sign-in pages (app, `auth.Rule`, `auth.SignInMatch`, `auth.ExternalLogin`,
  `UserDirectory.resolve`, `web.page.ProviderLoginPage`): the identifier is looked up in the users' `identities` first
  (an explicit user always wins, and a disabled one gets the disabled page even when a rule would match), then
  `auth.rules` in order. Rule keys: `provider` and `role` (both must be known), plus at least one condition, so a rule
  never admits everyone with an account at a provider: `emailDomain` (exact domain after the last `@`,
  case-insensitive, no subdomains, only with `email_verified` true, and only on `oidc` providers, since plain OAuth 2.0
  userinfo doesn't say whether an address is verified) and/or `group` (the `groups` claim/userinfo field is that string
  or an array containing it); anything else is an unknown-key error. A rule-granted session stores the identifier as
  its user id (it contains a `:`, so it can't clash with a user id), the rule's key (provider, conditions, role name)
  and the provider's display name (`nameField`; OIDC `name`, else `preferred_username`; else the identifier); on each
  request it's valid only while a rule with the same key exists and no user lists the identifier, so a config change
  ends it at restart. `GET /auth/start/{id}?next=` keeps the started flow (`ExternalLogin` stores the flow's `Pending`
  as JSON) and `next` in the visitor's session (one write, so only one session is started) and answers `303` to the
  provider; `switch=1` (the "Sign in with a different account" link) adds `prompt=select_account` for OIDC. The
  callback URL is `site.link("/auth/callback/{id}")`; a provider is offered on a site (button, start, callback) only
  when it's available there and the site has a base URL, else `404`. A sign-in started on one site
  can't be finished on a site where the provider isn't offered (`404`).
  `GET /auth/callback/{id}` takes the pending flow out of the session first (single use), then: allowed → new session
  (`resetSession`), `303` to `next`; unknown or disabled → the session is deleted (so none is left, not even someone
  else's on that browser) and a `403` page shows the provider label, the name/email hint, the identifier in a `<code>`
  with a Copy button (shown only when the Clipboard API exists), the different-account link, and nothing about users,
  roles or rules; a flow failure or no usable identifier (e.g. `identifyBy: email` with an unverified address) → a
  short `400` page, details only in the log. Rejected attempts are logged and recorded as `login.rejected` (id = the
  identifier, user `null`; `after`: provider, identifier, displayName, email, ip, reason `unknown`/`disabled`), at most
  5 per identifier per hour (in memory); the page is shown every time. `/login` shows a "Sign in with …" link per
  offered provider (`rel=nofollow`, carrying `next`); with `auth.local.enabled: false` the password form is left out,
  and with neither, the page says no sign-in method is available.
- `NotificationDispatcher` (app, package `notify`): routing uses `EndpointDefinition.notifyChannels()` as the config
  loader resolved it (omitted = `defaults.notify`, `false`/`[]` = none), so the dispatcher only drops unknown names.
  `send(channels, notification)` bypasses routing (announcements, tests) and rejects unknown names. The first
  notification for a channel opens a fixed window (`batchWindow`, from first to send, not sliding); everything raised
  for that channel until it closes becomes one `NotificationBatch` with a random UUID kept across retries. Per channel,
  batches are delivered one at a time in order (a batch being retried holds later ones back, so a recovery never
  overtakes its down notice), on virtual threads fed by one timer thread (`TaskScheduler`, replaced by a manual clock
  in tests). `Notifier.send` throws `NotifierException` (`permanent`, optional `retryAfter`); an unexpected
  `RuntimeException` counts as temporary. Backoff (`RetryPolicy.DEFAULT`): 10 s doubling, capped at 10 min, given up
  after 10 attempts; a receiver's `retryAfter` is used when longer. Every failure is logged (`WARNING` while retrying,
  `ERROR` when given up) and kept as the channel's last error with its time (`ChannelStatus`, which also has the last
  success and the pending count); a later success doesn't clear it. `Notification.forChange` maps a `StateChange`:
  any change to DOWN → down, DOWN → UP → recovered, otherwise nothing. Parsing `notifications.batchSeconds`, the
  `StatusRegistry` listener and `Daemon` wiring come with the first notifier type (`DiscordNotifier`), since until then
  no channel can be built; `close()` drops pending messages for now (M16 adds the bounded flush).
- Reminders (app, `notify.ReminderScheduler`): reminder `k` is due at `reminders[k]` minutes after the outage start
  (the failure streak's first failure); past the list, at `last + n × reminderRepeatMinutes` when that is above 0. An
  empty `reminders` list disables reminders, repeat included. The endpoint's current definition is read every time the
  next reminder is planned (`refresh(id)` re-plans after an edit). Slots already past when DOWN is declared (a slow
  failure threshold) are skipped, since the down notification covers them. When several slots are due at once (only
  after a restart), one reminder is sent and every one of those slots counts as used. `data/notifications/state.json`
  is `{"reminders":[{"endpointId","outageStart","sent"}]}`, rewritten atomically on every change and saved before the
  reminder is queued; an unreadable file is logged and ignored, entries for endpoints that no longer exist are dropped.
  State changes: any change to DOWN starts reminders, any change to UP (including UNKNOWN → UP after a restart) cancels
  them. After a restart, the first UNKNOWN → DOWN continues a restored entry (same outage start, nothing re-sent)
  instead of starting over. Like the dispatcher, the `StatusRegistry` listener, the `cancel(id)` on endpoint removal or
  replacement (`endedByConfigChange`) and the `Daemon` wiring come with `DiscordNotifier`. Reminders are sent with
  `NotificationDispatcher.notify`, so they follow the endpoint's routing and batching.
- Channels and `DiscordNotifier` (app, `notify.NotificationConfig`, `notify.DiscordNotifier`): `notifications` keys are
  `batchSeconds` (whole number 0–3600, default 30; 0 sends at once) and `channels`; unknown keys are errors. A channel
  needs `type` `discord` or `email`. `discord` keys: `webhookUrl` (required; https, or http on localhost; no credentials
  or fragment; a query such as `thread_id` is kept), `username` (1–80 characters; not containing "discord"/"clyde",
  which Discord rejects) and `mention` (role `<@&id>`, user `<@id>`/`<@!id>`, `@everyone`, `@here`, several separated by
  spaces). `email` channels (until M15 accepted with a warning and failing) are described below. `run` reports these with
  the other configuration errors; error messages never echo the webhook URL. Embeds: title "{name} is down" (red) /
  "is still down" (orange) / "recovered" (green), "Test notification" (grey), announcements blue; the description has
  "Down since" / "Down for 1h, since" / "Back up after 2h 14m" with Discord `<t:…:f>` timestamps (shown in each reader's
  timezone) and "Latest failure: …"; `timestamp` is the notification time and `url` the event permalink (only when
  absolute). Names and messages are markdown-escaped and truncated (title 256, message 1000). A message holds at most
  10 embeds and 6000 characters of embed text; bigger batches are split in order, and a retry of the same batch
  continues with the part that failed (the notifier remembers how many parts of that batch id were accepted), so no
  part is posted twice. The mention is the content of the first message with a down or reminder embed only;
  `allowed_mentions` is on every message (`parse: []` plus exactly the configured roles/users, `everyone` only when
  configured), so nothing else in a message can ping. `2xx` success; `429` → retry after the body's `retry_after`
  (else the `Retry-After` header), used when longer than the backoff; `5xx`, timeouts and connection failures → retry;
  other statuses permanent with Discord's `code`/`message` in the error. 10 s timeouts, no redirects.
- Notification wiring (`Daemon`): the scheduler's check listener records history first, then turns a `StateChange` into
  a down/recovered notification (`Notification.forChange`) for the endpoint's routing and feeds `ReminderScheduler`;
  links use `SiteResolver.siteFor` with the effective layout and point at the event the change opened (down) or closed
  (recovered), or are left out without a public base URL. Reminder targets read the open event's last failure message
  and permalink. `stopped` (removed/replaced) cancels reminders; an in-place update re-plans them on a virtual thread
  (registry listeners hold locks the planning also takes). Reminders are loaded after the history heartbeat starts;
  `close()` stops reminders and the dispatcher after the scheduler.
- "Send test notification" (`web.page.NotificationsPage`): `GET /admin/notifications` lists channels (name, type) with a
  test button each; last delivery, last error and pending count only with `view.private` (channel errors are private).
  `POST /admin/notify-test` (`channel`, `csrf`): `notifications.test` (anonymous → `/login`, else `403`), then CSRF, then
  an unknown channel is `400`; 5 tests per user per minute (`429` + `Retry-After`). It queues the test with
  `NotificationDispatcher.sendNow` (its own batch, no batch window, same retry rules), then `303` to the list with a
  "queued" notice. The main page shows a "Notifications" link to users with `notifications.test`. Tests aren't audited.
- Maintenance and notifications (app, `notify.OutageAlerts`, `maintenance.MaintenanceAnnouncer`): `MaintenanceScheduler`
  listeners hear, per tick, every endpoint entering or leaving a window (with the transition time) and the tick itself.
  `OutageAlerts` sits between state changes and the dispatcher/reminders: while an endpoint is in a window, changes
  send nothing and start no reminders; entering a window cancels pending reminders (and the reminder source returns
  nothing for endpoints in maintenance, so none are restored after a restart: the scheduler now starts before
  reminders are loaded). Leaving a window while the checks say DOWN sends a down notification at once, with the
  outage starting at the window's end ("End now": at that moment), and starts reminders from there; a DOWN change
  racing that is not sent twice. A recovery is only sent for an outage a down notification went out for, and gives
  that outage's start. `maintenance.announceBeforeHours` (whole number 0–720, default 0 = off) is a global setting,
  not per window. Announcements (`Kind.ANNOUNCEMENT`, title "Maintenance scheduled/Upcoming maintenance/Maintenance
  started/ended/cancelled: {title}", text with the time range in the configured timezone, the affected service names
  and, for created/upcoming, the description; link to `/maintenance/{id}` on the default site when absolute) go only
  to the window's `notifyChannels` via `NotificationDispatcher.send` (channels since removed from the config are
  skipped). They're checked on every scheduler tick, so "upcoming" may be up to 30 s late. Each goes out once and
  only for things that happen while the app runs (event time at or after startup and at most 1 h before the tick;
  in memory, nothing persisted), so a restart neither repeats nor catches up announcements. A series is announced
  when created and cancelled; its occurrences get upcoming/started/ended; skipping or editing a single occurrence
  isn't announced. "Upcoming" is left out when the window was created after that moment; a window created and
  cancelled between two ticks isn't announced at all.
- `EmailNotifier` (app, `notify.EmailNotifier`): `email` channel keys are `baseUrl` (required; https, or http on
  localhost; no credentials, query or fragment; a path prefix is kept, a trailing slash ignored), `apiKey` (required,
  no spaces or control characters; never echoed or logged, left out of the record's `toString`), `from` (required),
  `to` (non-empty list) and `requireTls` (boolean, default false); unknown keys are errors. Addresses are only checked
  loosely (contain `@`, no control characters, at most 320 characters); SimpleMailer validates the rest. The request
  is `POST {baseUrl}/v1/messages` with `Authorization: Bearer`, `{id, from, to, subject, text, html, requireTls}`,
  where `id` is the `NotificationBatch` id (stable across the dispatcher's retries) and the body depends only on the
  batch, so a repeat is identical and SimpleMailer answers `200` rather than `409`. Subject: one notification gives
  "[HealthChecker] Website down" / "Admin still down (1h)" / "Shop recovered after 2h 14m" / "Test notification" /
  the announcement headline; several give counts in kind order ("2 down, 1 still down, 1 recovered, 1 test
  notification, 2 announcements"); control characters become spaces and it is cut at 200 characters. Bodies: per
  notification a heading like the Discord title, "Down since" / "Down for 1h, since" / "Back up after 2h 14m (down
  since …)" with times as "Thu 1 Jan 2026 12:00 (Zone/Id)" in the configured timezone (email can't localise them),
  "Latest failure: …" (cut at 2000 characters), the free text, and the link ("Details: …" in text, an `<a>` in HTML,
  only for absolute http(s) links); HTML is a simple inline-styled page with a coloured left border per kind
  (red/orange/green/blue/grey) and everything escaped with `Html.escape`. Responses: `2xx` success; `5xx`, `408`,
  `429` (its `Retry-After` seconds honoured), timeouts and connection failures retried; `409` logged at `ERROR` as a
  bug and permanent; `400`, `401` and every other status permanent, with SimpleMailer's `error`/`message`/`errors`
  in the channel error. 30 s request timeout, the same no-redirect HTTP/1.1 client as Discord.
  `NotificationConfig.notifiers` takes the timezone (`Daemon` passes `config.timezone()`).
- Shutdown (`Daemon.close`, run by the JVM shutdown hook): a history heartbeat (coverage on disk up to now), then the
  maintenance and check schedulers stop (in-flight checks get the 1 s grace period, then every checker is closed),
  `HistoryStore.close` writes coverage up to that moment, reminders stop, pending notifications are flushed for at
  most 10 s (`Daemon.NOTIFICATION_FLUSH_TIMEOUT`), and the web server stops last, so the dashboard answers until then.
  Each step is isolated (a failing one is logged, the rest still run); a second `close()` (the hook racing `run`)
  waits for the first. The flush (`NotificationDispatcher.close(Duration)`): no new notifications are accepted, open
  batch windows close at once, a batch waiting for a retry is tried at once, failures are no longer retried, and
  whatever is still undelivered at the deadline is dropped with a `WARNING` per channel (a delivery in progress is
  abandoned). `close()` without a timeout still drops everything pending. Worst case is about 13 s, well within
  systemd's default 90 s stop timeout.
