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
    // builder-style factories: SettingSpec.string("host", "Host").required().help("…") …
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
    "countsAsDowntime": false, "maxLengthDays": 7, "upcomingDays": 7
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
- [ ] Add `.DS_Store` to `.gitignore`.
- [ ] Convert to multi-module: `HealthChecker-Parent` (root), `HealthChecker-API` (`api/`), `HealthChecker` (`app/`, move `Main`); shading in app only, Gson not relocated. Add Gson and JUnit 5 + surefire. **Tests:** a trivial smoke test so `mvn test` runs in both modules.
- [ ] `ConfigLoader`: server, dataDir, timezone, dashboard, defaults, sites, categories, endpoints (common fields incl. `id`, `category`, `reminders`), `${env:…}` substitution, collected error reporting. **Tests:** loading a full config; defaults applied; each validation rule; all errors reported together; env substitution and unresolved `${env:…}`.
- [ ] `Main` with `run` (default) and `hash-password` subcommands (hasher is a stub until M9); `config.example.json` in repo root. **Tests:** `config.example.json` loads without errors.

### M2 — Plugin API & loading
- [ ] API module: `CheckPlugin` (incl. `displayName`, `settings`, `apiVersion`), `SettingSpec`, `Checker`, `CheckResult`, `EndpointConfig` (incl. `declareTarget`), `ConfigException` (with optional setting key). **Tests:** `CheckResult`/`SettingSpec` builders.
- [ ] `EndpointConfig` implementation over `JsonObject` with unknown-key detection. **Tests:** typed getters; missing required key; wrong type; unread keys reported.
- [ ] `PluginManager`: built-ins + `plugins/*.jar`, duplicate/unknown type errors, version check. **Tests:** load a test plugin jar built during the test from a temp dir (using Gson from the parent loader); duplicate types; unknown type message lists available types; too-new API rejected.

### M3 — Built-in `http` check
- [ ] `HttpCheckPlugin`: shared `HttpClient`, `Redirect.NEVER`, timeout, `maxClass` rule, URL validation, declared targets. **Tests:** against a local SiggiHTTP server, the 200/301/404/503 × `maxClass` 2–5 matrix; refused and timeout always fail; redirect not followed; `maxClass` 1 and 6 rejected; invalid URLs (path, query, scheme) rejected.

### M4 — Scheduling & live state
- [ ] `StatusRegistry`: `failureThreshold` state machine, failure streak start time, recent checks (last 100), `StateChange` events, transition logging. **Tests:** thresholds 1 and 3; streak start time; UNKNOWN → UP/DOWN on first result; recent checks capped at 100.
- [ ] `EndpointRegistry` with runtime add / update-in-place / replace-checker / remove; `CheckScheduler` follows it (per-endpoint schedule, jitter, virtual threads, enforced timeout, skip-if-stuck). **Tests:** plugin that throws; hangs; ignores interrupts (skip-if-stuck); endpoint added/replaced/removed while running (old checker closed, no stray runs).

### M5 — History store & uptime
- [ ] `HistoryStore`: coverage periods with minute heartbeat; events (open/close, `start` = first failure of streak); yearly JSONL files; atomic writes; load at startup. Restart handling: close open events as `endedUnobserved`; coverage gap while app was down. **Tests:** write/read round trip; restart while down; restart while up leaves a gap; crash mid-write leaves the previous file intact.
- [ ] `UptimeCalculator`: exact downtime ∩ coverage for 24 h, 60 d and any day in the configured timezone; "no data" handling; never round up to 100 %; per-day cache. **Tests:** event across midnight split between days; DST transition day (23 h / 25 h); no-coverage days; partial coverage; rounding (99.995 % → 99.99 %); cache invalidated on event change.

