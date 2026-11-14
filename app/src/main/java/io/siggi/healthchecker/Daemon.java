package io.siggi.healthchecker;

import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.audit.AuditLog;
import io.siggi.healthchecker.auth.AuthProviders;
import io.siggi.healthchecker.auth.ExternalLogin;
import io.siggi.healthchecker.auth.LocalLogin;
import io.siggi.healthchecker.auth.LoginRateLimiter;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.auth.UserDirectory;
import io.siggi.healthchecker.config.Config;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.EndpointStore;
import io.siggi.healthchecker.history.HistoryEvent;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.history.UptimeCalculator;
import io.siggi.healthchecker.maintenance.MaintenanceScheduler;
import io.siggi.healthchecker.maintenance.MaintenanceStore;
import io.siggi.healthchecker.notify.DiscordNotifier;
import io.siggi.healthchecker.notify.Notification;
import io.siggi.healthchecker.notify.NotificationConfig;
import io.siggi.healthchecker.notify.NotificationDispatcher;
import io.siggi.healthchecker.notify.ReminderScheduler;
import io.siggi.healthchecker.notify.TaskScheduler;
import io.siggi.healthchecker.plugin.PluginManager;
import io.siggi.healthchecker.plugin.JsonEndpointConfig;
import io.siggi.healthchecker.schedule.CheckScheduler;
import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.schedule.EndpointFactory;
import io.siggi.healthchecker.schedule.EndpointRegistry;
import io.siggi.healthchecker.status.CheckRecord;
import io.siggi.healthchecker.status.State;
import io.siggi.healthchecker.status.StateChange;
import io.siggi.healthchecker.status.StatusRegistry;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.layout.LayoutStore;
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.healthchecker.web.page.AuditPage;
import io.siggi.healthchecker.web.page.EndpointEditor;
import io.siggi.healthchecker.web.page.EventEditor;
import io.siggi.healthchecker.web.page.CategoryEditor;
import io.siggi.healthchecker.web.page.LayoutEditor;
import io.siggi.healthchecker.web.page.LoginPage;
import io.siggi.healthchecker.web.page.ProviderLoginPage;
import io.siggi.healthchecker.web.page.MaintenancePage;
import io.siggi.healthchecker.web.page.MaintenanceViews;
import io.siggi.healthchecker.web.page.NotificationsPage;
import io.siggi.healthchecker.web.page.ServicePage;
import io.siggi.healthchecker.web.page.StatusPage;
import io.siggi.healthchecker.web.site.Site;
import io.siggi.healthchecker.web.site.SiteResolver;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final MaintenanceStore maintenance;
    private final MaintenanceScheduler maintenanceScheduler;
    private final WebServer web;
    private final SessionManager sessions;
    private final NotificationDispatcher notifications;
    private final ReminderScheduler reminders;
    private final SiteResolver sites;
    private final LayoutStore layout;
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
                String eventBefore = change == null ? null
                        : history.openEvent(endpointId).map(HistoryEvent::id).orElse(null);
                history.record(endpointId, check, change);
                if (change != null) {
                    notifyChange(change, eventBefore);
                }
            }

            @Override
            public void stopped(String endpointId, CheckScheduler.StopReason reason) {
                history.stopMonitoring(endpointId, reason == CheckScheduler.StopReason.REPLACED);
                // A removed or replaced endpoint's outage is over (endedUnobserved / endedByConfigChange).
                reminders.cancel(endpointId);
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
                // reminders or reminderRepeatMinutes may have changed. Re-planned off this thread: registry
                // listeners run under the registry (and endpoint store) lock, which planning takes too.
                String id = now.id();
                Thread.ofVirtual().name("HealthChecker-reminder-refresh").start(() -> reminders.refresh(id));
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
        layout = new LayoutStore(config.dataDir());
        layout.load();
        AuditLog audit = AuditLog.open(config.dataDir());
        maintenance = new MaintenanceStore(config.dataDir(), config.maintenance(), new MaintenanceStore.References() {
            @Override
            public boolean endpointExists(String id) {
                return endpoints.endpoint(id).isPresent();
            }

            @Override
            public boolean categoryExists(String id) {
                return endpoints.categories().stream().anyMatch(c -> c.category().id().equals(id));
            }

            @Override
            public boolean channelExists(String name) {
                return config.notificationChannels().contains(name);
            }
        }, audit, clock, config.timezone());
        maintenance.load();
        // A window's categories cover the services in them in the effective layout at each moment.
        maintenanceScheduler = new MaintenanceScheduler(maintenance, status, history, () -> {
            Map<String, String> categories = new HashMap<>();
            for (ResolvedLayout.Category c : layout.resolve(endpoints.categoryConfigs(), endpoints.definitions())
                    .categories()) {
                for (String id : c.endpointIds()) {
                    categories.put(id, c.isOther() ? null : c.id());
                }
            }
            return categories;
        }, clock);
        web = new WebServer(config.server());
        sites = SiteResolver.fromConfig(config, web.trustedProxies());
        NotificationConfig notificationConfig = NotificationConfig.parse(config.notifications());
        notifications = NotificationDispatcher.create(notificationConfig.notifiers(DiscordNotifier.httpClient()),
                notificationConfig.batchWindow());
        reminders = new ReminderScheduler(ReminderScheduler.stateFile(config.dataDir()), id -> registry.get(id)
                .map(e -> new ReminderScheduler.Target(e.definition(), failureMessage(id),
                        link(id, history.openEvent(id).map(HistoryEvent::id).orElse(null)))),
                notifications::notify, TaskScheduler.create(), clock,
                System.getLogger(ReminderScheduler.class.getName()));
        sessions = SessionManager.forDataDir(config.dataDir(), SessionManager.sessionDays(config.auth()),
                web.trustedProxies());
        UserDirectory users = UserDirectory.parse(config.auth());
        Permissions permissions = new Permissions(sessions, users);
        AuthProviders providers = AuthProviders.parse(config.auth(), config.server(), config.sites());
        AuditPage auditPage = new AuditPage(sites, permissions, audit, config.timezone());
        auditPage.register(web);
        new LoginPage(sites, sessions, permissions,
                new LocalLogin(LocalLogin.enabled(config.auth()), users.credentials(), new LoginRateLimiter()),
                web.trustedProxies()).audit(audit).providers(providers).register(web);
        new ProviderLoginPage(sites, sessions, users, providers, ExternalLogin.standard(), web.trustedProxies(), clock)
                .audit(audit).register(web);
        LayoutEditor layoutEditor = new LayoutEditor(sites, permissions, sessions, layout,
                endpoints::categoryConfigs, endpoints::definitions).audit(audit);
        layoutEditor.register(web);
        CategoryEditor categoryEditor = new CategoryEditor(sites, permissions, sessions, endpoints, layout, config)
                .audit(audit);
        categoryEditor.register(web);
        layoutEditor.categoryEditor(categoryEditor);
        EndpointEditor endpointEditor = new EndpointEditor(sites, permissions, sessions, endpoints, plugins, config,
                history).audit(audit);
        endpointEditor.register(web);
        StatusPage statusPage = new StatusPage(sites, layoutEditor::layout,
                lookup, status::status, permissions::viewer, uptime, config.dashboard(), layoutEditor);
        statusPage.endpointEditor(endpointEditor);
        statusPage.auditPage(auditPage);
        NotificationsPage notificationsPage = new NotificationsPage(sites, permissions, sessions, notifications,
                notificationConfig.types(), clock, config.timezone());
        notificationsPage.register(web);
        statusPage.notificationsPage(notificationsPage);
        statusPage.register(web);
        ServicePage servicePage = new ServicePage(sites,
                () -> layout.resolve(endpoints.categoryConfigs(), endpoints.definitions()),
                lookup, status::status, permissions::viewer, uptime, history, clock,
                config.dashboard(), new EventEditor(permissions, sessions, history).audit(audit));
        servicePage.endpointEditor(endpointEditor);
        servicePage.register(web);
        MaintenanceViews maintenanceViews = new MaintenanceViews(maintenance,
                () -> layout.resolve(endpoints.categoryConfigs(), endpoints.definitions()), lookup, clock,
                config.maintenance().upcomingDays());
        MaintenancePage maintenancePage = new MaintenancePage(sites, permissions, sessions, maintenanceViews,
                maintenanceScheduler, config::notificationChannels);
        maintenancePage.register(web);
        statusPage.maintenance(maintenancePage);
        servicePage.maintenance(maintenancePage);
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
            // Pending reminders from before a restart; overdue ones are sent once.
            daemon.reminders.load();
            // Windows in progress are applied before the first check is recorded.
            daemon.maintenanceScheduler.start();
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

    /** Queues the down/recovered notification a state change causes, and starts or cancels reminders. */
    private void notifyChange(StateChange change, String eventBefore) {
        String id = change.endpointId();
        Optional<Endpoint> endpoint = registry.get(id);
        if (endpoint.isPresent()) {
            // A recovery links to the event it just closed, a down notification to the one it opened.
            String eventId = change.to() == State.DOWN
                    ? history.openEvent(id).map(HistoryEvent::id).orElse(null) : eventBefore;
            Notification.forChange(endpoint.get().definition(), change, link(id, eventId))
                    .ifPresent(n -> notifications.notify(endpoint.get().definition(), n));
        }
        reminders.onStateChange(change);
    }

    /** The latest failure message of the endpoint's ongoing outage, else of its last check. */
    private String failureMessage(String id) {
        return history.openEvent(id).map(HistoryEvent::lastFailureMessage)
                .or(() -> status.status(id).map(s -> s.lastCheck()).filter(c -> !c.success()).map(CheckRecord::message))
                .orElse(null);
    }

    /**
     * An absolute link to the event (or the service page without one) on the first site showing the
     * endpoint; {@code null} without a public base URL, since a relative link is useless in a message.
     */
    private String link(String endpointId, String eventId) {
        Site site = sites.siteFor(endpointId, layout.resolve(endpoints.categoryConfigs(), endpoints.definitions()));
        String link = site.link("/service/" + endpointId + (eventId == null ? "" : "/event/" + eventId));
        return link.startsWith("https://") || link.startsWith("http://") ? link : null;
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

    MaintenanceStore maintenance() {
        return maintenance;
    }

    NotificationDispatcher notifications() {
        return notifications;
    }

    ReminderScheduler reminders() {
        return reminders;
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
            maintenanceScheduler.close();
            scheduler.close();
            reminders.close();
            notifications.close();
            uptime.close();
            history.close();
        } finally {
            closed.countDown();
        }
    }
}
