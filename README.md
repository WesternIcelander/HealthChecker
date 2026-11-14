# HealthChecker

A small, self-hosted Java daemon that checks your services on an interval, records
every outage, and serves a public status page.

- **Checks**: HTTP is built in; every other check type is a plugin jar dropped into
  `plugins/` (for example [MinecraftChecker](https://github.com/WesternIcelander/MinecraftChecker)).
- **History**: each outage is a downtime event with start, end and duration. Uptime is
  shown for the last 24 hours, the last 60 days, and per calendar day.
- **Dashboard**: services grouped into ordered categories, a 60-day bar per service,
  per-service list and calendar views, scheduled maintenance, several status sites on
  different hostnames. Every endpoint decides what anonymous visitors may see.
- **Login**: built-in username/password and/or OIDC / OAuth 2.0, with roles and
  permissions. Signed-in users can rename events, add notes, schedule maintenance,
  edit the layout, and manage endpoints, depending on their role.
- **Notifications**: down, still-down reminders and recovery, by email (through a
  SimpleMailer instance) and Discord webhooks, routed per endpoint.

No database: everything lives in plain files under `data/`.

## Contents

- [Build](#build)
- [Run](#run)
- [Configure](#configure)
  - [Server](#server)
  - [Endpoints and the `http` check](#endpoints-and-the-http-check)
  - [Visibility](#visibility)
  - [Categories and layout](#categories-and-layout)
  - [History and uptime](#history-and-uptime)
  - [Authentication](#authentication)
  - [Roles and permissions](#roles-and-permissions)
  - [Notifications](#notifications)
  - [Reminders](#reminders)
  - [Maintenance](#maintenance)
  - [Sites](#sites)
- [Reverse proxy](#reverse-proxy)
- [Running as a systemd service](#running-as-a-systemd-service)
- [Backing up `data/`](#backing-up-data)
- [Security notes](#security-notes)
- [Writing plugins](#writing-plugins)

## Build

Requirements: Java 21 and Maven.

```sh
mvn package          # builds and tests everything
mvn install          # also installs HealthChecker-API into ~/.m2, needed to build plugins
```

The runnable jar is `app/target/HealthChecker-1.0-SNAPSHOT.jar`. It's self-contained
(SiggiHTTP, Gson, SimpleJWT and their dependencies are shaded in). Copy it wherever you
like, for example as `/opt/healthchecker/HealthChecker.jar`.

Plugins are built in their own projects, after `mvn install` here. For example:

```sh
cd plugin-projects/MinecraftChecker   # or wherever you cloned it
mvn package
cp target/MinecraftChecker-1.0-SNAPSHOT.jar /opt/healthchecker/plugins/
```

## Run

```sh
java -jar HealthChecker.jar [run] [config.json]   # start the daemon (default: ./config.json)
java -jar HealthChecker.jar config.json           # same as "run config.json"
java -jar HealthChecker.jar hash-password         # read a password, print its hash
java -jar HealthChecker.jar --help
```

- `dataDir` (default `data`) and `plugins.dir` (default `plugins`) are relative to the
  **working directory**, not to the config file. Start it from the directory that
  should hold them, or use absolute paths.
- The whole config is validated at startup. All errors are printed together, each with
  its path (e.g. `endpoints[2] "survival": unknown type "minecraft" (available: http)`),
  and the process exits with status `1`. Warnings are printed but don't stop startup.
  Bad command-line arguments exit with status `2`.
- On success it prints `Listening on bind:port` and runs until it gets `SIGTERM` or
  `SIGINT`. Shutdown writes final coverage, closes checkers, flushes pending
  notifications for up to 10 s, then stops the web server (about 13 s at worst).
- `GET /health` answers `200 ok` as long as the web server is up, on any hostname, which
  makes it suitable for a load balancer or container health check.
- Config changes need a restart. Endpoints, categories, layout and maintenance edited in
  the dashboard apply immediately.

## Configure

The config is a strict JSON file (no comments, no trailing commas).
[`config.example.json`](config.example.json) uses every section; start from a copy of
it. Unknown keys are errors, which catches typos.

**Secrets from the environment**: `${env:NAME}` works inside any string value, e.g.
`"clientSecret": "${env:GOOGLE_CLIENT_SECRET}"`. A variable that isn't set is a
startup error. Keep API keys, webhook URLs and client secrets out of the config file
this way (the example config even takes the admin password hash from the
environment, so it only loads when those variables are set).

### Server

```json
"server": {
  "port": 8080, "bind": "0.0.0.0",
  "publicBaseUrl": "https://status.example.com",
  "trustedProxies": ["127.0.0.1", "::1"],
  "unknownHosts": "default"
},
"dataDir": "data",
"timezone": "Atlantic/Reykjavik",
"plugins": { "dir": "plugins" },
"dashboard": {
  "title": "Status",
  "colors": { "yellowBelow": 100.0, "orangeBelow": 99.0, "redBelow": 95.0 }
}
```

| Key | Default | Meaning |
|-----|---------|---------|
| `server.port`, `server.bind` | `8080`, `0.0.0.0` | where the HTTP server listens. Behind a reverse proxy, bind to `127.0.0.1`. |
| `server.publicBaseUrl` | none | the external URL. Used for links in notifications, redirects and OIDC callbacks. Required with OIDC/OAuth providers. Never derived from the `Host` header. |
| `server.trustedProxies` | `[]` | IPs or CIDR ranges whose `X-Forwarded-For`/`-Proto`/`-Host` headers are believed. See [Reverse proxy](#reverse-proxy). |
| `server.unknownHosts` | `default` | `reject` answers `404` for hostnames that match no [site](#sites). |
| `timezone` | system zone | IANA zone (`UTC` works, bare offsets don't). Decides where days begin for bars, calendar and per-day uptime. Timestamps are stored in UTC. |
| `dashboard.colors` | as above | day colours: yellow below `yellowBelow` (any downtime), orange below `orangeBelow`, red below `redBelow`. |

### Endpoints and the `http` check

```json
"defaults": {
  "intervalSeconds": 30, "timeoutMillis": 5000, "failureThreshold": 1,
  "visibility": "private",
  "notify": ["ops-email", "discord"],
  "reminders": [30, 60, 120]
},
"endpoints": [
  { "id": "website", "name": "Website", "type": "http", "category": "web",
    "url": "https://example.com", "maxClass": 2,
    "visibility": "public", "notify": ["ops-email"] }
]
```

Common keys, on every endpoint (anything left out comes from `defaults`):

| Key | Range / default | Meaning |
|-----|-----------------|---------|
| `id` | `[a-z0-9-]+`, required, unique | stable identity. History is stored under it and it appears in public URLs (`/service/{id}`), so **don't put a hostname in it**. |
| `name` | required, unique | display name; can change freely. |
| `type` | required | `http`, or a type provided by a plugin. |
| `category` | optional | a category id, see [Categories](#categories-and-layout). |
| `visibility` | `private` | see [Visibility](#visibility). |
| `intervalSeconds` | 1–86400, `30` | time between checks. |
| `timeoutMillis` | 100–300000, `5000` | per-check timeout, enforced by the app even if a plugin ignores it. |
| `failureThreshold` | 1–100, `1` | consecutive failures before UP becomes DOWN. The outage starts at the first of them. |
| `notify`, `reminders`, `reminderRepeatMinutes` | | see [Notifications](#notifications) and [Reminders](#reminders). |

Every other key is a type-specific setting, checked by that type.

**`http`** sends `GET {url}/` (redirects are not followed, so a 3xx is observable) and
passes when a response arrives and `statusCode / 100 <= maxClass`:

| `maxClass` | Passes on | Fails on |
|-----------:|-----------|----------|
| 2 | 2xx | 3xx, 4xx, 5xx |
| 3 | 2xx, 3xx | 4xx, 5xx |
| 4 | 2xx–4xx | 5xx |
| 5 | any HTTP response | no response only |

- `url` (required): absolute `http`/`https` URL with a host, path empty or `/`, no
  query, fragment or user info.
- `maxClass` (required, no default): 2–5.
- No response always fails: connection refused, DNS failure, TLS error, timeout,
  malformed response. So `5` means "the server answers HTTP at all".

Other types come from plugins; see the plugin's README for its settings (for
MinecraftChecker: `host`, `port`, `srv`, `minOnline`, `expectVersionContains`).

**Endpoints in the dashboard**: users with `endpoints.manage` can also create, edit and
delete endpoints and categories in the dashboard (`/admin/endpoints`). Those are stored
in `data/endpoints.json` and apply immediately. Config-defined ones are read-only there
(they have a "config" badge). An id defined in both places is a startup error. "Copy
to dashboard" moves a config entry over: it shows the snippet to remove from
`config.json`, and after the restart the dashboard copy takes over the same id and
history. A dashboard endpoint that fails validation at startup (for example because
its plugin jar was removed) is loaded as disabled instead of preventing startup.

### Visibility

The dashboard is public. Each endpoint's `visibility` (default **`private`**, so
nothing is shown by accident) decides what visitors without `view.private` see:

| | `public` | `public-status` | `private` |
|---|---|---|---|
| Listed on the main page | yes | yes | no |
| Name, state, uptime, 60-day bar | yes | yes | — |
| Service page: events, calendar, day uptime, public notes | yes | yes | — |
| Recent checks as pass/fail | yes | yes | — |
| Latency, check message, details, failure messages | yes (leak filter) | no | — |
| Target (URL, host, port) | **never** | **never** | **never** |

- A `private` endpoint answers `404` (not `403`), so its existence isn't revealed.
- **Leak filter**: any message or detail containing one of the endpoint's targets (now
  or in the past) is left out of the public view entirely.
- The overall status and the `/api/status` code (`200`, or `503` when something is down)
  only consider what the requester can see.
- The JSON API (`/api/status`, `/api/service/{id}`, `/api/maintenance`, …) applies the
  same rules as the HTML pages.

### Categories and layout

```json
"categories": [
  { "id": "web", "name": "Websites" },
  { "id": "minecraft", "name": "Minecraft servers", "description": "Java Edition" }
]
```

Categories in array order and endpoints in `endpoints` order give the default layout.
Endpoints without a category go into "Other". A category is only shown to a viewer who
can see at least one service in it. Users with `layout.edit` can reorder categories and
services and move services between categories on the main page ("Edit layout"); that
layout is saved in `data/layout.json` and overrides the config order. "Reset to config
order" goes back.

### History and uptime

- **Coverage**: periods when an endpoint was actually monitored. Time the app was down
  (or the endpoint wasn't configured) is "no data", which counts as neither up nor down,
  and is shown grey.
- **Downtime events**: one per outage, from the first failed check of the streak that
  led to DOWN to the first successful check. If the app stops during an outage, the
  event is closed at the last observed failure and marked "ended unobserved".
- **Uptime** = 1 − downtime / monitored time, over the last 24 h, the last 60 days, or a
  calendar day in `timezone`. It's truncated, never rounded up: 99.995 % shows as
  99.99 %, and only zero downtime shows 100 %.
- History is never deleted automatically. An endpoint removed from the config keeps its
  files in `data/endpoints/{id}/` (it's logged at startup), and the id can't be reused
  in the dashboard while that history exists.
- Signed-in users with `events.annotate` can rename events and add public or private
  notes to them. Public notes are shown to everyone who can see the endpoint.

### Authentication

```json
"auth": {
  "sessionDays": 7,
  "local": { "enabled": true },
  "roles": { "ops": ["view.private", "maintenance.manage", "events.annotate"] },
  "users": [
    { "id": "siggi", "displayName": "Siggi", "role": "admin",
      "password": "${env:ADMIN_PASSWORD_HASH}",
      "identities": ["google:110248495921238986420", "github:123456"] },
    { "id": "old-intern", "role": "viewer", "identities": ["github:42"], "disabled": true }
  ],
  "rules": [
    { "provider": "google", "emailDomain": "example.com", "role": "viewer" }
  ],
  "providers": [ … ]
}
```

**Sessions** are stored in `data/sessions/`, so logins survive restarts. `sessionDays`
(1–365, default 7) is an idle timeout. Anonymous visitors get no cookie. Session cookies
are per hostname.

**Built-in login**: a user with a `password` signs in at `/login` with their `id` as
username. Create the hash with

```sh
java -jar HealthChecker.jar hash-password
```

which asks for the password twice (without echo) and prints a
`pbkdf2-sha256$600000$…$…` string. Put it in the config, ideally via `${env:…}`. Failed
sign-ins are rate-limited (10 per 15 minutes per IP and per username). Set
`"local": { "enabled": false }` to allow only OIDC/OAuth.

**OIDC / OAuth 2.0 providers**: each is a "Sign in with …" button.

```json
"providers": [
  { "id": "google", "type": "oidc", "label": "Google",
    "issuer": "https://accounts.google.com",
    "clientId": "${env:GOOGLE_CLIENT_ID}", "clientSecret": "${env:GOOGLE_CLIENT_SECRET}",
    "identifyBy": "sub" },
  { "id": "github", "type": "oauth2", "label": "GitHub",
    "authorizeUrl": "https://github.com/login/oauth/authorize",
    "tokenUrl": "https://github.com/login/oauth/access_token",
    "userinfoUrl": "https://api.github.com/user",
    "scopes": ["read:user"], "idField": "id", "nameField": "login",
    "clientId": "${env:GITHUB_CLIENT_ID}", "clientSecret": "${env:GITHUB_CLIENT_SECRET}" }
]
```

- `oidc` uses discovery, the authorization code flow with PKCE, and verifies the
  `id_token` signature against the provider's JWKS. `identifyBy` is `sub` (default and
  recommended, since it never changes), `email` (only accepted when `email_verified` is
  true) or another claim name.
- `oauth2` is for providers that aren't OIDC (GitHub, Discord, …): the person is
  identified by `idField` from the userinfo response. `pkce` defaults to `true`.
- Register the callback URL **`{publicBaseUrl}/auth/callback/{providerId}`** with the
  provider, once for every site the provider is offered on (see [Sites](#sites)).
  Restrict a provider to some sites with `"sites": ["status.example.com"]`.
- Provider URLs must be https (http only on localhost).

**Adding people**: a person is identified as `{providerId}:{value}`, e.g.
`google:110248495921238986420`. If someone signs in with an identifier that isn't
configured, no session is created; they see a page with their identifier and a Copy
button, and are asked to send it to you. The attempt is also recorded in the audit log,
so you can verify it. Add the identifier to a user's `identities` and restart.

**Rules** grant a role without listing each person: `provider` and `role` plus at least
one condition, `emailDomain` (OIDC only, verified emails only, exact domain) and/or
`group` (matched against the `groups` claim). The first matching rule wins; an explicit
user entry always beats a rule.

`disabled: true` blocks a user's login and ends their existing sessions on the next
request. User and role changes apply after a restart.

### Roles and permissions

| Permission | Allows |
|------------|--------|
| `view.private` | see `private` endpoints, targets, private notes, failure messages, details, `notify`/`reminders`, channel errors |
| `events.annotate` | rename events; add, edit, delete notes on events and maintenance windows |
| `maintenance.manage` | create, edit, cancel, end and extend maintenance windows |
| `layout.edit` | reorder categories and services, move services between categories |
| `endpoints.manage` | create, edit, delete endpoints and categories; "Test now"; "Copy to dashboard" |
| `history.delete` | "also delete history" when deleting an endpoint |
| `notifications.test` | "Send test notification" |
| `audit.view` | the audit log at `/admin/audit` |

Built-in roles: `viewer` = `view.private`; `editor` = `viewer` + `events.annotate`,
`maintenance.manage`; `admin` = everything. Define your own under `auth.roles` as a list
of permissions. A role without `view.private` sees what anonymous visitors see, plus
whatever its other permissions allow. The layout editor, the endpoint and category
editors and the audit log also need `view.private`, since they show private services.

Every dashboard change (layout, endpoints, categories, event titles, notes,
maintenance) and every failed or rejected sign-in is recorded in `data/audit.jsonl`,
with secrets masked.

### Notifications

```json
"notifications": {
  "batchSeconds": 30,
  "channels": {
    "ops-email": { "type": "email", "baseUrl": "https://mail-api.example.com",
                   "apiKey": "${env:SIMPLEMAILER_KEY}",
                   "from": "HealthChecker <health@example.com>", "to": ["ops@example.com"] },
    "discord":   { "type": "discord", "webhookUrl": "${env:DISCORD_WEBHOOK}",
                   "mention": "<@&123456789>" }
  }
}
```

Channels are named destinations; you can have several of each type. Each endpoint picks
channels with `notify`:

| Endpoint `notify` | Effect |
|-------------------|--------|
| omitted | uses `defaults.notify` (no notifications if that is omitted too) |
| `["ops-email", "discord"]` | those channels |
| `false` or `[]` | never notifies; still checked, recorded and shown |

- Notifications are sent for **down** (UP/UNKNOWN → DOWN), **reminders** while still
  down, and **recovered** (DOWN → UP, with the outage duration). UNKNOWN → UP at startup
  is silent.
- Everything for one channel within `batchSeconds` (0–3600, default 30; 0 = at once) is
  combined into one message, so an incident hitting 20 endpoints sends one email.
- Delivery is asynchronous and retried with backoff (10 s doubling up to 10 min, 10
  attempts). The last error per channel is shown at `/admin/notifications`, where users
  with `notifications.test` can send a test message to each channel.
- **`email`** goes through a SimpleMailer instance's HTTP API:
  `POST {baseUrl}/v1/messages` with `apiKey` as bearer token. Keys: `baseUrl`, `apiKey`,
  `from`, `to` (list), optional `requireTls`. Each message carries a stable id, so
  retries never send duplicates.
- **`discord`** posts embeds to `webhookUrl` (red down, orange reminder, green
  recovered). Optional `username`, and `mention` (`<@&roleId>`, `<@userId>`,
  `@everyone`, `@here`), which is sent with down and reminder messages only; nothing
  else in a message can ping anyone.
- Messages link to the event on the dashboard when a `publicBaseUrl` is configured.

### Reminders

`reminders` (in `defaults` or per endpoint) lists minutes after the outage started at
which a "still down" reminder is sent. The default `[30, 60, 120]` reminds at 0:30, 1:00
and 2:00 into the outage, then stops. Values must be strictly increasing; `[]` turns
reminders off. `reminderRepeatMinutes` (default `0` = off) keeps reminding at that
interval after the last listed one.

Pending reminders are saved in `data/notifications/state.json`. After a restart, a
reminder that became due while the app was down is sent once, not once per missed slot.

### Maintenance

```json
"maintenance": {
  "countsAsDowntime": false, "maxLengthDays": 7, "upcomingDays": 7, "announceBeforeHours": 24
}
```

Users with `maintenance.manage` schedule maintenance windows from the main page or a
service page ("Schedule maintenance"): title, description, start and end (in
`timezone`), affected services and/or whole categories, optional announcement
channels, and an optional repeat rule (daily, weekly on chosen weekdays, monthly by day
or "first Sunday" style, with an optional end date). Single occurrences of a series can
be skipped or edited. Windows in progress can be ended early or extended.

During a window:

- Checks keep running and are recorded, so you can see when the service came back.
- The service shows a blue "Maintenance" state; downtime is recorded as planned
  maintenance, and days with maintenance but no unplanned downtime are blue.
- Planned downtime doesn't reduce uptime unless `countsAsDowntime` is `true`.
- Down, reminder and recovery notifications for affected endpoints are suppressed. If
  the service is still down when the window ends, it becomes a normal outage from that
  moment, with the usual notifications.
- If the window has announcement channels, a message goes out when it's scheduled, when
  it starts, when it ends or is cancelled, and `announceBeforeHours` before it starts
  (0 = no advance notice). Announcements aren't caught up after a restart.

`maxLengthDays` (1–366) limits one occurrence, and `upcomingDays` (1–366) sets how far
ahead the "Upcoming maintenance" box looks. Visitors see a window only for affected
services they can see.

### Sites

One instance can serve several status pages on different hostnames, each limited to
some categories and/or endpoints:

```json
"sites": [
  { "hosts": ["mc-status.example.com"], "title": "Minecraft status",
    "publicBaseUrl": "https://mc-status.example.com", "categories": ["minecraft"] },
  { "hosts": ["web-status.example.com", "*.web.example.com"],
    "publicBaseUrl": "https://web-status.example.com", "endpoints": ["website"] }
]
```

- `hosts` is matched against the `Host` header (or `X-Forwarded-Host` from a trusted
  proxy), case-insensitively and without the port. `*.example.com` matches any
  subdomain, but not `example.com` itself. The first matching site wins.
- `categories` and `endpoints` are combined; omit both to include everything. A site
  only filters: order stays as in the global layout.
- `title` overrides `dashboard.title`; `publicBaseUrl` is used for that site's links,
  redirects and OIDC callbacks (falling back to `server.publicBaseUrl`).
- A hostname that matches no site gets the default site (everything), unless
  `server.unknownHosts` is `reject`.
- Endpoints outside a site's scope answer `404` there, even for signed-in users.
- Logins don't carry over between sites (cookies are per hostname), and each site needs
  its own OIDC/OAuth callback URL registered.

> **Sites are presentation, not access control.** Anyone can send any `Host` header
> and see another site's view. Only `visibility` and login protect anything. Don't put
> a service on a "hidden" site and expect it to be private: make it `private`.

## Reverse proxy

HealthChecker speaks plain HTTP and never terminates TLS itself. Put it behind a reverse
proxy (nginx, Caddy, Apache, a load balancer) that handles HTTPS.

- Bind it to localhost: `"bind": "127.0.0.1"`.
- Add the proxy's address to `server.trustedProxies` (for a proxy on the same machine,
  `127.0.0.1` and/or `::1`, matching the address it connects from). Loopback is **not**
  trusted automatically.
- The proxy must send `X-Forwarded-For`, `X-Forwarded-Proto` and the original `Host`
  (or `X-Forwarded-Host`). These are only believed from trusted proxies:
  - `X-Forwarded-For` gives the client IP used for login rate limiting and the audit
    log.
  - `X-Forwarded-Proto: https` marks the request as HTTPS, which adds `Secure` to the
    session cookie. The proxy must overwrite this header with the scheme the client
    used, not pass on or append to a value the client sent.
  - `Host` / `X-Forwarded-Host` selects the [site](#sites).
- Set `publicBaseUrl` (globally and/or per site) to the external `https://` URL. Links
  and redirects are built only from it, never from the request's `Host`.

nginx example:

```nginx
server {
    listen 443 ssl;
    server_name status.example.com mc-status.example.com;
    # ssl_certificate …; ssl_certificate_key …;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
    }
}
```

Caddy example (sends the forwarded headers by default):

```
status.example.com, mc-status.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

## Running as a systemd service

A sample layout:

```
/opt/healthchecker/
  HealthChecker.jar
  config.json        (readable by the service user only)
  plugins/           plugin jars
  data/              created and written by the service
```

`/etc/systemd/system/healthchecker.service`:

```ini
[Unit]
Description=HealthChecker status page
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=healthchecker
Group=healthchecker
WorkingDirectory=/opt/healthchecker
# Secrets referenced as ${env:…} in config.json, e.g. SIMPLEMAILER_KEY=…
EnvironmentFile=-/etc/healthchecker/env
ExecStart=/usr/bin/java -jar /opt/healthchecker/HealthChecker.jar run /opt/healthchecker/config.json
# Shutdown takes at most about 13 s (final coverage, notification flush).
TimeoutStopSec=30
# A config error exits with status 1: don't loop on it.
Restart=on-failure
RestartSec=10
RestartPreventExitStatus=1 2
SuccessExitStatus=143

NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/healthchecker/data

[Install]
WantedBy=multi-user.target
```

```sh
sudo useradd --system --home-dir /opt/healthchecker --shell /usr/sbin/nologin healthchecker
sudo mkdir -p /opt/healthchecker/data /opt/healthchecker/plugins
sudo chown healthchecker:healthchecker /opt/healthchecker/data
sudo chmod 600 /etc/healthchecker/env          # if you use it
sudo systemctl daemon-reload
sudo systemctl enable --now healthchecker
journalctl -u healthchecker -f                 # logs, including config errors
```

`WorkingDirectory` matters: `dataDir` and `plugins.dir` are relative to it.

## Backing up `data/`

Everything HealthChecker records lives under `dataDir`; `config.json` and the plugin
jars are the rest of an installation. Back them up together.

| Path | Contents | Back up? |
|------|----------|----------|
| `endpoints/{id}/coverage.json` | monitored periods | yes |
| `endpoints/{id}/events/{yyyy}.jsonl` | downtime events, titles, notes | yes |
| `endpoints/{id}/targets.json` | every target the endpoint ever had (for the leak filter) | yes |
| `endpoints.json` | endpoints and categories created in the dashboard | yes |
| `layout.json` | dashboard layout | yes |
| `maintenance.json` | maintenance windows | yes |
| `audit.jsonl` | audit log (append-only, never rotated) | yes |
| `notifications/state.json` | pending reminders | optional |
| `sessions/` | login sessions | optional; restoring it restores logins, leaving it out just signs everyone out |

- Files are written atomically (temp file, fsync, rename), so copying the directory
  while the app runs gives you consistent individual files. Files can still be from
  slightly different moments; for an exact snapshot, stop the service first, or use a
  filesystem snapshot (ZFS, LVM, btrfs).
- To restore: stop the service, replace `data/`, start it. The time the app was down
  shows as "no data", not as downtime.
- Don't edit these files while the app runs; it would overwrite your changes. A
  malformed file is a startup error naming the file, rather than being silently
  dropped.
- `data/` contains private notes, targets and session data. Keep backups as private as
  the server itself.

## Security notes

- **Only give accounts to people you trust.** Users with `endpoints.manage` can make
  the server connect to any host and port (endpoints and "Test now"), including hosts
  on your internal network.
- Plugins are trusted code running in the same JVM. Only install plugin jars you trust.
- Targets (URLs, hosts, ports) are never shown to anonymous visitors, and messages
  containing them are filtered out. Still, pick endpoint ids and names that don't reveal
  them: both are public for `public` and `public-status` endpoints.
- Sites scope by `Host` header and are not a security boundary (see [Sites](#sites)).
- Every form is a POST with a CSRF token; permissions are checked on the server for
  every page, form and API call.

## Writing plugins

See [`PLUGINS.md`](PLUGINS.md) for the plugin API, and
[MinecraftChecker](https://github.com/WesternIcelander/MinecraftChecker) for a complete
example.
