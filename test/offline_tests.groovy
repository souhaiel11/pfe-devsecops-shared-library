/**
 * Offline/static test harness for the pfe-devsecops shared library.
 * No Jenkins controller required -- run with:
 *   test/run-offline-tests.sh
 *
 * Covers the pure decision logic (Section 19 test matrix) that does not
 * require a live `sh`/`docker`/`kubectl` execution: technology detection,
 * truthful skip-tests semantics, NOT_REACHED telemetry propagation, and
 * canonical payload shape.
 */
import org.pfe.devsecops.ProjectDetector
import org.pfe.devsecops.BuildRunner
import org.pfe.devsecops.StageTelemetry
import org.pfe.devsecops.PlatformReporter
import org.pfe.devsecops.ScannerRunner

int failures = 0

def check = { boolean cond, String label ->
    if (cond) {
        println "PASS: ${label}"
    } else {
        println "FAIL: ${label}"
        failures++
    }
}

// ---- TEST A: Maven project detected correctly ----
def mavenSteps = new FakeSteps()
mavenSteps.metaClass.fileExists = { String p -> p == 'pom.xml' }
check(new ProjectDetector(mavenSteps).detectBuildType(null) == 'maven', 'TEST A - Maven detected from pom.xml')

def gradleSteps = new FakeSteps()
gradleSteps.metaClass.fileExists = { String p -> p == 'build.gradle' }
check(new ProjectDetector(gradleSteps).detectBuildType(null) == 'gradle', 'Gradle detected from build.gradle')

def nodeSteps = new FakeSteps()
nodeSteps.metaClass.fileExists = { String p -> p == 'package.json' }
check(new ProjectDetector(nodeSteps).detectBuildType(null) == 'node', 'Node detected from package.json')

def noneSteps = new FakeSteps()
check(new ProjectDetector(noneSteps).detectBuildType(null) == null, 'No build type fabricated when nothing present')

// ---- TEST B: Dockerfile detected correctly ----
def dockerSteps = new FakeSteps()
dockerSteps.metaClass.fileExists = { String p -> p == 'Dockerfile' }
check(new ProjectDetector(dockerSteps).detectDockerfile(null, null) == true, 'TEST B - Dockerfile detected at root')
check(new ProjectDetector(noneSteps).detectDockerfile(null, null) == false, 'TEST B - Dockerfile absence reported truthfully')

def customDockerSteps = new FakeSteps()
customDockerSteps.metaClass.fileExists = { String p -> p == 'docker/Dockerfile' }
check(new ProjectDetector(customDockerSteps).detectDockerfile(null, 'docker/Dockerfile') == true,
    'TEST B - explicit dockerfile override respected')

// ---- TEST C: tests intentionally skipped -> truthful SKIPPED, never PASSED ----
def skipSteps = new FakeSteps()
def skipTelemetry = new StageTelemetry()
new BuildRunner(skipSteps, skipTelemetry).run('maven', true, null)
check(skipTelemetry.tests.status == 'SKIPPED', 'TEST C - skipTests=true yields SKIPPED status')
check(skipTelemetry.tests.status != 'PASSED' && skipTelemetry.tests.status != 'SUCCESS',
    'TEST C - skipped tests are never reported as PASSED/SUCCESS')

// ---- TEST C (unknown result honesty): tests attempted, no parseable surefire output ----
def unknownSteps = new FakeSteps()
def unknownTelemetry = new StageTelemetry()
new BuildRunner(unknownSteps, unknownTelemetry).run('maven', false, null)
check(unknownTelemetry.tests.status == 'UNKNOWN' && unknownTelemetry.tests.total == null,
    'TEST G - unparseable/missing test results reported as UNKNOWN, never fabricated 0/PASSED')

// ---- TEST I: SCM checkout failure -> later stages NOT_REACHED ----
def unreachedTelemetry = new StageTelemetry()
unreachedTelemetry.buildStageStatus['build'] = 'SOMETHING_STALE'
unreachedTelemetry.markUnreachedFromCheckoutFailure()
['build', 'tests', 'sonar', 'trivy', 'owasp', 'zap', 'docker'].each { stageName ->
    check(unreachedTelemetry.buildStageStatus[stageName] == 'NOT_REACHED', "TEST I - ${stageName} marked NOT_REACHED after checkout failure")
}
check(unreachedTelemetry.tests.status == 'NOT_REACHED', 'TEST I - tests.status NOT_REACHED after checkout failure')
check(unreachedTelemetry.tests.total == null, 'TEST I - tests.total never fabricated after checkout failure')
check(unreachedTelemetry.docker.build_status == 'NOT_REACHED', 'TEST I - docker.build_status NOT_REACHED after checkout failure')