### M6 — Public main page & status API
- [ ] `WebServer` on SiggiHTTP: port/bind, responders, `listen()` thread, `trustedProxies`, static CSS/JS; `GET /health`. **Tests:** `/health` 200; `X-Forwarded-For` honoured only from trusted proxies.
- [ ] `ViewFilter` (allow-list copy) for `public` / `public-status` / `private`; targets never anonymous; leak filter omits entries containing declared targets. **Tests:** private endpoints absent; `public-status` fields exactly as in the visibility table; leaking message/detail omitted without placeholder while other details remain.
- [ ] `LayoutStore` (read side) and category grouping: default layout from config, merged with `data/layout.json` if present; implicit "Other"; flat list with no categories; hide categories with no visible services; heading summary. **Tests:** default ordering from config; saved layout overrides order and category; new config entries appended; stale ids ignored; uncategorized last by default; category with only private services hidden from anonymous.
- [ ] Sites: Host / trusted `X-Forwarded-Host` matching (exact, wildcard, port stripped), default site, `unknownHosts: "reject"`, scope filter applied before visibility, per-site title and base URL. **Tests:** scoping on main page and API; wildcard and port handling; unknown host default vs reject; `X-Forwarded-Host` ignored from untrusted IPs; links never built from the Host header.
- [ ] Main page and `GET /api/status`: overall banner, category sections, rows with state dot, name, 24 h / 60 d %, 60-segment day bar with configurable colours, tooltips, links; responsive down to 320 px; light/dark. **Tests:** overall state and 200/503 from visible endpoints only; bar has 60 segments with correct colours for fixture data; no declared target anywhere in anonymous HTML or JSON (search output for every configured URL/host).

### M7 — Service page: list & calendar views
- [ ] `/service/{id}` header (state, uptimes, bar, recent pass/fail strip); `404` for private when anonymous or out of site scope. **Tests:** 404 cases; header fields per visibility.
- [ ] List view (default): paginated events, newest first, durations, expandable notes (public only when anonymous), event permalink page. **Tests:** paging; duration formatting; anonymous sees only public notes.
- [ ] Calendar view: 3 months per page (current + 2 previous on page 0), Earlier/Later paging, colour-only day blocks, Monday-first grid. **Tests:** page 0/1/2 month ranges; year rollover; no "Later" on page 0; days before first coverage grey.
- [ ] Day popover (JS) and `/service/{id}/day/{date}` fallback page: uptime, downtime, events clipped to the day; `?day=` deep link from the main-page bar. **Tests:** event spanning two days shown on both, clipped; `?day=` opens the right page.
- [ ] JSON: `/api/service/{id}`, `/events`, `/days`, `/event/{eventId}`. **Tests:** same visibility and scope rules as the HTML; `/days` range limit.

### M8 — MinecraftChecker plugin (repo `plugin-projects/MinecraftChecker`)
- [ ] `mvn install` HealthChecker so `HealthChecker-API` is in the local repo. Plugin project: pom (`io.siggi:MinecraftChecker`, API + Gson `provided`), `META-INF/services` entry, `.gitignore`, README. **Tests:** smoke test that the plugin is found by `ServiceLoader`.
- [ ] Server List Ping: VarInt framing with packet size cap, handshake (original host), status request/response, MOTD flattening, optional ping. **Tests:** against a fake in-process status server: valid response; chat-component MOTD; garbage; oversized packet; timeout.
- [ ] SRV lookup via JNDI DNS when `port` unset (priority/weight ordering, fallback on connect failure, `srv: false`); declared targets incl. resolved target. **Tests:** with an injected resolver: ordering, weighted choice, fallback to next record, no record → 25565, `port` set skips SRV.
- [ ] Settings `host`, `port`, `srv`, `minOnline`, `expectVersionContains` with validation (per-field `ConfigException`s), `settings()` specs and `displayName()`. **Tests:** each invalid setting reports its key; too few players fails; version mismatch fails.
- [ ] Integration check: build the plugin jar, copy it into a temp `plugins/` dir, start HealthChecker against a fake status server, and confirm via `/api/status` that the endpoint goes UP. Script it as `scripts/it-minecraft.sh` in the plugin repo.

### M9 — Sessions, built-in login, roles & permissions
- [ ] Sessions via `SessionsBuilder` (no deprecated session API), cookie flags, ID rotation with `resetSession`, sliding expiry, CSRF tokens. **Tests:** cookie flags (`HttpOnly`, `SameSite=Lax`, `Secure` over HTTPS); anonymous page views set no cookie; ID changes on login; expiry; logout clears the cookie; CSRF rejection.
- [ ] PBKDF2 hashing + `hash-password` subcommand. **Tests:** hash/verify round trip; wrong password; malformed hash string.
- [ ] `/login` (form, constant-time, dummy hash for unknown users), `/logout` (POST + CSRF), rate limiter. **Tests:** success; wrong password; unknown user; rate limit per IP and per username; logout requires CSRF.
- [ ] Users, roles and permissions: built-in `viewer`/`editor`/`admin`, custom roles, `disabled`, permission lookup per request; one `Permission` helper used by every responder. Logged-in view gated by `view.private`; header with user, role and logout. **Tests:** role expansion; unknown permission rejected at config load; disabled user (incl. existing session ended); user without `view.private` sees the anonymous view; private endpoint 404 without `view.private`.

