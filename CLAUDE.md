# CLAUDE.md

## Project

HealthChecker: a Java 21 / Maven daemon that runs health checks against configured
endpoints and serves a public dashboard (with per-endpoint visibility, built-in and
OIDC/OAuth login) using SiggiHTTP (`io.siggi:SiggiHTTP`). Notifications go via
SimpleMailer (HTTP API, project at `../SimpleMailer`) and Discord webhooks.
See `PLAN.md` for the design and milestones.

- Modules: `HealthChecker-Parent` (root pom), `HealthChecker-API` (`api/`),
  `HealthChecker` (`app/`, runnable shaded jar)
- Build: `mvn package`
- Install the API for plugins: `mvn install`
- Test: `mvn test`
- Entry point: `io.siggi.healthchecker.Main`
- HTTP checks are built in. Every other check type is a separate plugin project
  with its own git repo under `plugin-projects/` (ignored by this repo), e.g.
  `plugin-projects/MinecraftChecker`.
- Plugins compile against `HealthChecker-API`, plus Gson (`provided`). Keep the API
  module dependency-free. Gson is shaded into the app without relocation, because
  plugins rely on it.
- Endpoint targets (URLs, hosts, ports) must never appear in anything an anonymous
  visitor can see.

## Workflow: PLAN.md steps

- Work through `PLAN.md` one checklist item (`- [ ]`) at a time.
- When a step is completed:
  1. Make sure it builds and tests pass (`mvn -q test`, or `mvn -q package` when there are no tests yet).
  2. Tick the item in `PLAN.md` (`- [ ]` → `- [x]`).
  3. Commit, one commit per step, with a message that names the step (e.g. `M3: Add http check`).
     - Code for a plugin step goes in that plugin's own repo
       (e.g. `plugin-projects/MinecraftChecker`). The `PLAN.md` tick is committed
       separately in this repo.
  4. Push every repo you committed to right away (`git push`).
- Don't batch several steps into one commit, and don't leave completed steps unpushed.
- If a build or test fails, or a push is rejected, stop and report it; don't force-push.
