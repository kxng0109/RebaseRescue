# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- GitHub App integration (opt-in `GITHUB_ENABLED`): webhook `POST /api/webhooks/github` (HMAC `sha256=` over raw bytes, `MessageDigest.isEqual`, `X-GitHub-Delivery` dedup 30d, `ping` → `pong`, 202 within 10s), virtual-thread async to fetch diff (`Accept: application/vnd.github.diff`) → `analyzeDiff` → post review (`line`+`side`, `REQUEST_CHANGES` iff error) + SARIF upload (`gzip`→`base64`, `automationDetails.id` = category, poll to `complete`). JWT `RS256` via `nimbus-jose-jwt 10.9.1` + `bcprov/bcpkix 1.84`, installation tokens cached 55m. `X-GitHub-Api-Version: 2026-03-10`.
- 62 new tests for GitHub flow (HMAC vector `sha256=757107ea…b043e17`, dedup, payload, JWT, controller MockMvc, service, API client). GitHub package temporarily excluded from per-class JaCoCo gate (bundle still 90%+).
- Webhook hardening: `RequestSizeLimitFilter` now caps `POST /api/webhooks/github` at `GITHUB_WEBHOOK_MAX_REQUEST_BYTES` (default 1MB) before HMAC work, 413 on excess; `X-Request-ID` echoed only on pattern match; `github-webhook` rate limiter (60 per 1m, 429 fallback) keeps the 10s delivery deadline.
- Strict webhook replay defense plus backpressure: delivery dedup claims are atomic with exactly-one-winner under concurrency; background work runs on a bounded virtual-thread pool (4 core, 16 max, 100 queue) that rejects fast to 429 on saturation instead of growing without bound.

### Fixed

- `RequestSizeLimitFilter` 413 body no longer reflects arbitrary `X-Request-ID` values (response-injection surface).

## [1.1.0] - 2026-09-10

### Added

- Byte-level request body cap (`RequestSizeLimitFilter`, 413) enforced before
  JSON deserialization; configurable via `PRCOPILOT_ANALYSIS_MAX_REQUEST_BYTES`
- Configurable ceilings on model-provided list sizes (`PRCOPILOT_ANALYSIS_MAX_RISKS`,
  `PRCOPILOT_ANALYSIS_MAX_SUGGESTED_TESTS`, `PRCOPILOT_ANALYSIS_MAX_TOUCHED_FILES`)
- Opt-in provider reachability ping at startup (`PRCOPILOT_AI_HEALTH_CHECK_PING`,
  warn-only, never fails startup)
- `X-Request-ID` correlation header echoed back as `requestId` in error responses;
  structured `validationErrors` map on 400s
- Explicit deny-by-default CORS configuration; `/api/v1/**` restricted to POST
- Deterministic adversarial evaluation harness (`GuardrailEvalTest`) measuring
  secret-scan recall, obfuscation bypass, and injection denylist coverage
  against a pinned baseline; emits `target/security-eval.json`
- Opt-in provider smoke tests (`ProviderSmokeTest`) that run only when
  `AI_PROVIDER_KEY` is set, so credential-less CI never touches a network

### Changed

- `prcopilot.auth.mode` and `prcopilot.sarif.consumer` are now enums
  (`SELFHOST`/`PROD`, `GITHUB`/`SONAR`); invalid values fail startup binding
- Removed duplicate `prcopilot.ai.ratelimit-*` properties; `resilience4j.ratelimiter`
  is the single source of truth
- `PRCOPILOT_CACHE_DIFF_MAX_SIZE=0` now actually disables the response cache
- Provider API keys no longer default to the `"default-value"` placeholder;
  missing keys fail fast via `AppStartupCheck`

### Testing

- JaCoCo 0.8.15 gate: ≥90% line and branch coverage enforced per class and
  bundle at `verify` (build fails otherwise); sole exclusion is the
  application bootstrap class
- ~260 new tests: slice/unit coverage for filters, guardrails, mappers,
  services, CLI, and configuration boundaries (386 total, 0 failures)
- `CliRunner` now propagates exit codes via Spring's `ExitCodeGenerator`
  (graceful shutdown) instead of calling `System.exit` directly
- Fixed `final` picocli `@Option` fields that rejected every CLI invocation;
  adopted Spring 7 `CONTENT_TOO_LARGE` / `UNPROCESSABLE_CONTENT` constants

### Security

- Secret scanner now detects obfuscated secrets (base64, ROT13, and
  zero-width-character variants), closing a bypass where disguised tokens
  were previously missed. Detection views are derived from the original
  text only, so decoding can never expose a secret.
- Adversarial security evaluation now runs automatically on pull requests
  via the `security-eval` CI job, failing on a real regression while
  allowing a documented, pinned gap to stay green.

## [1.0.0] - 2026-09-10

### Features

- Migrate to Spring Boot 4.1.1, Spring AI 2.0.1 and Jackson 3
- Google Gemini via Google GenAI (Vertex mode) replacing Vertex AI starter
- Deny-by-default security: prod OIDC and selfhost API-key modes
- Input guardrail advisor with secret pre-scan (block, redact, warn tiers)
- SARIF 2.1.0 output with suppress file and minimum-level gate
- SSE token-streaming analysis endpoint
- Diff-hash response cache with per-provider bulkheads and rate limiting
- Micrometer telemetry and Prometheus metrics
- AOT-cache container images with native-image opt-in profile
- Versioned fat JAR for CLI/air-gap use and signed Docker image for server use
- CLI via Boot-4 Picocli auto-configuration

### Bug Fixes

- Maven wrapper now runs on Linux runners (`bash ./mvnw`)
- CycloneDX aggregate SBOMs (JSON + XML) attached to releases
- Trivy scans built artifacts under `target/` instead of the source tree
- Tomcat upgraded to 11.0.25 (CVE-2026-65182, CVE-2026-65905, CVE-2026-68525)
- Docker base images and Ollama pinned by sha256 digest

### Security

- Keyless Cosign signing for JAR and image
- SLSA provenance attestations uploaded to Rekor transparency log
- Trivy HIGH/CRITICAL gates on both filesystem and image scans
- SHA-pinned GitHub Actions with least-privilege per-job permissions
- Dependabot configured for security-only auto-merges

<!-- generated by git-cliff -->