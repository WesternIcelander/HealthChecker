package io.siggi.healthchecker;

import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.auth.LocalLogin;
import io.siggi.healthchecker.auth.LoginRateLimiter;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.auth.UserDirectory;
import io.siggi.healthchecker.config.Config;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.history.UptimeCalculator;
import io.siggi.healthchecker.plugin.PluginManager;
import io.siggi.healthchecker.schedule.CheckScheduler;
import io.siggi.healthchecker.schedule.EndpointFactory;
import io.siggi.healthchecker.schedule.EndpointRegistry;
import io.siggi.healthchecker.status.CheckRecord;
import io.siggi.healthchecker.status.StateChange;
import io.siggi.healthchecker.status.StatusRegistry;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.layout.LayoutStore;
import io.siggi.healthchecker.web.page.LoginPage;
import io.siggi.healthchecker.web.page.ServicePage;
import io.siggi.healthchecker.web.page.StatusPage;
import io.siggi.healthchecker.web.site.SiteResolver;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;

/**
 * The running service: endpoints from the config checked by the scheduler, results recorded
 * in the status registry and the history, and the dashboard served by the web server.
 * Signed-in users see what their role's permissions allow; users and roles are read once at startup.
 */
final class Daemon implements AutoCloseable {
    private final HistoryStore history;
    private final EndpointRegistry registry;
    private final StatusRegistry status = new StatusRegistry();
    private final CheckScheduler scheduler;
    private final UptimeCalculator uptime;
    private final WebServer web;
    private final SessionManager sessions;
    private final CountDownLatch closed = new CountDownLatch(1);
    private boolean closing;

    private Daemon(Config config, PluginManager plugins, HistoryStore history) throws IOException {
        this.history = history;
        Clock clock = Clock.systemUTC();
        registry = new EndpointRegistry(new EndpointFactory(plugins));
        scheduler = new CheckScheduler(registry, status);
        scheduler.addCheckListener(new CheckScheduler.CheckListener() {
            @Override
            public void checked(String endpointId, CheckRecord check, StateChange change) {
                history.record(endpointId, check, change);
            }

            @Override
            public void stopped(String endpointId) {
                history.stopMonitoring(endpointId);
            }
        });
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
        new StatusPage(sites, () -> layout.resolve(config.categories(), config.endpoints()),
                registry::get, status::status, permissions::viewer, uptime, config.dashboard())
                .register(web);
        new ServicePage(sites, () -> layout.resolve(config.categories(), config.endpoints()),
                registry::get, status::status, permissions::viewer, uptime, history, clock,
                config.dashboard()).register(web);
    }

    /**
     * Builds everything, adds the configured endpoints, starts checking and listening.
     * On failure, whatever was started is stopped again.
     *
     * @param history loaded history; closed by {@link #close()}
     */
    static Daemon start(Config config, PluginManager plugins, HistoryStore history) throws IOException, ConfigException {
        Daemon daemon;
        try {
            daemon = new Daemon(config, plugins, history);
        } catch (IOException | RuntimeException e) {
            history.close();
            throw e;
        }
        try {
            for (EndpointDefinition definition : config.endpoints()) {
                daemon.registry.add(definition);
            }
            daemon.history.startHeartbeat();
            daemon.scheduler.start();
            daemon.web.start();
        } catch (IOException | ConfigException | RuntimeException e) {
            daemon.close();
            throw e;
        }
        return daemon;
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