### M10 — Dashboard editing: layout, event titles & notes
- [ ] Layout editor on the main page (drag and drop plus up/down buttons and category dropdown; private services with visibility markers) and `POST /admin/layout` (CSRF, version check → 409, "Reset to config order", atomic write). **Tests:** save and reload; move service between categories; reorder categories incl. "Other"; conflicting save gets 409; no `layout.edit` → 403; unknown ids rejected; reset restores config order.
- [ ] Rename event (empty = reset to default). **Tests:** rename visible publicly; reset; no `events.annotate` → 403.
- [ ] Notes: add / edit / delete; public/private toggle; author and timestamps; length limit; forms in list view, event page and day popover; all POST + CSRF; persisted before responding. **Tests:** private note hidden from anonymous in HTML and JSON; edits survive restart; HTML escaping of note text; length limit.

### M11 — Endpoint & category management in the dashboard
- [ ] `EndpointStore`: merge `config.json` (read-only) and `data/endpoints.json` (editable); duplicate-id startup error; disabled entries for ones failing validation; versioned atomic writes. **Tests:** merge; duplicate id error; disabled entry when its plugin is missing; stale-version write → 409.
- [ ] Endpoint editor: common fields, type dropdown, type-specific form generated from `settings()`, field-level errors, no-JS two-step fallback, read-only view with "config" badge. **Tests:** form rendered from a test plugin's `settings()`; plugin validation error shown on its field; config-defined endpoint can't be edited via POST; no `endpoints.manage` → 403.
- [ ] Save applies live via `EndpointRegistry` (in-place vs replace-checker rules; `endedByConfigChange`; reminder cancellation). **Tests:** create → checked within one interval; name edit keeps state and open event; host edit replaces checker and closes event as `endedByConfigChange`; no target leaks into public pages after edits.
- [ ] "Test now" (no save, rate-limited). **Tests:** returns result without persisting; rate limit.
- [ ] Delete with confirmation; keep or delete history (`history.delete`); block id reuse while history exists. **Tests:** history kept by default; deleted with permission; id reuse blocked.
- [ ] Category editor in the layout editor (add/rename/delete dashboard categories; delete moves services to "Other"), and "Copy to dashboard" for config-defined endpoints/categories. **Tests:** delete moves services; config categories read-only; copy produces an equivalent entry and the removal instructions.
- [ ] `AuditLog` (`data/audit.jsonl`) for all dashboard edits incl. layout, events and notes; `/admin/audit` page (`audit.view`). **Tests:** each edit type writes an entry with before/after; secrets masked; page requires `audit.view`.

### M12 — Scheduled maintenance
- [ ] `MaintenanceStore`: windows (endpoints and/or categories, start/end, title, description, notes, `notifyChannels`), validation, versioned atomic writes, audit entries. **Tests:** cannot create in the past or longer than the limit; `end` > `start`; stale save 409; audit entries.
- [ ] Recurrence: daily / weekly / monthly rules with `until`, DST-safe expansion in the configured timezone, skip/edit single occurrence, materialise occurrences when they start. **Tests:** weekly recurrence across a DST change keeps local time; "first Sunday" monthly; skip and edit one occurrence; editing a series doesn't change past occurrences.
- [ ] `MaintenanceScheduler`: start/end transitions; "End now" and "Extend"; affected endpoints show state `MAINTENANCE`. History integration: DOWN during a window → `kind: maintenance` event; split at window end into a normal `outage`; uptime excludes maintenance downtime unless `countsAsDowntime`. **Tests:** with a fake clock: transitions; end now; extend; down inside window → maintenance event, no outage; down past window end → outage starting at window end; uptime with and without `countsAsDowntime`.
- [ ] Dashboard and JSON: "Schedule maintenance" form (main page and service page), `/maintenance/{id}` page, edit/cancel; "Upcoming maintenance" box; blue day segments and calendar outlines; "Planned" badge and All/Outages/Maintenance filter; popover lists maintenance separately; `/api/maintenance`, `/api/maintenance/{id}`, maintenance state in `/api/status`. **Tests:** anonymous sees windows only for visible endpoints, with a filtered affected list; window with no visible endpoints hidden; no `maintenance.manage` → 403.

