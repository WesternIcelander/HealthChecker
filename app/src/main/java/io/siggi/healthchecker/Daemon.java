package io.siggi.healthchecker;

import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.auth.LocalLogin;
import io.siggi.healthchecker.auth.LoginRateLimiter;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.auth.UserDirectory;
import io.siggi.healthchecker.config.Config;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.EndpointStore;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.history.UptimeCalculator;
import io.siggi.healthchecker.plugin.PluginManager;
import io.siggi.healthchecker.plugin.JsonEndpointConfig;
import io.siggi.healthchecker.schedule.CheckScheduler;
import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.schedule.EndpointFactory;
import io.siggi.healthchecker.schedule.EndpointRegistry;
import io.siggi.healthchecker.status.CheckRecord;
import io.siggi.healthchecker.status.StateChange;
import io.siggi.healthchecker.status.StatusRegistry;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.layout.LayoutStore;
import io.siggi.healthchecker.web.page.EndpointEditor;
import io.siggi.healthchecker.web.page.EventEditor;
import io.siggi.healthchecker.web.page.CategoryEditor;
import io.siggi.healthchecker.web.page.LayoutEditor;
import io.siggi.healthchecker.web.page.LoginPage;
import io.siggi.healthchecker.web.page.ServicePage;
import io.siggi.healthchecker.web.page.StatusPage;
import io.siggi.healthchecker.web.site.SiteResolver;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/**
 * The running service: endpoints from the config checked by the scheduler, results recorded
 * in the status registry and the history, and the dashboard served by the web server.
 * Signed-in users see what their role's permissions allow; users and roles are read once at startup.
 */
