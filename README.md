# AI PR Copilot

A self hosted AI powered code audit and pull request analysis service with multi provider support. This REST API
analyzes Git diffs using language models to provide structured reviews, identify risks, and suggest test cases.

[![CI](https://github.com/kxng0109/AI-PR-Copilot/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/kxng0109/AI-PR-Copilot/actions/workflows/ci.yml)

> Note: This project is under active development. Features and APIs may change as the project evolves.

## Table of Contents

- [Features](#features)
- [Supported AI Providers](#supported-ai-providers)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
    - [Clone the Repository](#clone-the-repository)
    - [Configuration](#configuration)
    - [Running the Application](#running-the-application)
- [API Documentation](#api-documentation)
    - [Analyze Diff Endpoint](#analyze-diff-endpoint)
    - [Example Request](#example-request)
    - [Example Response](#example-response)
- [Configuration Reference](#configuration-reference)
    - [AI Provider Configuration](#ai-provider-configuration)
    - [Analysis Settings](#analysis-settings)
    - [Fallback Configuration](#fallback-configuration)
- [Provider Setup Guides](#provider-setup-guides)
    - [OpenAI](#openai)
    - [Anthropic Claude](#anthropic-claude)
    - [Google Gemini](#google-gemini)
    - [Ollama](#ollama)
- [Error Handling](#error-handling)
- [Health Checks](#health-checks)
- [CI](#ci)
- [Testing](#testing)
- [Architecture Overview](#architecture-overview)
- [License](#license)

## Features

- Multi provider AI support: OpenAI, Anthropic Claude, Google Gemini, Ollama
- Optional automatic fallback between providers
- Structured code analysis: title, summary, details, risks, suggested tests, touched files, metadata
- Configurable language, style, temperature, token limits, and raw model output inclusion
- Validation and diff size limits with centralized error handling
- OpenAPI documentation via Swagger UI
- Startup validation for provider configuration

## Supported AI Providers

| Provider      | Models                                    | Authentication                                  |
|---------------|-------------------------------------------|-------------------------------------------------|
| OpenAI        | GPT-4o, GPT-4o-mini                       | API Key                                         |
| Anthropic     | Claude Sonnet 4, Claude Opus 4            | API Key                                         |
| Google Gemini | Gemini 2.5 Flash (via Google GenAI)       | GCP Project ID + location + ADC (Vertex mode)   |
| Ollama        | Local models (Qwen, Llama, Mistral, etc.) | Local installation                              |

Stack: Java 25 · Spring Boot 4.1.1 · Spring AI 2.0.1 · Jackson 3 (ISO-8601 dates) ·
Spring Security 7 (OIDC in `prod`, API key in `selfhost`) · Picocli 4.7.7
(Boot-4 factory vendored in `cli.picocli4`) · springdoc 3.1.0.

Versioning: single source is `pom.xml` `project/version` (current `1.1.0`).
It propagates to `application.yml` (`info.project.version`,
`spring.application.version` via `@project.version@` resource filtering),
`config.AppInfo` (code), Docker label/tag (`APP_VERSION` build arg,
`APP_VERSION` env tag), and the release workflow (git tag `v*.*.*`).
`config.AppVersionTest` fails the build if `AppInfo` drifts from the POM —
bump both together; Docker image/Compose default to the same version.

## Prerequisites

- Java 25 or higher
- Maven 3.6 or higher
- API key for at least one provider or a running Ollama instance

## Getting Started

### Clone the Repository

```bash
git clone https://github.com/kxng0109/ai-pr-copilot.git
cd ai-pr-copilot
```

### Configuration

1) Copy the example environment file:

```bash
cp .env.example .env
```

2) Edit `.env` and set your provider credentials. Minimal example:

```bash
PRCOPILOT_AI_PROVIDER=openai
OPENAI_API_KEY=sk-your-openai-key-here
```

See [Provider Setup Guides](#provider-setup-guides) for details per provider.

### Running the Application

Build and run:

```bash
mvn clean install
mvn spring-boot:run
```

Default base URL: `http://localhost:8080`

Verify health:

```bash
curl http://localhost:8080/actuator/health
```

## API Documentation

Swagger UI:

```
http://localhost:8080/swagger-ui.html
```

OpenAPI spec:

```
http://localhost:8080/api-docs
```

### Analyze Diff Endpoint

- Method: `POST /api/v1/analyze-diff`
- Content Type: `application/json`
- Rate-limited (30/min default) + provider bulkheads (fail-fast 429); repeated diffs served from diff-hash cache.
- Input guardrail on every LLM call (both blocking and streaming paths): size cap,
  instruction-override denylist, and secret pre-scan. High-confidence secrets
  (provider IDs, PEM blocks) hard-block with `400` and zero LLM cost; generic
  high-entropy assignments are redacted and analysis continues; PII-shaped content
  only warns. Scan failures fail closed.

### Example Request

```bash
curl -X POST http://localhost:8080/api/v1/analyze-diff \
  -H "Content-Type: application/json" \
  -d '{
    "diff": "diff --git a/src/main/UserService.java b/src/main/UserService.java\nindex abc123..def456 100644\n--- a/src/main/UserService.java\n+++ b/src/main/UserService.java\n@@ -10,7 +10,7 @@ public class UserService {\n-    public User getUser(String id) {\n-        return database.findById(id);\n+    public User getUser(String id) throws UserNotFoundException {\n+        return database.findById(id).orElseThrow(() -> new UserNotFoundException(id));\n     }\n }",
    "language": "en",
    "style": "conventional-commits",
    "maxSummaryLength": 500,
    "requestId": "req-12345"
  }'
```

### Example Response

```json
{
	"title": "refactor: improve error handling in UserService",
	"summary": "Modified getUser method to throw UserNotFoundException when user is not found instead of returning null.",
	"details": "The getUser method signature now includes a throws clause for UserNotFoundException. The implementation uses Optional.orElseThrow() to raise an exception when the database query returns empty, replacing the previous behavior of returning null.",
	"risks": [
		{"level": "error", "message": "Breaking change: existing callers must now handle UserNotFoundException"},
		{"level": "warning", "message": "No null checks are present in the diff; ensure all callers are updated"},
		{"level": "note", "message": "UserNotFoundException is not defined in this diff; verify it exists in the codebase"}
	],
	"riskScore": 37,
	"suggestedTests": [
		"Test that getUser throws UserNotFoundException when user does not exist",
		"Test that getUser returns valid User object when user exists",
		"Integration test to verify exception propagates correctly through call stack"
	],
	"touchedFiles": [
		"src/main/UserService.java"
	],
	"analysisNotes": "This is a common refactoring pattern to improve error handling. Ensure backward compatibility is considered if this is a public API.",
	"metadata": {
		"modelName": "gpt-4o",
		"provider": "openai",
		"modelLatencyMs": 1247,
		"tokensUsed": 312
	},
	"requestId": "req-12345",
	"rawModelOutput": null
}
```

**Request Parameters:**

| Field              | Type    | Required | Description                                     |
|--------------------|---------|----------|-------------------------------------------------|
| `diff`             | string  | Yes      | Git diff in unified format                      |
| `language`         | string  | No       | Analysis language (default `en`)                |
| `style`            | string  | No       | Analysis style (default `conventional-commits`) |
| `maxSummaryLength` | integer | No       | Maximum summary length                          |
| `requestId`        | string  | No       | Optional request identifier                     |

**Response Fields:**

| Field            | Type   | Description                              |
|------------------|--------|------------------------------------------|
| `title`          | string | Short technical title of the change      |
| `summary`        | string | Concise description of what changed      |
| `details`        | string | Detailed breakdown of the implementation |
| `risks`          | array  | `{level: error\|warning\|note, message}`  |
| `riskScore`      | number | Deterministic 0-100 (error=25, warning=10, note=2, capped) |
| `suggestedTests` | array  | Recommended test cases                   |
| `touchedFiles`   | array  | Modified files                           |
| `analysisNotes`  | string | Additional notes or caveats              |
| `metadata`       | object | AI call metadata                         |
| `requestId`      | string | Echo of the request ID                   |
| `rawModelOutput` | string | Raw model output if enabled              |

### SARIF Endpoint

- Method: `POST /api/v1/analyze-diff/sarif`
- Content Type out: `application/sarif+json` (SARIF 2.1.0)
- Same analysis, stable `ruleId` (`APR-<hash>`), `primaryLocationLineHash` fingerprints,
  `runAutomationDetails.id` category. Upload to GitHub Code Scanning
  (`github/codeql-action/upload-sarif`, `category: ai-pr-copilot`) or SonarQube
  (`sonar.sarifReportPaths`). Findings below `PRCOPILOT_GATE_MIN_LEVEL`
  (`note|warning|error`) are filtered at emit time; entries in
  `PRCOPILOT_SUPPRESS_FILE` (default `.ai-review-ignore.yml`, YAML list of
  `{ruleId|fingerprint, reason, expires: YYYY-MM-DD}`) are excluded.

### Streaming Endpoint

- Method: `POST /api/v1/analyze-diff/stream`
- Content Type out: `text/event-stream`
- Events: `started`, per-chunk `token`, `result`, `done` (`live|cached`), `error`.
  Cache hits emit the stored result immediately without an AI call.

## Configuration Reference

Configuration can be set via environment variables or `application.yml`. See `.env.example` for the full list.

### AI Provider Configuration

```bash
PRCOPILOT_AI_PROVIDER=openai
AI_TEMPERATURE=0.1
AI_MAX_TOKENS=1024
AI_TIMEOUT_MILLIS=30000
```

### Analysis Settings

```bash
PRCOPILOT_ANALYSIS_MAX_DIFF_CHARS=50000
PRCOPILOT_ANALYSIS_MAX_REQUEST_BYTES=262144
PRCOPILOT_ANALYSIS_MAX_MODEL_OUTPUT_CHARS=1000000
PRCOPILOT_ANALYSIS_MAX_RISKS=200
PRCOPILOT_ANALYSIS_MAX_SUGGESTED_TESTS=100
PRCOPILOT_ANALYSIS_MAX_TOUCHED_FILES=500
PRCOPILOT_ANALYSIS_DEFAULT_LANGUAGE=en
PRCOPILOT_ANALYSIS_DEFAULT_STYLE=conventional-commits
PRCOPILOT_ANALYSIS_INCLUDE_RAW_MODEL_OUTPUT=false
PRCOPILOT_CACHE_DIFF_MAX_SIZE=1000
PRCOPILOT_CACHE_DIFF_TTL=30m
PRCOPILOT_GATE_MIN_LEVEL=note
```

`PRCOPILOT_ANALYSIS_MAX_REQUEST_BYTES` is a hard byte ceiling on `/api/v1/**`
JSON bodies enforced before deserialization (413). `PRCOPILOT_CACHE_DIFF_MAX_SIZE=0`
disables the response cache. Rate limiting is configured only via the
`resilience4j.ratelimiter` properties (`PRCOPILOT_RATELIMITER_*` env vars).

### Responsiveness / Resources (all optional)

```bash
SERVER_TOMCAT_THREADS_MAX=30
SPRING_THREADS_VIRTUAL_ENABLED=true
PRCOPILOT_AI_BULKHEAD_SAAS_MAX_CONCURRENT=15
PRCOPILOT_AI_BULKHEAD_OLLAMA_MAX_CONCURRENT=3
PRCOPILOT_RATELIMITER_LIMIT_FOR_PERIOD=30
PRCOPILOT_RATELIMITER_REFRESH_PERIOD=60s
SPRING_HTTP_CLIENTS_CONNECT_TIMEOUT=5s
SPRING_HTTP_CLIENTS_READ_TIMEOUT=40s
JAVA_MAX_RAM_PERCENTAGE=70
```

Prometheus metrics at `/actuator/prometheus`: `aiprcopilot.analysis.duration`
(`provider`, `outcome`), `aiprcopilot.analysis.cache` (`hit|miss`).

### Images

- Default: JVM + layered + AOT cache (trained in Docker build; `AOTCACHE_ENABLED=0` skips).
- Opt-in native: `mvn -Pnative package` (GraalVM; gated on provider×fallback smoke tests).

### Fallback Configuration

```bash
PRCOPILOT_AI_AUTO_FALLBACK=true
PRCOPILOT_AI_FALLBACK_PROVIDER=anthropic
OPENAI_API_KEY=sk-your-openai-key
ANTHROPIC_API_KEY=sk-ant-your-anthropic-key
```

## Provider Setup Guides

### OpenAI

```bash
PRCOPILOT_AI_PROVIDER=openai
OPENAI_API_KEY=sk-your-openai-key-here
OPENAI_MODEL=gpt-4o
```

### Anthropic Claude

```bash
PRCOPILOT_AI_PROVIDER=anthropic
ANTHROPIC_API_KEY=sk-ant-your-anthropic-key-here
ANTHROPIC_MODEL=claude-sonnet-4-0
```

### Google Gemini (Google GenAI, Vertex mode)

```bash
PRCOPILOT_AI_PROVIDER=gemini
GOOGLE_GENAI_PROJECT_ID=your-gcp-project-id
GOOGLE_GENAI_LOCATION=us-central1
GOOGLE_GENAI_MODEL=gemini-2.5-flash
```

Legacy `GEMINI_*` names still work as fallback. Authenticate with Application Default
Credentials (`GOOGLE_APPLICATION_CREDENTIALS`). `GOOGLE_GENAI_API_KEY` is prototyping
only (no residency/ZDR guarantees) — never use it for production diffs.

Follow Google Cloud Vertex AI documentation for authentication and project setup.

### Ollama

```bash
docker run -d -p 11434:11434 ollama/ollama
ollama pull qwen3:4b
PRCOPILOT_AI_PROVIDER=ollama
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_MODEL=qwen3:4b
```

## Error Handling

Structured errors via `GlobalExceptionHandler`:

- 400 for validation errors (field details in `validationErrors`) or unreadable body
- 404 for unknown endpoint
- 405 for unsupported method
- 413 for oversized request body or diff
- 422 for invalid model output
- 429 for rate limit or provider concurrency exceeded
- 500 for unexpected errors
- 502 or 504 for upstream access or timeout

Pass `X-Request-ID` (1-64 chars of letters, digits, `-`, `_`) to correlate
errors; the value is echoed back as `requestId`. The `requestId` body field
accepts the same shape.

Example:

```json
{
	"timestamp": "...",
	"statusCode": 400,
	"error": "Bad Request",
	"message": "Validation failed",
	"path": "/api/v1/analyze-diff",
	"requestId": null,
	"validationErrors": {
		"diff": "Diff must not be blank"
	}
}
```

## Authentication

Deny-by-default. Public only: `/actuator/health`, `/actuator/info`, `/api-docs/**`, `/swagger-ui/**`.

- `selfhost` (default, regular users): pre-shared API key, no IdP.
  ```bash
  PRCOPILOT_AUTH_MODE=selfhost
  PRCOPILOT_API_KEY=change-me-to-a-long-random-value
  curl -H "Authorization: Bearer $PRCOPILOT_API_KEY" http://localhost:8080/api/v1/analyze-diff ...
  ```
  Empty key keeps `/api/v1/**` locked (401). CLI `--quiet --format json|compact` for scripting.
- `prod` (enterprise): OIDC JWT required, fail-closed with no default issuer.
  ```bash
  PRCOPILOT_AUTH_MODE=prod
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://auth.example.com/realms/ai-pr-copilot
  ```

## Health Checks

The service exposes Spring Boot Actuator endpoints for monitoring:

**Health Check:**

```bash
curl http://localhost:8080/actuator/health
```

**Application Info:**

```bash
curl http://localhost:8080/actuator/info
```

## CI

`ci.yml` runs on pushes and pull requests. `build-and-test` runs `mvn -B verify`
and uploads `target/`; `trivy` scans the built artifacts (never the source tree —
the Maven pom parser is a known crash/429 source) with `scanners: vuln`,
`severity: HIGH,CRITICAL`, `ignore-unfixed: true`, `exit-code: 1`.
A separate `trivy-cache-warm.yml` pulls the Trivy DB + Java DB nightly via
`oras pull` so scans run with `TRIVY_SKIP_DB_UPDATE=true`.

All third-party actions are SHA-pinned with a version comment (currently
`actions/checkout@v7.0.1`, `actions/setup-java@v6.0.1`,
`github/codeql-action@v4.38.0`). Dependabot watches actions, Maven and Docker
weekly but only auto-merges **security** updates; version bumps need manual
review because it cannot rewrite a pinned SHA.

## Releases

Tag-driven: push `vX.Y.Z` (stable) or `vX.Y.Z-rc.N` (prerelease). The tag must
equal `pom.xml` `project.version` or the pipeline fails fast. `release.yml`
then runs test → Trivy gates → build + checksum + smoke → image push →
Cosign keyless signing + SLSA attestations → publishes the GitHub Release
immediately with the full conventional changelog (`cliff.toml`,
repo-kept `CHANGELOG.md`).

Every release attaches: versioned JAR + `.sha256`, CycloneDX aggregate SBOM
(`bom.json` + `bom.xml`), image SBOM, Cosign bundle (`.sigstore.json`),
attestation bundle. Images land in GHCR (`X.Y.Z`, `X.Y`, `X`, plus `latest`
for stable only). `-rc` tags publish as prereleases and never touch `latest`.
Verify a JAR with `sha256sum -c` and the `cosign verify-blob` command
printed in the release notes.

## Architecture Overview

- Controller: `DiffAnalysisController` (`/analyze-diff`, `/analyze-diff/sarif`, `/analyze-diff/stream`) + `GithubWebhookController` (`/api/webhooks/github`)
- Services: `DiffAnalysisService` (cache+bulkhead+telemetry), `AiChatService` (virtual-thread executor, SSE flux), `PromptBuilderService` (cached template), `DiffResponseMapperService`, `GitService`, `SarifService`, `AnalysisMetrics`, `SecretScanService`, `DiffGuardrailAdvisor` (Call+StreamAdvisor, highest precedence), `GithubApiClient` + `GithubWebhookService` + `GithubAppAuthService` + `DeliveryDedupStore`
- Configuration and validation: `MultiAiConfigurationProperties`, `PrCopilotAnalysisProperties`,
  `PrCopilotLoggingProperties`, `PrCopilotAuthProperties`, `PrCopilotSarifProperties`, `GithubProperties`, startup checks in `AppStartupCheck`
- Security: `SecurityConfig` (prod OIDC / selfhost API key, webhook HMAC), `ApiKeyAuthFilter` (constant-time compare), `DeliveryDedupStore` (replay defense)
- CLI: `CliRunner`, `AnalyzeCommand` (`--base/--staged/--uncommitted/--format/--quiet`), Boot-4 factory in `cli.picocli4`
- Error handling: `GlobalExceptionHandler`
- Uses Spring AI 2.0 to switch between providers (Google Gemini via `GoogleGenAiChatModel`, Vertex mode)

### GitHub App Integration (opt-in, `GITHUB_ENABLED=true`)

Manual GitHub App (Developer settings → New GitHub App, *Only on this account*). Minimum permissions: **Pull requests Read & write** + **Security events Read & write**; event: `pull_request`. All calls send `X-GitHub-Api-Version: 2026-03-10`.

```
GITHUB_ENABLED=true
GITHUB_APP_ID=123456
GITHUB_APP_CLIENT_ID=Iv1.xxxxx         # preferred over numeric ID for JWT iss
GITHUB_APP_PRIVATE_KEY="-----BEGIN RSA PRIVATE KEY-----..."  # or file:/path/key.pem
GITHUB_APP_INSTALLATION_ID=12345678
GITHUB_WEBHOOK_SECRET=random-32+ bytes
GITHUB_WEBHOOK_MAX_REQUEST_BYTES=1048576
GITHUB_WEBHOOK_RATELIMITER_LIMIT_FOR_PERIOD=60
GITHUB_API_BASE_URL=https://api.github.com   # must match GITHUB_API_ALLOWED_HOSTS
GITHUB_API_ALLOWED_HOSTS=api.github.com   # comma-separated; add an Enterprise Server FQDN to opt in
GITHUB_SARIF_CATEGORY=ai-pr-copilot
```

Startup fails closed when `GITHUB_ENABLED=true` without a webhook secret or with a non-allowlisted base URL. Flow: webhook `POST /api/webhooks/github` (size cap 1MB via `RequestSizeLimitFilter` before HMAC, 413 on excess, `X-Request-ID` echoed only on pattern match, `@RequestBody byte[]` raw for HMAC `sha256=` + `MessageDigest.isEqual`, 403 on mismatch, `X-GitHub-Delivery` atomic dedup via `asMap().putIfAbsent` 30d, `ping` → `pong`, `github-webhook` rate limiter 60 per 1m with 429 fallback) → 202 within 10s onto bounded virtual-thread pool (4 core, 16 max, 100 queue, abort to 429 on saturation) → virtual-thread async: fetch diff (`Accept: application/vnd.github.diff`), `analyzeDiff`, post review (`line`+`side`, never deprecated `position`, `REQUEST_CHANGES` iff error-level risks) + SARIF upload (`gzip`→`base64`, `automationDetails.id` = category, poll to `complete`). JWT: `RS256` (keys floored at 2048 bits), `iss` = clientId/appId, `iat` = now-60s, `exp` = now+9m, `nimbus-jose-jwt 10.9.1` + `bcprov/bcpkix 1.86`. Tokens cached 55m (GitHub TTL 1h, stateless `ghs_APPID_JWT` format); API calls share one `RestClient` and retry once after token eviction on 401.

Local dev: use a tunnel (ngrok/21tunnel) — smee.io is flaky per upstream. `DeliveryDedupStore` is in-memory Caffeine (single instance, best-effort dedup; idempotent analysis so rare double-process is safe).

## Testing

`mvn test` runs 448 tests (0 failures). Coverage is enforced by JaCoCo 0.8.15:
**≥90% line and branch coverage per class and bundle**, failing the build
otherwise. The application bootstrap class is the sole exclusion (wiring only,
verified by context-load instead); the `github` package is temporarily excluded from the per-class gate (62 new tests, bundle still 90%+).

```powershell
.\mvnw.cmd test "-Dtest=SecretScanServiceTest" "-Djacoco.skip=true" -q  # single class, no gate
.\mvnw.cmd clean verify -q                                              # full suite + gate (CI must use this)
```

Reports land in `target/site/jacoco/` (`index.html`, `jacoco.csv`, `jacoco.xml`).
Two additional suites exist:

**Provider smoke tests** (`ProviderSmokeTest`) — hit REAL provider endpoints.
Opt-in only: the class runs solely when `AI_PROVIDER_KEY` is set, so a
credential-less CI run never touches a network and never leaks a key into
a report. Keys arrive via env vars, never committed. Each test makes exactly
one real call and asserts on response shape, never on model content.

**Security eval harness** (`GuardrailEvalTest`) — deterministic, LLM-free
adversarial evaluation. Measures secret-scan recall, obfuscation bypass, and
injection denylist coverage, then asserts only that the current numbers do
not regress below a pinned baseline. A JSON report is written to
`target/security-eval.json`. Per OWASP LLM01:2026, deterministic prevention
of prompt injection is impossible, so the harness reports bypass rate rather
than claiming zero bypasses. Current measurements: secret-scan 6/7,
obfuscation 3/3, injection 3/5.

## License

This project is under active development. License information will be added in a future release.

---
Project Status: Active Development. For questions or issues, please open an issue on the repository.