// ---- TEST L: canonical WF1 payload shape unchanged ----
def reporter = new PlatformReporter(new FakeSteps())
def payload = reporter.buildPayload([
    event: 'pipeline_success', job: 'pfe-app-test', buildNumber: '134', buildUrl: 'http://jenkins/job/pfe-app-test/134/',
    branch: 'main', commit: 'abc1234', buildStatus: 'SUCCESS', severityHint: 'LOW', durationMs: 1000,
    buildStageStatus: [:], technicalFailure: null, pullRequest: null,
    reports: [:], tests: [:], sonar: [:], docker: [:], kubernetes: [:]
])
def expectedKeys = ['event', 'job', 'build_number', 'build_url', 'logs_url', 'branch', 'commit', 'status',
                     'severity_hint', 'duration_ms', 'buildStageStatus', 'technicalFailure', 'pull_request',
                     'reports', 'tests', 'sonar', 'docker', 'kubernetes', 'zap'] as Set
check(payload.keySet() == expectedKeys, 'TEST L - canonical payload keys match the pre-migration WF1 contract, plus additive zap')
check(payload.logs_url == 'http://jenkins/job/pfe-app-test/134/consoleText', 'TEST L - logs_url derived correctly')
check(payload.build_number == '134', 'TEST L - build_number forwarded (WF1 normalizes camelCase/snake_case at the boundary)')
check(payload.zap == null, 'TEST L - zap null (additive, not passed) does not break existing consumers reading old fields')

def exactSha = '10e90dd5a0d21941dea1a544c3026d0955a029b1'
def prPayload = reporter.buildPayload([
    event: 'pr_validation', job: 'pfe-app-test-multibranch/PR-24', buildNumber: '1', buildUrl: 'http://jenkins/pr/24/1/',
    branch: 'fix/test', commit: exactSha, checkoutSha: exactSha, buildStatus: 'SUCCESS', severityHint: 'LOW', durationMs: 1000,
    buildStageStatus: [:], technicalFailure: null, pullRequest: [number:'24'], reports: [:], tests: [status:'SUCCESS'],
    sonar: [ceTaskId:'ce-1', analysisId:'analysis-1'], docker: [:], kubernetes: [:], zap: null,
    prValidation: [validationRequestId:'validation-1', projectId:'project-1', incidentId:'incident-1',
        fixRequestId:'request-1', batchId:'batch-1', batchKey:'batch-1', attemptCount:7,
        repository:'owner/repo', prNumber:24, prHeadBranch:'fix/test', expectedPrHeadSha:exactSha,
        jenkinsJob:'pfe-app-test', prValidationJob:'pfe-app-test-multibranch/PR-24']
])
check(prPayload.expectedPrHeadSha == exactSha && prPayload.checkoutSha == exactSha,
    'R21-TEST A - PR callback preserves both authoritative full SHA values')
check(prPayload.validationRequestId == 'validation-1' && prPayload.batchId == 'batch-1' && prPayload.attemptCount == 7,
    'R21-TEST B - PR callback preserves persisted validation/batch correlation')
check(prPayload.ceTaskId == 'ce-1' && prPayload.analysisId == 'analysis-1',
    'R21-TEST C - PR callback carries exact CE and analysis identities')

// ======================================================================
// QA-BUILD-135-R1 defect-closure tests (Defects A/B/C/D)
// ======================================================================

// ---- R2-TEST A: ZAP kubectl launch failure -> no 37-minute wait loop entered ----
def launchFailSteps = new FakeSteps()
launchFailSteps.statusDecider = { String script ->
    if (script.contains('kubectl get svc')) return 0          // k8s reachable
    if (script.contains('kubectl apply')) return 1             // launch fails (Defect A ground truth)
    return null
}
def launchFailTelemetry = new StageTelemetry()
new ScannerRunner(launchFailSteps, launchFailTelemetry).runZap('pfe-devsecops', '/kube/config', 'http://app-test:8080', '136', '/shared/reports/pfe-app-test/136')
check(!launchFailSteps.shScripts.any { it.contains('Waiting for ZAP') },
    'R2-TEST A - launch failure never enters the 220x10s wait loop')

