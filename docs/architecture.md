# Architecture: PFE Jenkins Platform Standardization (R2)

```
PROJECT REPOSITORY (pfe-app-test, and every future project)
        | minimal Jenkinsfile (@Library + devSecOpsPipeline{...})
        v
JENKINS SHARED LIBRARY (this repo, pfe-devsecops)
        | build / tests / scanners / telemetry / callback construction
        v
JENKINS PLATFORM CONFIGURATION (global library registration, credentials, tools)
        | credentials / Maven+Sonar tool config / library source
        v
WF1 (n8n)
        | classification / root cause / owner / route / risk / Judge
        v
BACKEND (NestJS)
        | persistence / governance / incident lifecycle
        v
ANGULAR
        | truthful presentation / explicit human approval before any PR/deploy
```

## Migration matrix (audit of the pre-migration pfe-app-test Jenkinsfile)

The full pre-migration Jenkinsfile is preserved verbatim at
[`pfe-app-test.Jenkinsfile.pre-migration-reference.groovy`](pfe-app-test.Jenkinsfile.pre-migration-reference.groovy)
(also fully recoverable from `pfe-app-test` git history, commit `21eea2a`).

| Current location (line refs are pre-migration) | Category | Target location | Reason |
|---|---|---|---|
| `APP_NAME`/`IMAGE_NAME` (L43-44) | PROJECT_SPECIFIC | `devSecOpsPipeline { applicationName }` | genuinely per-project |
| `ZAP_TARGET_URL` (L54) | PROJECT_SPECIFIC | `devSecOpsPipeline { zapTargetUrl }` | this project's deployed Service name differs from the app-name convention (`app-test` vs `pfe-app-test`) |
| skip-tests decision (L107, L117-122) | PROJECT_SPECIFIC | `devSecOpsPipeline { skipTests }` | deliberate current pipeline decision, must stay explicit/truthful, not silently re-derived |
| `mvn clean package` invocation | PIPELINE_GENERIC | `BuildRunner.groovy` | identical for every Maven project |
| SonarQube stage (L147-190) | PIPELINE_GENERIC | `ScannerRunner.runSonar()` | identical orchestration for every project |
| Docker build (L193-216) | PIPELINE_GENERIC | `DockerRunner.groovy` | identical for every Dockerized project |
| Trivy stage (L218-275) | PIPELINE_GENERIC | `ScannerRunner.runTrivy()` | identical for every project |
| OWASP Dependency-Check stage (L277-338) | PIPELINE_GENERIC | `ScannerRunner.runOwasp()` | identical for every Maven project |
| K8s target check (L340-362) | PIPELINE_GENERIC | `ScannerRunner.checkKubernetesTarget()` | identical mechanism, target service name derived from `zapTargetUrl` |
| ZAP DAST (L364-500) | PIPELINE_GENERIC | `ScannerRunner.runZap()` | identical orchestration; only the target URL varies |
| `post{always{}}` payload construction (L503-700) | PIPELINE_GENERIC | `PlatformReporter.buildPayload()` / `vars/devSecOpsPipeline.groovy#reportToPlatform` | identical schema for every project |
| workspace-safe cleanup / node re-acquisition workaround (L507-524, L685-699) | PIPELINE_GENERIC | `SafeCleanup.groovy` + structural fix (see below) | identical safety requirement for every project |
| `BACKEND_URL`, `N8N_WEBHOOK_URL`, `SONAR_HOST_URL` (L46-48) | PLATFORM_INFRASTRUCTURE | `PlatformConfig.groovy` | developer must never know these |
| `K8S_NAMESPACE`, `KUBECONFIG` (L50-51) | PLATFORM_INFRASTRUCTURE | `PlatformConfig.groovy` | Jenkins node infra detail |
| `ZAP_IMAGE` (L53) | PLATFORM_INFRASTRUCTURE | `PlatformConfig.groovy` | tool selection, not project concern |
| `N8N_API_KEY`, `NVD_API_KEY`, `SONAR_TOKEN` (L39-41) | SECRET_CREDENTIAL | already Jenkins Credentials (unchanged) — only the *reference* moved into the Shared Library (`PlatformConfig.CRED_*` IDs), never into the project Jenkinsfile | already correctly stored; reference relocated out of project code |
| `problemClass: 'TECHNICAL_BLOCKER'` inside `technicalFailure` (L581, pre-migration) | GOVERNANCE_ANALYSIS (pre-existing boundary violation) | removed from the Jenkins-emitted payload | **audit finding**: the pre-migration pipeline already classified its own failure as `TECHNICAL_BLOCKER` in Jenkins. Verified against WF1's `Merge All Fetched Data` node: it recomputes `problemClass`/`owner`/`route` from `technicalCode` via its own `TECHNICAL_CODE_META` table and never reads Jenkins' `problemClass` field, so removing it is a pure governance-boundary fix with zero WF1 compatibility impact. |
| `JENKINS_HARD_GATE` / `CVSS_FAIL_THRESHOLD` parameters (L26-36) | PIPELINE_GENERIC (operational, not governance) | `vars/devSecOpsPipeline.groovy` `properties([parameters([...])])` | mandatory platform-defined parameters, not something a project declares |

## Structural fix for the SCM-checkout failure class (Section 13/14)

Pre-migration: the declarative `pipeline{}` releases the node/workspace
context before `post{always{}}` runs, so after a checkout failure any
`sh`/`deleteDir`/`writeFile` in `post` throws `MissingContextVariableException`
unless a *new* node is explicitly re-acquired (which is what the
QA-BUILD-133 hardening commit did, at the cost of real complexity).

Post-migration: `vars/devSecOpsPipeline.groovy` enters exactly one `node {}`
block at the very start and never leaves it until the platform callback and
cleanup are done. `checkout scm` is wrapped in its own `try/catch`; on
failure, later stages are skipped (mirrored to `NOT_REACHED` telemetry) but
the *same* node/workspace context is still held for the callback and
`deleteDir()` at the end. This removes the original bug class architecturally
instead of working around it per project.

- Case A (Jenkins cannot fetch the Jenkinsfile at all): no library code can
  run, by definition. This remains a Jenkins job/SCM configuration concern
  (checkout retry/timeout on the job's own SCM step), documented here but not
  solvable in library code.
- Case B (checkout fails after the Jenkinsfile loaded): handled by
  `vars/devSecOpsPipeline.groovy` as described above. Verified by the
  executable offline test `TEST I` (`test/offline_tests.groovy`).

## Config precedence

1. Jenkins Credentials (`SONAR_TOKEN`, `N8N_API_KEY`, `NVD_API_KEY` — already correct pre-migration, untouched)
2. Jenkins global configuration / JCasC (not yet installed on this controller — see `jenkins-config/`)
3. Shared Library defaults (`PlatformConfig.groovy`) — where infra config lives today
4. Explicit project override in the Jenkinsfile closure — only for the 5 documented fields

## Test matrix results

See [`../test/offline_tests.groovy`](../test/offline_tests.groovy) and
[`test-matrix-results.md`](test-matrix-results.md) for the full A-O results.
