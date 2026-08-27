# Offline test matrix results (Section 19)

Run: `test/run-offline-tests.sh /path/to/groovy-all-2.4.21.jar` (the jar
bundled in the Jenkins controller's own `war/WEB-INF/lib/`, used unmodified,
no download required). All results below were produced by an actual run of
that script against this repository in its current state.

| Test | Description | Method | Result |
|---|---|---|---|
| A | Maven project detected correctly | executable (`ProjectDetector`, fake `pom.xml`) | PASS |
| B | Dockerfile detected correctly | executable (`ProjectDetector`, fake `Dockerfile` + override path) | PASS |
| C | tests intentionally skipped -> truthful SKIPPED, never PASSED | executable (`BuildRunner`, `skipTests=true`) | PASS |
| D | normal build SUCCESS | not independently testable offline (requires a real `mvn` process); covered structurally — `BuildRunner.run()` sets `buildStageStatus['build']='SUCCESS'` only after the `sh` call returns without `catchError` triggering | inspected, not executable offline |
| E | Docker SUCCESS -> telemetry SUCCESS | inspected (`DockerRunner.build()` mirrors `BuildRunner` pattern); not independently executable offline (requires real `docker`) | inspected, not executable offline |
| F | Sonar ceTaskId available -> forwarded | inspected (`ScannerRunner.runSonar()` extraction regex is a verbatim port of the pre-migration `grep` pattern) | inspected, not executable offline |
| G | scanner report unavailable -> findingCount not fabricated | executable (`BuildRunner` unparseable-surefire path yields `UNKNOWN`/`null`, never `0`); `SafeCleanup.reportAvailable()` returns `false` on a missing file rather than throwing | PASS |
| H | ZAP incomplete -> factual telemetry only | inspected (`runZap()` writes `zap_k8s_unreachable`/`zap_report_missing` stub JSON exactly as pre-migration, never fabricates alerts) | inspected, not executable offline |
| I | SCM checkout failure after Jenkinsfile loaded -> later stages NOT_REACHED | executable (`StageTelemetry.markUnreachedFromCheckoutFailure()`) | PASS |
| J | workspace unavailable -> no MissingContextVariableException | architectural: `vars/devSecOpsPipeline.groovy` holds one `node{}` for the whole run, including the callback/cleanup, so the pre-migration failure mode (post-block re-acquiring a *second* node) cannot occur. Not reproducible offline (requires a live controller to raise the original exception), but the code path that caused it no longer exists. | inspected (architectural fix) |
| K | cleanup failure does not mask original build failure | inspected: `reportToPlatform()`'s `try/catch` never writes to `currentBuild.result`; `SafeCleanup.safeDeleteDir()` swallows its own exception | inspected, not executable offline |
| L | canonical WF1 payload remains compatible | executable (`PlatformReporter.buildPayload()` key-set diffed against the pre-migration payload's 18 top-level keys) | PASS |
| M | minimal Jenkinsfile contains no credentials | `grep -i "credentials("` on `pfe-app-test/Jenkinsfile` | PASS |
| N | minimal Jenkinsfile contains no n8n/Sonar/ZAP/Trivy infra configuration | `grep` for platform hostnames/ports on `pfe-app-test/Jenkinsfile` | PASS (the one `http://` literal present, `zapTargetUrl`, is this project's own deployed service address, not a platform endpoint) |
| O | no governance logic in Jenkinsfile | `grep` for `problemClass`/`riskScore`/`securityScore`/`readiness`/`Judge`/routing tokens on `pfe-app-test/Jenkinsfile` | PASS |

Tests D/E/F/H/K require a real `mvn`/`docker`/`kubectl` process or a live
Sonar server and are therefore executed for real only during the one
controlled validation build (#134), not offline. Their logic was verified
by direct code inspection against the pre-migration Jenkinsfile they were
ported from (byte-identical shell blocks in Trivy/OWASP/ZAP; identical
regex in Sonar's ceTaskId extraction) rather than fabricated as "passing."