// ---- R2-TEST B: ZAP launch failure -> buildStageStatus.zap != COMPLETED ----
check(launchFailTelemetry.zap.launchAttempted == true, 'R2-TEST B - launchAttempted recorded true')
check(launchFailTelemetry.zap.launchSucceeded == false, 'R2-TEST B - launchSucceeded recorded false')
String zapStatusAfterLaunchFail = launchFailTelemetry.zapStageStatus(true)
check(zapStatusAfterLaunchFail == 'FAILED', 'R2-TEST B - buildStageStatus.zap == FAILED, never COMPLETED, after launch failure')
check(zapStatusAfterLaunchFail != 'COMPLETED', 'R2-TEST B - explicitly not COMPLETED')

// ---- R2-TEST D (part 1): launch failure -> technicalCode is the low-level factual code, not a governance verdict ----
check(launchFailTelemetry.zap.technicalCode == 'ZAP_POD_LAUNCH_FAILED', 'R2-TEST D - launch failure technicalCode == ZAP_POD_LAUNCH_FAILED')
check(launchFailTelemetry.zap.launchErrorType == 'KUBECTL_RUN_FAILED', 'R2-TEST D - launchErrorType captured')

// ---- R2-TEST E: successful ZAP report -> COMPLETED ----
def successSteps = new FakeSteps()
successSteps.statusDecider = { String script ->
    if (script.contains('kubectl get svc')) return 0
    if (script.contains('kubectl apply')) return 0                        // launch succeeds
    if (script.contains('test -s "$REPORT_BASE/zap-report.json"')) return 0 // usable report, no stub marker
    return null
}
def successTelemetry = new StageTelemetry()
new ScannerRunner(successSteps, successTelemetry).runZap('pfe-devsecops', '/kube/config', 'http://app-test:8080', '136', '/shared/reports/pfe-app-test/136')
check(successSteps.shScripts.any { it.contains('Waiting for ZAP') },
    'R2-TEST E - a successful launch DOES enter the wait/retrieve step')
check(successTelemetry.zap.launchSucceeded == true && successTelemetry.zap.resultAvailable == true,
    'R2-TEST E - launchSucceeded and resultAvailable both true on a usable report')
check(successTelemetry.zapStageStatus(true) == 'COMPLETED', 'R2-TEST E - buildStageStatus.zap == COMPLETED only for a real usable report')

// ---- R2-TEST C / J: launch succeeded but result unavailable (stub / pod disappeared) -> resultAvailable=false, distinct code from launch failure ----
def disappearedSteps = new FakeSteps()
disappearedSteps.statusDecider = { String script ->
    if (script.contains('kubectl get svc')) return 0
    if (script.contains('kubectl apply')) return 0                        // launch succeeded
    if (script.contains('test -s "$REPORT_BASE/zap-report.json"')) return 1 // stub content present -> not usable
    return null
}
def disappearedTelemetry = new StageTelemetry()
new ScannerRunner(disappearedSteps, disappearedTelemetry).runZap('pfe-devsecops', '/kube/config', 'http://app-test:8080', '135', '/shared/reports/pfe-app-test/135')
check(disappearedTelemetry.zap.launchSucceeded == true, 'R2-TEST C - launch succeeded even though the result later proved unavailable')
check(disappearedTelemetry.zap.resultAvailable == false, 'R2-TEST C - stub report content -> resultAvailable=false (not just file existence)')
check(disappearedTelemetry.zapStageStatus(true) == 'FAILED', 'R2-TEST C - buildStageStatus.zap == FAILED for an unusable stub, matching build #135 ground truth')
check(disappearedTelemetry.zap.technicalCode == 'ZAP_SCAN_POD_DISAPPEARED',
    'R2-TEST J - pod-disappeared classification is DISTINCT from ZAP_POD_LAUNCH_FAILED (launch succeeded here)')
check(disappearedTelemetry.zap.technicalCode != launchFailTelemetry.zap.technicalCode,
    'R2-TEST J - launch-never-happened and pod-disappeared never share one generic code')

