# BFF Java Rewrite — Specification

## Purpose

This document specifies a **Java rewrite** of the reference implementation at
[`toenshoffr/bff`](https://github.com/toenshoffr/bff) — a Node.js/Express/TypeScript
Backend-for-Frontend (BFF) that sits between an Angular SPA and a Spring Boot REST
API, implementing the **token handler pattern**.

**Goal: functional and wire-level parity with the reference implementation**, so the
existing Angular frontend and the existing Spring Boot backend work against this
service *unchanged* — same endpoints, same cookie names, same header names, same
JSON shapes, same env var names where practical. This is a re-implementation in
Java, not a redesign. Any deviation from the reference contract must be called out
explicitly rather than introduced silently.

Treat this file as the implementation brief: read it fully before writing code, and
implement it end to end (not a partial skeleton) unless told otherwise.

## Core Pattern (unchanged from reference)

- **Browser ↔ BFF**: an opaque, `httpOnly`, `Secure`, `SameSite` session cookie. The
  browser (and any JS running in it) never sees an access or refresh token.
- **BFF ↔ Spring Boot API**: JWT access tokens, sent as `Authorization: Bearer <jwt>`.
  Tokens live only in server-side session storage and are refreshed transparently.

```
Angular SPA                    BFF (this app)                 Spring Boot API
     |  httpOnly session cookie      |        Bearer JWT              |
     |------------------------------>|------------------------------->|
     |         GET /api/orders       |         GET /orders            |
     |<------------------------------|<--------------------------------|
```

1. Angular calls the BFF's `/auth/login` (password) or redirects the browser to
   `/auth/oauth/login` (OAuth).
2. The BFF exchanges credentials/authorization code for a JWT access token (+
   refresh token) with the Spring Boot API / IdP, stores them server-side keyed by
   session, and sets an `httpOnly` cookie on the browser.
3. Angular calls `/api/**` on the BFF using the session cookie. The BFF resolves the
   session, refreshes the JWT if near expiry, and proxies the request to the Spring
   Boot API with `Authorization: Bearer <jwt>` attached.
4. The Spring Boot API never sees the session cookie; the browser never sees the JWT.

## Suggested Tech Stack

Not mandatory in every detail, but strongly preferred for a natural Java/Spring fit:

- **Java 21**, **Spring Boot 3.x**, built with **Maven** (`spring-boot-starter-parent`
  as the parent POM; see "Build & Packaging" below)
- **Spring Web (MVC)** for the REST/auth endpoints
- **Spring Session** for the server-side session store — default to an in-memory
  store (the plain Servlet `HttpSession`) for local dev, but structure it so a
  shared store (Redis via `spring-session-data-redis`) is a build-time opt-in —
  see "Build & Packaging" below for why that's a Maven profile rather than a pure
  env-var toggle — matching the reference's production note about `connect-redis`
- A proxy layer for `/api/**` — either a manual `Filter`/`HandlerInterceptor` +
  `RestClient`/`WebClient` forwarding implementation, or Spring Cloud Gateway if the
  project is comfortable pulling that dependency in. Must support arbitrary HTTP
  methods, streaming request/response bodies, and header passthrough (minus the
  ones this spec says to strip/inject).
- **Spring Security** may be used for building blocks (CORS, security headers,
  session fixation protection) but do **not** wire up its default `oauth2Login` /
  form-login flows — the auth endpoints and OAuth PKCE flow described below must be
  implemented explicitly to match the reference behavior, since this is a
  custom BFF protocol, not standard Spring Security session auth.
- Config via Spring Boot's standard `application.yml` + environment variable
  overrides, validated at startup (`@ConfigurationProperties` + `@Validated` /
  Bean Validation) so invalid/missing config fails fast with a readable error,
  matching the reference's `zod` validation behavior.
- An HTTP client for calling the Spring Boot API / IdP token endpoints (`RestClient`,
  `WebClient`, or similar).

## Build & Packaging (Maven)

Maven is the build tool for this project — no Gradle wrapper/build files. A single
`pom.xml` at the repo root, using `spring-boot-starter-parent` as `<parent>` so
plugin/dependency versions stay aligned with the Spring Boot BOM.

- **`groupId`/`artifactId`**: e.g. `com.toenshoffr` / `bff-java` (adjust to taste,
  but keep it consistent with the reference repo's naming where practical).
- **Java version**: set `<java.version>21</java.version>` (or the equivalent
  `maven.compiler.release`) so the parent POM configures the compiler plugin
  correctly.
- **Packaging: `war`**, not `jar`. This app must be deployable to a **standalone
  Tomcat** (see the README's "Deploying to Tomcat" section) as well as runnable
  standalone via an embedded server for local dev:
  - `<packaging>war</packaging>` in `pom.xml`.
  - The application's entry point class extends
    `org.springframework.boot.web.servlet.support.SpringBootServletInitializer`
    and overrides `configure(SpringApplicationBuilder)` to point at the same
    `@SpringBootApplication` class used by `main()`, so the exact same app boots
    whether it's launched with `java -jar` (embedded Tomcat) or deployed as a WAR
    into an external Tomcat's `webapps/` directory.
  - Mark `spring-boot-starter-tomcat` as `<scope>provided</scope>` — it's still on
    the classpath for local `mvn spring-boot:run` / `java -jar` runs (Spring Boot's
    repackaging keeps `provided` deps in the executable jar/war), but it is *not*
    bundled into the WAR that ships to an external Tomcat, which supplies its own
    Servlet container.
  - Keep `spring-boot-maven-plugin` in `<build><plugins>` (its `repackage` goal
    still produces an executable WAR usable with `java -jar`, on top of the plain
    deployable WAR Maven's own `war` packaging produces).
- **Standard build commands**:
  - `mvn clean verify` — compile, run tests, package.
  - `mvn clean package` — produces `target/bff-java-<version>.war` (a WAR
    deployable to Tomcat *and*, thanks to `spring-boot-maven-plugin` repackaging,
    runnable directly via `java -jar target/bff-java-<version>.war`).
  - `mvn spring-boot:run` — run locally with the embedded Servlet container,
    reading config from `application.yml` + env var overrides as usual.
- Core dependencies expected on the classpath by default (via Spring Boot
  starters, versions managed by the parent POM — don't pin versions individually
  unless overriding the BOM for a specific reason): `spring-boot-starter-web`,
  `spring-boot-starter-validation`, `spring-session-core`, `spring-boot-starter-security`
  (building blocks only, per the "Suggested Tech Stack" note above), and
  `spring-boot-starter-test` (+ e.g. `spring-security-test`, plus a stubbed-backend
  library such as WireMock) for tests.
- `spring-session-data-redis` + `spring-boot-starter-data-redis` are **not** on the
  default classpath — Spring Boot wires in a Redis-backed `SessionRepository`
  purely from those dependencies being present (there's no reliable property to
  keep them inert while present), so keep them behind an opt-in Maven profile
  (e.g. `mvn clean package -Predis`) rather than always-on, per "Production Notes".

## Features to Implement

### 1. Dual, independently-configurable auth methods

Controlled by `AUTH_METHODS` (comma-separated: `password`, `oauth`, or both). Both
can be mounted simultaneously; the Angular app chooses which flow to present.

**Username/password** (`POST /auth/login`)
- Accepts `{ "username": string, "password": string }`.
- Forwards to `POST {API_BASE_URL}{PASSWORD_LOGIN_PATH}`.
- Normalizes the response — accept both camelCase (`accessToken`/`refreshToken`/
  `expiresIn`) and snake_case (`access_token`/`refresh_token`/`expires_in`).
- On success: regenerate the session (mitigate fixation), store tokens + user in the
  session, respond `{ authenticated: true, user }`.
- On `400`/`401` from the backend: respond `401 { "error": "invalid_credentials" }`.
- Validate `username`/`password` are present non-empty strings before calling the
  backend; else `400`.

**OAuth Authorization Code + PKCE** (`GET /auth/oauth/login`, `GET /auth/oauth/callback`)
- `/login`: generate a random `state` and a PKCE `code_verifier` (store both,
  plus an optional `redirectTo` query param, in the session under an `oauthFlow`
  key), compute `code_challenge` = base64url(SHA-256(code_verifier)), redirect the
  browser to `OAUTH_AUTHORIZATION_ENDPOINT` with `response_type=code`,
  `client_id`, `redirect_uri`, `scope`, `state`, `code_challenge`,
  `code_challenge_method=S256`.
- `/callback`: validate `state` matches the stored flow and `code` is present
  (400 `invalid_oauth_state` otherwise); if the IdP returned an `error` query
  param, redirect to `OAUTH_POST_LOGIN_REDIRECT?authError=<error>` instead of
  erroring. On success, POST to `OAUTH_TOKEN_ENDPOINT`
  (`application/x-www-form-urlencoded`) with `grant_type=authorization_code`,
  `code`, `redirect_uri`, `client_id`, `code_verifier`, and `client_secret` if
  configured. Regenerate the session, store tokens, redirect to the flow's
  `redirectTo` or `OAUTH_POST_LOGIN_REDIRECT`.
- This is a confidential client (secret used on token exchange); PKCE is defense
  in depth on top of that, not a substitute for it.

### 2. Transparent token refresh

Before every proxied `/api/**` call (and nowhere else), ensure a fresh access
token for the caller's session:
- No session/tokens → throw/represent as unauthenticated (→ `401`).
- Token still valid (with ~10s skew before expiry) → reuse it.
- Token expired/near-expiry and a refresh token exists → refresh via whichever
  auth method established the session (`password` → `POST
  {API_BASE_URL}{PASSWORD_REFRESH_PATH}` with `{ refreshToken }`; `oauth` →
  `grant_type=refresh_token` against `OAUTH_TOKEN_ENDPOINT`), store the refreshed
  tokens back in the session, and continue.
- Refresh fails or no refresh token → treat as unauthenticated (session expired).

### 3. Authenticated reverse proxy (`ALL /api/**`)

- Resolve a fresh access token for the session (per above); `401 { "error":
  "unauthenticated" }` if none.
- Proxy the request to `API_BASE_URL`, rewriting the `API_PROXY_PATH` prefix away
  (e.g. BFF `/api/orders` → API `/orders`), injecting `Authorization: Bearer
  <jwt>`.
- Strip any `Access-Control-*` headers from the upstream response — CORS is
  decided solely by this BFF's own CORS config; passing upstream CORS headers
  through would overwrite (not merge with) the BFF's and break credentialed
  requests from the SPA.
- Support all HTTP methods, request/response bodies, and streaming (don't buffer
  large payloads into memory if avoidable).

### 4. CSRF protection — stateless double-submit cookie

- `GET /auth/csrf`: issue (or re-issue, if a `bff.csrf` cookie already exists) a
  random token; set it as a **non-httpOnly** cookie named `bff.csrf` (same
  `Secure`/`SameSite`/`maxAge` as the session cookie) and echo it in the JSON body
  as `{ "csrfToken": "..." }`.
- For every `POST`/`PUT`/`PATCH`/`DELETE` request (including `/auth/login` and
  anything proxied through `/api/**`), require the `X-CSRF-Token` header to match
  the `bff.csrf` cookie value; `403 { "error": "invalid_csrf_token" }` on
  mismatch/missing. `GET`/`HEAD`/`OPTIONS` are exempt.
- Must be disableable via `CSRF_PROTECTION_ENABLED=false` (testing/local curl only)
  — log a startup warning when disabled and never assume it's safe to leave off in
  a deployed environment.

### 5. Session-regeneration on login

Both auth flows must regenerate the session ID on successful login (before storing
tokens) to mitigate session fixation.

### 6. Config validation at startup

Fail fast with a readable, per-field error list (not a runtime crash later) if
required config is missing/invalid — in particular, the `OAUTH_*` fields become
required only when `AUTH_METHODS` includes `oauth`.

### 7. Security headers, CORS, trust proxy

- Equivalent of `helmet`'s default security headers (Spring Security's header
  writers, or manual).
- CORS: `origin = FRONTEND_ORIGIN` (exact match, not wildcard), credentials
  enabled.
- Correctly honor `X-Forwarded-*` (or Spring Boot's forwarded-headers strategy)
  so `secure` cookies and the request's perceived scheme are correct behind a
  reverse proxy/load balancer.

## Endpoints

| Method & path | Description |
|---|---|
| `GET /healthz` | Liveness check → `{ "status": "ok" }` |
| `GET /auth/csrf` | Issues a CSRF token (cookie + JSON body) |
| `GET /auth/status` | `{ authenticated, authMethod, user }` for the current session |
| `POST /auth/login` | Username/password login (if `password` enabled) |
| `GET /auth/oauth/login` | Starts the OAuth Authorization Code + PKCE flow (if `oauth` enabled) |
| `GET /auth/oauth/callback` | OAuth redirect URI — exchanges the code for tokens |
| `POST /auth/logout` | Destroys the session, clears the session cookie → `{ "authenticated": false }` |
| `ALL /api/**` | Authenticated reverse proxy to the Spring Boot API |

`GET /auth/status` with no session → `{ "authenticated": false }` (no `authMethod`/
`user` keys needed in that case, matching the reference).

## Session Data Model

Per HTTP session, keep:
- `authMethod`: `"password" | "oauth"` — which flow established the session
- `tokens`: `{ accessToken: string, refreshToken?: string, expiresAt: epoch-millis }`
- `user`: arbitrary object from the backend's login response (or `{ username }`
  fallback for password auth if the backend didn't return one)
- `oauthFlow` (transient, only during the OAuth redirect round-trip): `{ state,
  codeVerifier, redirectTo? }`

## Configuration

Keep the **same environment variable names** as the reference for drop-in
compatibility with existing `.env` files/deployment configs, mapped into Spring
Boot's config however is idiomatic (e.g. `@ConfigurationProperties` with
`@Value`/relaxed binding, or an `application.yml` that reads these as env
overrides):

| Variable | Purpose | Default |
|---|---|---|
| `PORT` | HTTP port | `3000` |
| `NODE_ENV` → rename `APP_ENV` or reuse Spring's `SPRING_PROFILES_ACTIVE` | Environment name | `development` |
| `FRONTEND_ORIGIN` | Exact Angular app origin (CORS) | required |
| `API_BASE_URL` | Spring Boot REST API base URL | required |
| `API_TIMEOUT_MS` | Upstream call timeout | `10000` |
| `API_PROXY_PATH` | BFF path prefix proxied to the API | `/api` |
| `AUTH_METHODS` | `password`, `oauth`, or `password,oauth` | `password` |
| `SESSION_SECRET` | Session cookie signing secret (≥16 chars) | required |
| `COOKIE_NAME` | Session cookie name | `bff.sid` |
| `COOKIE_SECURE` | `Secure` flag | `true` |
| `COOKIE_SAME_SITE` | `lax`\|`strict`\|`none` | `lax` |
| `COOKIE_MAX_AGE_MS` | Session cookie lifetime | `28800000` (8h) |
| `CSRF_PROTECTION_ENABLED` | Enable double-submit CSRF check | `true` |
| `PASSWORD_LOGIN_PATH` | Backend login endpoint | `/api/auth/login` |
| `PASSWORD_REFRESH_PATH` | Backend refresh endpoint | `/api/auth/refresh` |
| `OAUTH_AUTHORIZATION_ENDPOINT` | IdP authorize endpoint | required if `oauth` enabled |
| `OAUTH_TOKEN_ENDPOINT` | IdP token endpoint | required if `oauth` enabled |
| `OAUTH_END_SESSION_ENDPOINT` | IdP logout endpoint | optional |
| `OAUTH_CLIENT_ID` | OAuth client id | required if `oauth` enabled |
| `OAUTH_CLIENT_SECRET` | OAuth client secret | optional (public client if omitted) |
| `OAUTH_REDIRECT_URI` | Must point back at this BFF, not the Angular app | required if `oauth` enabled |
| `OAUTH_SCOPES` | Space-separated scopes | `openid profile email` |
| `OAUTH_POST_LOGIN_REDIRECT` | Where to send the browser after OAuth login | `/` |

(`PORT`/`NODE_ENV` naming can follow Spring Boot convention — e.g. `SERVER_PORT`,
`SPRING_PROFILES_ACTIVE` — since those aren't part of the frontend/backend wire
contract; everything else in this table **is** part of the contract and should
keep its exact name unless there's a strong reason to change it, documented in
the new repo's README.)

## Expected Spring Boot API Contract (unchanged)

- **Password login** (`POST {API_BASE_URL}{PASSWORD_LOGIN_PATH}`): accepts
  `{ "username": string, "password": string }`, returns
  `{ "accessToken": string, "refreshToken": string, "expiresIn": number, "user"?: object }`
  (snake_case also accepted).
- **Password refresh** (`POST {API_BASE_URL}{PASSWORD_REFRESH_PATH}`): accepts
  `{ "refreshToken": string }`, returns the same shape as login.
- **OAuth**: standard OAuth2/OIDC Authorization Code + `refresh_token` grants
  against the configured IdP.
- **Proxied API calls**: any JWT-secured endpoint under `API_BASE_URL`, reached
  via the BFF at `{API_PROXY_PATH}/<path>` (e.g. BFF `/api/orders` → API
  `/orders`).

## Production Notes

- Default session store must be safe for local dev only (in-memory); document
  clearly how to swap in a shared store (Redis) for multi-instance/production use.
  In practice that's a Maven build flag, not just an env var: Spring Boot wires in
  a Redis-backed `SessionRepository` as soon as `spring-session-data-redis` +
  `spring-boot-starter-data-redis` are on the classpath and a Redis connection is
  reachable — there's no supported property that keeps that wiring inert while
  the dependency is merely present. So the default build excludes both
  dependencies (in-memory sessions), and `mvn clean package -Predis` (see the
  `redis` profile in `pom.xml`) is the production build that adds them, alongside
  `SPRING_DATA_REDIS_HOST`/`SPRING_DATA_REDIS_PORT` for where to reach Redis.
- Must run behind TLS with `COOKIE_SECURE=true` in production.
- `COOKIE_SAME_SITE=none` only needed if Angular and the BFF are on different
  sites — document that same-site deployment is preferred.
- When deployed as a WAR into an external Tomcat sitting behind a reverse proxy
  or load balancer that terminates TLS, Tomcat itself (not just Spring) must be
  told to trust `X-Forwarded-*` — typically via Tomcat's `RemoteIpValve` in
  `server.xml`/`context.xml` — so `request.isSecure()`/the perceived scheme are
  correct upstream of Spring's own forwarded-headers handling. See the README's
  "Deploying to Tomcat" section.

## Non-Goals / Explicitly Out of Scope

- No change to the Angular integration contract (cookie names, header names,
  endpoint paths/shapes) unless unavoidable — if something must change, document
  it prominently in the new repo's README under a "Differences from the reference
  implementation" section.
- No new features beyond what's listed above — this is a language port, not a
  redesign. Resist adding abstractions, config options, or endpoints the
  reference doesn't have.
- No requirement to reuse any of the reference's TypeScript code, only its
  documented behavior.

## Suggested Java Project Layout

Mirror the reference's organization by responsibility (adapt names to Java/Spring
conventions):

```
pom.xml                             packaging=war, spring-boot-starter-parent
src/main/resources/
└── application.yml                 config defaults, env var overrides
src/main/java/.../bff/
├── BffApplication.java             Spring Boot entry point,
│                                   extends SpringBootServletInitializer
├── config/
│   └── BffProperties.java          @ConfigurationProperties, validated
├── auth/
│   ├── PasswordAuthController.java
│   ├── OAuthController.java
│   └── TokenService.java           normalization + transparent refresh
├── web/
│   ├── CsrfFilter.java
│   ├── SecurityHeadersConfig.java
│   └── GlobalErrorHandler.java
├── proxy/
│   └── ApiProxyController.java     (or a Filter, depending on approach)
└── session/
    └── SessionModel.java           typed session attribute holder(s)
```

## Acceptance Checklist

A rewrite is complete when, against a real (or stubbed) Spring Boot API and IdP:

- [ ] `GET /healthz` returns `200 { status: "ok" }` with no session.
- [ ] Password login round-trip: bad credentials → `401`; good credentials → session
      cookie set, `GET /auth/status` reflects `authenticated: true`.
- [ ] OAuth login round-trip: `/oauth/login` redirects to the IdP with correct PKCE
      params; `/oauth/callback` completes the exchange and establishes a session;
      `state` mismatch → `400`.
- [ ] `GET /auth/csrf` sets a readable cookie and returns a matching token; a
      mutating request without/with a mismatched `X-CSRF-Token` → `403`; with a
      matching token → passes through to the handler.
- [ ] `CSRF_PROTECTION_ENABLED=false` disables the check and logs a startup warning.
- [ ] `ALL /api/**` with no session → `401`; with a session → request reaches the
      backend with `Authorization: Bearer <jwt>` and the `API_PROXY_PATH` prefix
      stripped.
- [ ] An expired access token is silently refreshed on the next `/api/**` call
      without the caller seeing a `401`; an expired refresh (or missing refresh
      token) surfaces as `401`.
- [ ] `POST /auth/logout` destroys the session and clears the session cookie.
- [ ] Missing/invalid required env vars (e.g. missing `SESSION_SECRET`, or
      `oauth` enabled without `OAUTH_CLIENT_ID`) fail startup with a readable
      per-field error instead of starting in a broken state.
- [ ] Upstream `Access-Control-*` response headers never reach the browser.