### M13 — OIDC / OAuth 2.0 login
- [ ] Provider config + validation; `identifyBy` (`sub` default, verified `email`, other claim; `idField` for oauth2); identifier format `{providerId}:{value}`. **Tests:** config validation; identifier construction per mode.
- [ ] OIDC: discovery, PKCE, state, nonce, code exchange, JWKS cache, `id_token` verification with SimpleJWT. **Tests:** with an in-process fake OIDC provider (SiggiHTTP + test RSA key): success; bad signature; wrong `aud`/`iss`; expired; nonce/state mismatch; `alg: none` rejected; unknown `kid` triggers a JWKS refresh.
- [ ] Generic `oauth2`: code flow (+PKCE where supported), userinfo, `idField`/`nameField`. **Tests:** fake OAuth2 provider: success; userinfo missing `idField` fails cleanly.
- [ ] Identity → user lookup, then rules (first match); "send this identifier to the admin" page (copy button, different-account link, no session); rejected attempts logged and audited, rate-limited; login page with provider buttons; local form hidden when disabled. **Tests:** unknown identity shows its exact identifier and creates no session; `email` identifier ignored when not verified; rule grants role; explicit user beats rule; disabled user gets the disabled page; rejected attempt appears in the audit log.
- [ ] Per-site callback URLs from the site's `publicBaseUrl`; provider `sites` restriction. **Tests:** callback URL per site; provider hidden and rejected on sites it isn't enabled for.

### M14 — Notifications: framework, reminders & Discord
- [ ] `NotificationDispatcher`: `notify` routing (`false`/`[]` off, omitted = defaults), batching, async retry/backoff, last-error tracking. **Tests:** routing cases (`false`, omitted, defaults `false` + opt-in); batching window combines; retry with backoff; last error recorded.
- [ ] Reminders: schedule from outage start per `reminders` (+ optional `reminderRepeatMinutes`); cancel on recovery; persisted in `data/notifications/state.json`; overdue-after-restart sent once. **Tests:** with a fake clock: reminders at 30/60/120 and none after; none after recovery; repeat interval; restart mid-outage sends the overdue one once.
- [ ] `DiscordNotifier`: embeds (down/reminder/recovered), splitting, `allowed_mentions`, 429 handling; "Send test notification" (`notifications.test`). **Tests:** fake webhook server: payload shape; >10 embeds split; mention only on down/reminder; 429 then success; 4xx permanent; test button requires permission.
- [ ] Maintenance: suppress down/reminder/recovery for affected endpoints during a window; down at window end if still down; announcements (created, `announceBeforeHours`, started, ended, cancelled) to the window's `notifyChannels`. **Tests:** no alerts during maintenance; alert at window end if still down; announcements go to window channels only.

### M15 — Email via SimpleMailer
- [ ] `EmailNotifier`: `POST /v1/messages`, stable UUID per notification across retries, response-code handling; text + HTML bodies, subject summary, event permalink. **Tests:** fake SimpleMailer API: 202; retry after 503 reuses the same id; 401 and 400 permanent; payload shape; HTML escaping of endpoint names and messages.

### M16 — Packaging & ops
- [ ] Shutdown hook: final coverage heartbeat, stop scheduler, flush pending notifications (bounded), close checkers, kill HTTP server. **Tests:** shutdown closes checkers and writes final coverage within the time limit.
- [ ] `mvn package` → runnable app jar; verify by starting it with `config.example.json`-derived test config and the MinecraftChecker jar in `plugins/`, checking `/health`, `/api/status` and `/`, then stopping it cleanly.
- [ ] README: build, configure (`maxClass`, visibility, roles, history, auth, notifications, reminders, maintenance, sites), run, reverse proxy notes, backup of `data/`; sample systemd unit.
- [ ] `PLUGINS.md`: writing a plugin (API, `settings()` / `SettingSpec`, `declareTarget`, Gson contract, services file, threading/timeout rules, minimal example, pointer to MinecraftChecker).

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