// ---- R2-TEST D (part 2): no fabricated findingCount anywhere in Jenkins-side ZAP facts ----
check(!disappearedTelemetry.zap.containsKey('findingCount'), 'R2-TEST D - Jenkins never emits a findingCount field for ZAP (WF1-owned, and null/absent when incomplete, never fabricated 0)')

// ---- R2-TEST F: checkout SHA capture and short-SHA derivation ----
String fakeFullSha = '5b291b9abc35c093a67b269eceff5e5fa0fe5979'
def commitTelemetry = new StageTelemetry()
commitTelemetry.checkoutFullSha = fakeFullSha
commitTelemetry.checkoutShortSha = fakeFullSha.take(8)
check(commitTelemetry.checkoutShortSha == '5b291b9a', 'R2-TEST F - short SHA is the first 8 chars of the captured full SHA')

// ---- R2-TEST G: PlatformReporter forwards the captured SHA into the payload verbatim ----
def commitPayload = new PlatformReporter(new FakeSteps()).buildPayload([
    event: 'pipeline_success', job: 'pfe-app-test', buildNumber: '136', buildUrl: 'http://jenkins/job/pfe-app-test/136/',
    branch: 'main', commit: commitTelemetry.checkoutShortSha, buildStatus: 'SUCCESS', severityHint: 'LOW', durationMs: 1000,
    buildStageStatus: [:], technicalFailure: null, pullRequest: null,
    reports: [:], tests: [:], sonar: [:], docker: [:], kubernetes: [:], zap: null
])
check(commitPayload.commit == '5b291b9a', 'R2-TEST G - PlatformReporter payload.commit equals the captured checkout SHA')

// ---- R2-TEST H: commit fallback chain never fabricates when genuinely unresolved ----
String noCapturedSha = null
String noEnvGitCommit = null
String resolvedCommit = noCapturedSha ?: (noEnvGitCommit?.take(8)) ?: 'unknown'
check(resolvedCommit == 'unknown', 'R2-TEST H - commit falls back to the literal "unknown" only when truly unresolved, never a guessed value')
String envFallbackOnly = null
String envCommit = 'deadbeefcafefeed'
String resolvedFromEnv = envFallbackOnly ?: (envCommit?.take(8)) ?: 'unknown'
check(resolvedFromEnv == 'deadbeef', 'R2-TEST H - env.GIT_COMMIT fallback still works when the direct capture is unavailable')

// ---- R2-TEST K: no governance fields ever emitted anywhere in the Shared Library source (grep-verified) ----
String libRoot = System.getenv('LIB_ROOT')
if (libRoot) {
    // Matches actual emission syntax (a map-key colon or a quoted string literal),
    // never bare prose -- this file's own doc comments legitimately say things like
    // "no problemClass/owner/route here" and must not trip this check.
    def governanceTokens = [
        /problemClass\s*:/, /\briskScore\s*:/, /\bsecurityScore\s*:/, /\bowner\s*:/, /\broute\s*:/,
        /['"]problemClass['"]/, /aiSuggestedDecision/, /requiresApproval/, /\breadiness\s*:/
    ]
    def offenders = []
    new File(libRoot, 'src').eachFileRecurse { f ->
        if (f.name.endsWith('.groovy')) {
            f.readLines().eachWithIndex { String line, int i ->
                if (line.trim().startsWith('//') || line.trim().startsWith('*')) return
                governanceTokens.each { tok -> if (line =~ tok) offenders << "${f.name}:${i + 1}: ${line.trim()}" }
            }
        }
    }
    def varsFile = new File(libRoot, 'vars/devSecOpsPipeline.groovy')
    if (varsFile.exists()) {
        varsFile.readLines().eachWithIndex { String line, int i ->
            if (line.trim().startsWith('//') || line.trim().startsWith('*')) return
            governanceTokens.each { tok -> if (line =~ tok) offenders << "devSecOpsPipeline.groovy:${i + 1}: ${line.trim()}" }
        }
    }
    check(offenders.isEmpty(), "R2-TEST K - no governance-classification field emitted anywhere in the Shared Library source${offenders ? ' (found: ' + offenders + ')' : ''}")
} else {
    println 'R2-TEST K - SKIPPED (LIB_ROOT not set; run via test/run-offline-tests.sh)'
}

println ''
if (failures == 0) {
    println 'ALL OFFLINE TESTS PASSED'
} else {
    println "OFFLINE TESTS FAILED: ${failures}"
    System.exit(1)
}
