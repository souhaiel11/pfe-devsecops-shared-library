# Governance boundary

**Jenkins produces facts. WF1 makes governance decisions.**

## What this Shared Library reports (facts)

- `buildStageStatus.{build,tests,sonar,trivy,owasp,zap,docker}`: one of
  `SUCCESS | FAILED | UNKNOWN | NOT_REACHED | COMPLETED` (scanner stages)
- `tests.{status,total,failures,skipped,coverage}` — `status` truthfully one
  of `SUCCESS | FAILED | SKIPPED | UNKNOWN | NOT_REACHED`, never a fabricated
  `PASSED` for a skipped or unparseable run
- `docker.{build_status,image_tag,push_status}`
- `sonar.{ceTaskId,analysisId}`
- `technicalFailure.{phase,technicalCode,message}` — a raw diagnostic code
  such as `SCM_CHECKOUT_NETWORK_FAILURE`, **never** a `problemClass`, owner,
  or route
- `reports.available.{trivy,zap,owasp}` — existence only, never a finding
  count Jenkins didn't actually parse
- `severity_hint` — explicitly documented as informational only; WF1 already
  ignores it for its canonical calculation

## What this Shared Library never computes or triggers

- `problemClass`, `owner`, routing (`AUTO_FIX_ELIGIBLE`,
  `DEVELOPER_ACTION_REQUIRED`, `ADMIN_ACTION_REQUIRED`, ...)
- deployment readiness, risk level, `securityScore`, Judge verdict
- WF2 (patch/PR), WF3 (post-PR validation), WF4 (approval/optimizer), WF5
  (deploy) — none of these are invoked from Jenkins or this library, ever
- PR creation of any kind

## Enforcement

The only outbound call to the platform is the single `curl -X POST
"$N8N_WEBHOOK_URL"` in `PlatformReporter.send()`
(`src/org/pfe/devsecops/PlatformReporter.groovy`), carrying the factual
payload above. Every other `http://` occurrence in this codebase is either
the in-pod ZAP local API (`http://127.0.0.1:8090`, never leaves the ZAP pod)
or the `zapTargetUrl` convention string (the *project's own* deployed
service address, not a platform endpoint). No WF2/WF3/WF4/WF5 webhook,
PR API, or deployment endpoint is called from anywhere in this library.
