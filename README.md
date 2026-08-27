# pfe-devsecops (Jenkins Shared Library)

Centralized DevSecOps CI/CD platform logic for all PFE 2026 projects. A
project Jenkinsfile calls this library and declares nothing about scanners,
platform URLs, credentials, or governance.

## Developer usage

```groovy
@Library('pfe-devsecops') _

devSecOpsPipeline {
    applicationName = 'my-app'
}
```

That's it. The library auto-detects Maven/Gradle/Node, runs the build,
runs SonarQube + Trivy + OWASP Dependency-Check + (branch builds only) a
Kubernetes reachability check and an OWASP ZAP DAST scan, and reports
factual telemetry to the platform (WF1) over the existing `n8n` webhook.
No PR, deployment, or remediation is ever triggered from Jenkins -- see
[docs/governance-boundary.md](docs/governance-boundary.md).

### Optional per-project overrides

Only set these when the repository layout genuinely requires it:

| Field | Default | When to set it |
|---|---|---|
| `applicationName` | derived from `JOB_NAME` | job name isn't a clean app name |
| `workingDirectory` | repo root | app lives in a subdirectory (monorepo) |
| `dockerfile` | `Dockerfile` at `workingDirectory` | Dockerfile has a non-standard path |
| `skipTests` | `false` | project genuinely doesn't run tests in CI yet -- must be declared truthfully, never silently assumed |
| `zapTargetUrl` | `http://<applicationName>:8080` | the deployed k8s Service name/port doesn't match the app name convention |

Nothing else is configurable from a project Jenkinsfile. Platform URLs,
credential IDs, namespaces, and scanner policy live in
[`src/org/pfe/devsecops/PlatformConfig.groovy`](src/org/pfe/devsecops/PlatformConfig.groovy)
and are never repeated in project code.

## Structure

```
vars/devSecOpsPipeline.groovy   entry point + orchestration (stages, post/report)
src/org/pfe/devsecops/
  PlatformConfig.groovy         PLATFORM_INFRASTRUCTURE defaults (URLs, cred IDs, namespaces)
  ProjectDetector.groovy        convention-over-configuration tech detection
  StageTelemetry.groovy         CPS-safe mutable execution state (facts only)
  BuildRunner.groovy            Maven build/test execution
  DockerRunner.groovy           Docker image build
  ScannerRunner.groovy          Sonar / Trivy / OWASP / K8s check / ZAP
  PlatformReporter.groovy       canonical WF1 callback (schema, retries, timeout)
  SafeCleanup.groovy            workspace-safe cleanup, never masks a real failure
docs/                           architecture, onboarding, governance boundary
jenkins-config/                 how to register this library on Jenkins (not yet applied)
test/                           offline/static test harness (no live Jenkins needed): run test/run-offline-tests.sh
```

## Versioning

Single `main` branch, referenced by projects implicitly via the Jenkins
global library registration (no per-project `@Library('pfe-devsecops@<ref>')`
pinning needed for now). This is the simplest professional option for a PFE
scale deployment; if a breaking change is ever needed, tag a release and
have the one active project (`pfe-app-test`) pin to it explicitly during the
transition.

## Governance boundary

Jenkins (this library included) produces **facts only**: stage status,
test counts, scanner completion, a raw technical failure code. It never
computes `problemClass`, `owner`, routing, risk, readiness, or a Judge
verdict, and it never triggers WF2/WF4/WF5, PR creation, or deployment.
See [docs/governance-boundary.md](docs/governance-boundary.md).