final class Daemon implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(Daemon.class.getName());
    private final HistoryStore history;
    private final EndpointRegistry registry;
    private final StatusRegistry status = new StatusRegistry();
    private final CheckScheduler scheduler;
    private final UptimeCalculator uptime;
    private final WebServer web;
    private final SessionManager sessions;
    private final CountDownLatch closed = new CountDownLatch(1);
    private boolean closing;

    private final EndpointStore endpoints;

    private Daemon(Config config, PluginManager plugins, EndpointStore endpoints, HistoryStore history)
            throws IOException {
        this.history = history;
        this.endpoints = endpoints;
        // A deleted endpoint's id can't be reused while its history is kept.
        endpoints.reserveIdsWithHistory(history::hasHistory);
        Clock clock = Clock.systemUTC();
        registry = new EndpointRegistry(new EndpointFactory(plugins));
        scheduler = new CheckScheduler(registry, status);
        scheduler.addCheckListener(new CheckScheduler.CheckListener() {
            @Override
            public void checked(String endpointId, CheckRecord check, StateChange change) {
                history.record(endpointId, check, change);
            }

            @Override
            public void stopped(String endpointId, CheckScheduler.StopReason reason) {
                history.stopMonitoring(endpointId, reason == CheckScheduler.StopReason.REPLACED);
            }
        });
        // Remember every target an endpoint has had, so its history stays leak-filtered after edits.
        registry.subscribe(new EndpointRegistry.Listener() {
            @Override
            public void added(Endpoint endpoint) {
                rememberTargets(endpoint);
            }

            @Override
            public void updated(Endpoint old, Endpoint now) {
                rememberTargets(now);
            }

            @Override
            public void replaced(Endpoint old, Endpoint now) {
                rememberTargets(now);
            }

            @Override
            public void removed(Endpoint old) {
            }
        });
        Function<String, Optional<Endpoint>> lookup =
                id -> registry.get(id).map(e -> e.withPastTargets(history.knownTargets(id)));
        uptime = new UptimeCalculator(history, config.timezone(), config.maintenance().countsAsDowntime(), clock);
        LayoutStore layout = new LayoutStore(config.dataDir());
        layout.load();
        web = new WebServer(config.server());
        sessions = SessionManager.forDataDir(config.dataDir(), SessionManager.sessionDays(config.auth()),
                web.trustedProxies());
        SiteResolver sites = SiteResolver.fromConfig(config, web.trustedProxies());
        UserDirectory users = UserDirectory.parse(config.auth());
        Permissions permissions = new Permissions(sessions, users::user);
        new LoginPage(sites, sessions, permissions,
                new LocalLogin(LocalLogin.enabled(config.auth()), users.credentials(), new LoginRateLimiter()),
                web.trustedProxies()).register(web);
        LayoutEditor layoutEditor = new LayoutEditor(sites, permissions, sessions, layout,
                endpoints::categoryConfigs, endpoints::definitions);
        layoutEditor.register(web);
        CategoryEditor categoryEditor = new CategoryEditor(sites, permissions, sessions, endpoints, layout, config);
        categoryEditor.register(web);
        layoutEditor.categoryEditor(categoryEditor);
        EndpointEditor endpointEditor = new EndpointEditor(sites, permissions, sessions, endpoints, plugins, config,
                history);
        endpointEditor.register(web);
        StatusPage statusPage = new StatusPage(sites, layoutEditor::layout,
                lookup, status::status, permissions::viewer, uptime, config.dashboard(), layoutEditor);
        statusPage.endpointEditor(endpointEditor);
        statusPage.register(web);
        ServicePage servicePage = new ServicePage(sites,
                () -> layout.resolve(endpoints.categoryConfigs(), endpoints.definitions()),
                lookup, status::status, permissions::viewer, uptime, history, clock,
                config.dashboard(), new EventEditor(permissions, sessions, history));
        servicePage.endpointEditor(endpointEditor);
        servicePage.register(web);
    }

    /**
     * Builds everything, adds the configured endpoints, starts checking and listening.
     * On failure, whatever was started is stopped again.
     *
     * @param endpoints the merged endpoints and categories; disabled endpoints aren't checked
     * @param history   loaded history; closed by {@link #close()}
     */
    static Daemon start(Config config, PluginManager plugins, EndpointStore endpoints, HistoryStore history)
            throws IOException, ConfigException {
        Daemon daemon;
        try {
            daemon = new Daemon(config, plugins, endpoints, history);
        } catch (IOException | RuntimeException e) {
            history.close();
            throw e;
        }
        try {
            for (EndpointDefinition definition : daemon.endpoints.definitions()) {
                daemon.registry.add(definition);
            }
            daemon.endpoints.addChangeListener(daemon::applyEndpointChanges);
            daemon.history.startHeartbeat();
            daemon.scheduler.start();
            daemon.web.start();
        } catch (IOException | ConfigException | RuntimeException e) {
            daemon.close();
            throw e;
        }
        return daemon;
    }

    /**
     * Applies a saved change of the dashboard endpoints to the running checks: new endpoints start
     * being checked, presentation/routing changes apply in place (state and open events kept), and a
     * changed check replaces the checker (state UNKNOWN, an open event closed as
     * {@code endedByConfigChange}). Runs under the endpoint store's lock, right after the write.
     */
    private void applyEndpointChanges() {
        Map<String, ConfigException> failures = registry.sync(endpoints.definitions());
        failures.forEach((id, e) -> LOGGER.log(System.Logger.Level.ERROR,
                "Saved endpoint " + id + " couldn't be applied; it keeps its previous check until the next save or"
                        + " restart: " + e.getMessage()));
    }

    private void rememberTargets(Endpoint endpoint) {
        List<String> values = new ArrayList<>();
        for (JsonEndpointConfig.Target t : endpoint.targets()) {
            values.add(t.value());
        }
        history.rememberTargets(endpoint.id(), values);
    }

    EndpointStore endpoints() {
        return endpoints;
    }

    StatusRegistry status() {
        return status;
    }

    HistoryStore history() {
        return history;
    }

    EndpointRegistry registry() {
        return registry;
    }

    /** The port the web server listens on. */
    int port() {
        return web.port();
    }

    /** Waits until {@link #close()} has finished. */
    void awaitClose() throws InterruptedException {
        closed.await();
    }

    /** Stops listening, stops every check (closing the checkers) and writes a final history heartbeat. */
    @Override
    public void close() {
        synchronized (this) {
            if (closing) {
                return;
            }
            closing = true;
        }
        try {
            web.close();
            scheduler.close();
            uptime.close();
            history.close();
        } finally {
            closed.countDown();
        }
    }
}
