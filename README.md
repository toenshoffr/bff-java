# bff-java

A Java/Spring Boot rewrite of [`toenshoffr/bff`](https://github.com/toenshoffr/bff),
a Backend-for-Frontend (BFF) that implements the **token handler pattern** between
an Angular SPA and a Spring Boot REST API.

See [`SPEC.md`](./SPEC.md) for the full behavioral specification this
implementation follows, including the acceptance checklist it satisfies.

## What this project is

The BFF sits between the browser (an Angular single-page app) and a Spring Boot
REST API, and terminates both trust boundaries so neither side has to handle
tokens directly:

```
Angular SPA                    BFF (this app)                 Spring Boot API
     |  httpOnly session cookie      |        Bearer JWT              |
     |------------------------------>|------------------------------->|
     |         GET /api/orders       |         GET /orders            |
     |<------------------------------|<--------------------------------|
```

- **Browser ↔ BFF**: an opaque, `httpOnly`, `Secure`, `SameSite` session cookie.
  The browser (and any JavaScript running in it) never sees an access or refresh
  token, which closes off token theft via XSS.
- **BFF ↔ Spring Boot API**: JWT access tokens, attached server-side as
  `Authorization: Bearer <jwt>` and refreshed transparently before they expire.
  Tokens live only in server-side session storage.

On top of that, the BFF provides:

- Two independently-configurable login flows: username/password, and OAuth 2.0
  Authorization Code + PKCE against an external IdP.
- Transparent access-token refresh on every proxied API call.
- An authenticated reverse proxy (`/api/**`) to the Spring Boot API, with
  upstream CORS headers stripped so only the BFF's own CORS policy reaches the
  browser.
- Stateless double-submit-cookie CSRF protection for all mutating requests.
- Session regeneration on login (fixation mitigation), fail-fast startup config
  validation, and standard security headers.

The goal is **wire-level parity** with the reference Node.js/Express
implementation — same endpoints, cookie names, header names, JSON shapes, and
(mostly) the same environment variable names — so an existing Angular frontend
and Spring Boot backend can point at this service unchanged. It's a
re-implementation in Java, not a redesign; see [`SPEC.md`](./SPEC.md) for the
full behavioral specification, endpoint list, session data model, and
environment variable reference.

## Building

Maven is the build tool (see `SPEC.md` → "Build & Packaging (Maven)" for the
full rationale). From the repo root:

```bash
mvn clean package
```

This produces `target/bff-java.war`. That single artifact is
dual-purpose:

- **Run it standalone** (embedded Tomcat, for local dev or a container image):
  ```bash
  java -jar target/bff-java.war
  ```
  or, without packaging first:
  ```bash
  mvn spring-boot:run
  ```
- **Deploy it into an external Tomcat** — see below.

Required configuration (env vars) is validated at startup and documented in
full in `SPEC.md` → "Configuration"; at minimum you'll need
`FRONTEND_ORIGIN`, `API_BASE_URL`, and `SESSION_SECRET` set before the app
will start.

## Running the tests

```bash
mvn clean verify
```

`mvn clean package`/`mvn clean verify` run the full test suite, including
integration tests that boot the real Spring context against a stubbed
Spring Boot API/IdP (via [WireMock](https://wiremock.org/)) and exercise the
flows in SPEC.md's acceptance checklist: CSRF, password login, OAuth PKCE,
transparent token refresh, the authenticated proxy, and logout.

## Deploying to Tomcat

The app is packaged as a standard WAR (`<packaging>war</packaging>`, entry
point extends `SpringBootServletInitializer`), so it deploys to any Tomcat 10.x
instance (Jakarta EE 9+, matching Spring Boot 3.x's `jakarta.*` namespace) the
same way any Spring Boot WAR does.

1. **Build the WAR**
   ```bash
   mvn clean package
   ```
   This produces `target/bff-java.war`.

2. **Copy it into Tomcat's `webapps/` directory**
   ```bash
   cp target/bff-java.war "$CATALINA_HOME/webapps/bff.war"
   ```
   Tomcat auto-deploys on startup (or on drop-in, if `autoDeploy` is enabled).
   The name you give the file becomes the context path — `bff.war` deploys
   under `/bff`; rename it to `ROOT.war` to deploy at the server root (`/`) if
   the BFF should own the whole origin.

   > Deploying under a non-root context path changes the effective path of
   > every endpoint in `SPEC.md` (e.g. `/healthz` becomes `/bff/healthz`) and
   > the default cookie `Path`. Confirm the Angular app's configured API base
   > URL and this app's `COOKIE_*` settings agree on the context path before
   > relying on a non-root deployment.

3. **Provide configuration**

   The app reads its config from environment variables (see `SPEC.md` →
   "Configuration" for the full table: `FRONTEND_ORIGIN`, `API_BASE_URL`,
   `SESSION_SECRET`, `AUTH_METHODS`, the `OAUTH_*` block, etc.). Under a
   standalone Tomcat, set these however Tomcat's own conventions call for —
   the simplest is `$CATALINA_HOME/bin/setenv.sh`:
   ```bash
   #!/bin/sh
   export FRONTEND_ORIGIN=https://app.example.com
   export API_BASE_URL=https://api.example.com
   export SESSION_SECRET=change-me-to-a-long-random-value
   export AUTH_METHODS=password,oauth
   # ...remaining OAUTH_*/COOKIE_*/etc. vars as needed
   ```
   Alternatively, define them as `<Environment>` entries in Tomcat's
   `conf/context.xml` (or a per-app `META-INF/context.xml` inside the WAR) if
   your deployment process prefers JNDI-style config over process env vars —
   Spring Boot's relaxed binding picks up either.

4. **Server port and app port**

   An externally-deployed WAR runs *inside* Tomcat's own HTTP connector, so
   Tomcat's configured port (`conf/server.xml`, default `8080`) is what
   matters — the app's own `PORT`/`SERVER_PORT` setting is irrelevant to a WAR
   deployment (it only applies when running the embedded-server jar directly).

5. **TLS and `X-Forwarded-*` headers**

   In production this app expects `COOKIE_SECURE=true`, which requires Spring
   to see the *original* request as HTTPS even if TLS is actually terminated
   upstream (a load balancer or reverse proxy in front of Tomcat). Two things
   need to agree for that to work correctly:
   - Tomcat itself needs to trust and act on `X-Forwarded-For`/
     `X-Forwarded-Proto` from the proxy — typically by adding Tomcat's
     `RemoteIpValve` in `conf/server.xml`:
     ```xml
     <Valve className="org.apache.catalina.valves.RemoteIpValve"
            remoteIpHeader="X-Forwarded-For"
            protocolHeader="X-Forwarded-Proto" />
     ```
   - If TLS terminates directly on Tomcat instead (no upstream proxy), configure
     an HTTPS connector in `conf/server.xml` in the usual Tomcat way and skip
     the valve.

   Without one of these, `COOKIE_SECURE=true` behind a TLS-terminating proxy
   will cause Tomcat/Spring to think the request is plain HTTP, and the
   session cookie won't be set as `Secure` (or may not be sent back by the
   browser at all).

6. **Session store for multiple Tomcat instances**

   The default session store is in-memory and per-instance — fine for a single
   Tomcat node, but it won't share sessions across multiple nodes behind a load
   balancer. For a multi-instance deployment, either:
   - enable sticky sessions on the load balancer (simplest, but ties a user to
     one node), or
   - build with the Redis-backed session store so any node can serve any
     session: `mvn clean package -Predis` (see the `redis` profile in
     `pom.xml`), then set `SPRING_DATA_REDIS_HOST`/`SPRING_DATA_REDIS_PORT` to
     point at your Redis instance. This is a build-time flag rather than a pure
     env-var toggle — see `SPEC.md` → "Production Notes" for why.

7. **Verify**

   ```bash
   curl https://your-tomcat-host/bff/healthz   # or /healthz if deployed at ROOT
   ```
   should return `200 {"status":"ok"}`.

8. **Redeploying**

   Standard Tomcat WAR redeploy: replace the WAR file in `webapps/` (Tomcat
   will undeploy the old version and redeploy the new one), or drop the old
   deployment first if your Tomcat isn't configured for hot-swap redeploys.
   Existing sessions are lost on redeploy unless using the shared Redis store.

## Further reading

See [`SPEC.md`](./SPEC.md) for the complete behavioral specification: auth
flows, token refresh semantics, the full endpoint table, session data model,
environment variable reference, and the acceptance checklist a compliant
implementation must satisfy.
