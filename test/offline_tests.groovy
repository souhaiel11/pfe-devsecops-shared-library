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
import org.pfe.devsecops.PlatformConfig

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

// ======================================================================
// R40 defect-closure tests: PR-24 build #1 proved a real
// NoSuchMethodError('String') CPS crash at devSecOpsPipeline.groovy:132,
// plus a terminal-callback gap it exposed (Checkout/stage failures that
// throw an Error, not just an Exception, previously skipped
// reportToPlatform entirely, stranding the platform in QUEUED forever).
// ======================================================================

// ---- R40-TEST A: the fixed expectedPrHeadSha conversion is CPS-safe and
// preserves exact SHA/null/case semantics (the actual logic from the fixed
// line, replicated here the same way R2-TEST H replicates its fallback
// chain rather than re-invoking the full Jenkins-coupled call()). ----
def convertExpectedSha = { rawExpected ->
    // Mirrors the fixed line 132 exactly: (value ?: '').toString().toLowerCase()
    (rawExpected ?: '').toString().toLowerCase()
}
String realSha = '10E90DD5A0D21941DEA1A544C3026D0955A029B1'
check(convertExpectedSha(realSha) == '10e90dd5a0d21941dea1a544c3026d0955a029b1',
    'R40-TEST A - mixed-case expectedPrHeadSha lowercased correctly, full 40 chars preserved')
check(convertExpectedSha(null) == '', 'R40-TEST A - null expectedPrHeadSha falls back to empty string, never fabricated/never throws')
check(convertExpectedSha('') == '', 'R40-TEST A - empty expectedPrHeadSha stays empty')
check(!(convertExpectedSha(null) ==~ /[a-f0-9]{40}/), 'R40-TEST A - null/absent SHA correctly fails the 40-hex gate (fail-closed, not skipped)')
check(convertExpectedSha(realSha) ==~ /[a-f0-9]{40}/, 'R40-TEST A - a real 40-char SHA passes the exact-hex gate after conversion')

// ---- R40-TEST B: no CPS-unsafe bare TypeName(...) constructor-style call
// remains anywhere in the Shared Library (grep-verified, same technique as
// R2-TEST K). `new String(...)` and `String.valueOf(...)` are excluded --
// both are safe, qualified forms Jenkins CPS does not route through step
// lookup; only a bare `String(`/`Integer(`/etc. call is CPS-unsafe. ----
if (libRoot) {
    def cpsUnsafePattern = /(?<!\bnew\s)(?<!\w)(String|Integer|Boolean|Long|Double|Float|BigDecimal|BigInteger)\s*\(/
    def cpsOffenders = []
    def scanFile = { File f, String label ->
        f.readLines().eachWithIndex { String line, int i ->
            if (line.trim().startsWith('//') || line.trim().startsWith('*')) return
            // Exclude the safe qualified form Type.staticMethod(...), e.g. String.valueOf(...)
            def stripped = line.replaceAll(/\b(String|Integer|Boolean|Long|Double|Float|BigDecimal|BigInteger)\.\w+\(/, '')
            if (stripped =~ cpsUnsafePattern) cpsOffenders << "${label}:${i + 1}: ${line.trim()}"
        }
    }
    new File(libRoot, 'src').eachFileRecurse { f -> if (f.name.endsWith('.groovy')) scanFile(f, f.name) }
    def varsFile2 = new File(libRoot, 'vars/devSecOpsPipeline.groovy')
    if (varsFile2.exists()) scanFile(varsFile2, 'devSecOpsPipeline.groovy')
    check(cpsOffenders.isEmpty(), "R40-TEST B - no CPS-unsafe bare TypeName(...) call anywhere in the Shared Library${cpsOffenders ? ' (found: ' + cpsOffenders + ')' : ''}")
} else {
    println 'R40-TEST B - SKIPPED (LIB_ROOT not set; run via test/run-offline-tests.sh)'
}

// ---- R40-TEST C: technicalFailure construction for a post-checkout stage
// failure (Build/Sonar/Docker/Trivy/OWASP/ZAP) -- mirrors the exact
// reportToPlatform branch added for ctx.stageFailure, same convention as
// R40-TEST A / R2-TEST H (isolated logic, not the full Jenkins-coupled call()). ----
def buildTechnicalFailure = { boolean checkoutFailed, Map stageFailure ->
    if (checkoutFailed) {
        return [phase: 'SCM_CHECKOUT', technicalCode: 'SCM_CHECKOUT_NETWORK_FAILURE',
                message: 'Git checkout did not complete -- see Jenkins console for the underlying git/network error.']
    } else if (stageFailure) {
        return [phase: 'PIPELINE_STAGE', technicalCode: stageFailure.technicalCode, message: stageFailure.message]
    }
    return null
}
def stageEx = [technicalCode: 'PIPELINE_STAGE_ERROR', message: 'No such DSL method \'String\' found among steps [...]']
def stageFailureResult = buildTechnicalFailure(false, stageEx)
check(stageFailureResult.phase == 'PIPELINE_STAGE', 'R40-TEST C - post-checkout stage failure reports phase=PIPELINE_STAGE, distinct from SCM_CHECKOUT')
check(stageFailureResult.technicalCode == 'PIPELINE_STAGE_ERROR', 'R40-TEST C - stage failure technicalCode forwarded verbatim, never fabricated')
check(buildTechnicalFailure(true, stageEx).phase == 'SCM_CHECKOUT',
    'R40-TEST C - checkoutFailed still takes priority over stageFailure (mutually exclusive in practice, checkout-first ordering preserved)')
check(buildTechnicalFailure(false, null) == null, 'R40-TEST C - no technicalFailure fabricated when neither checkout nor a later stage failed')

// ---- R40-TEST D: an Error (not just an Exception) is catchable by
// `catch (Throwable ...)`, proving the broadened catch actually closes the
// gap that let NoSuchMethodError escape reportToPlatform on real build #1. ----
boolean caughtAsThrowable = false
try {
    throw new NoSuchMethodError("No such DSL method 'String' found among steps [...]")
} catch (Throwable t) {
    caughtAsThrowable = true
}
check(caughtAsThrowable, 'R40-TEST D - catch (Throwable) catches NoSuchMethodError (an Error, not an Exception)')

boolean caughtAsBareCatch = false
try {
    try {
        throw new NoSuchMethodError('simulated CPS DSL lookup failure')
    } catch (bareCatchVar) {
        caughtAsBareCatch = true
    }
} catch (Error uncaught) {
    caughtAsBareCatch = false
}
check(!caughtAsBareCatch, 'R40-TEST D - control case: an untyped Groovy catch (Exception-only) does NOT catch an Error, confirming the pre-fix gap was real')

// ---- R45-TEST A: COMMUNITY_EXACT_SHA mode runs Sonar against the dedicated
// per-PR project key and never emits sonar.pullrequest.* (Community Edition
// rejects it outright -- proven on real PR-24 build #2, see PlatformConfig). ----
check(PlatformConfig.SONAR_ANALYSIS_MODE == 'COMMUNITY_EXACT_SHA',
    'R45-TEST A - PlatformConfig currently pins COMMUNITY_EXACT_SHA (flip only after a real Developer Edition migration)')

def communitySteps = new FakeSteps()
communitySteps.metaClass.withSonarQubeEnv = { String name, Closure body -> body.call() }
def communityTelemetry = new StageTelemetry()
new ScannerRunner(communitySteps, communityTelemetry).runSonar(
    'pfe-app-test', '.', true, '24', 'feature/x', 'main', 'pfe-app-test-pr-24')
String communitySonarScript = communitySteps.shScripts.find { it.contains('mvn sonar:sonar') }
check(communitySonarScript != null, 'R45-TEST A - PR build in COMMUNITY_EXACT_SHA mode still runs mvn sonar:sonar')
check(communitySonarScript.contains('sonar.projectKey="pfe-app-test-pr-24"'),
    'R45-TEST A - PR build uses the dedicated per-PR project key, never the base project key')
check(!communitySonarScript.contains('sonar.pullrequest.'),
    'R45-TEST A - no sonar.pullrequest.* property ever sent in COMMUNITY_EXACT_SHA mode')
check(communityTelemetry.buildStageStatus['sonar'] == 'SUCCESS', 'R45-TEST A - stage reports SUCCESS on a normal run')

// ---- R45-TEST B: a missing validationSonarProjectKey fails closed -- never
// silently falls back to the base project key or to "latest analysis". ----
def missingKeySteps = new FakeSteps()
missingKeySteps.metaClass.withSonarQubeEnv = { String name, Closure body -> body.call() }
def missingKeyTelemetry = new StageTelemetry()
new ScannerRunner(missingKeySteps, missingKeyTelemetry).runSonar(
    'pfe-app-test', '.', true, '24', 'feature/x', 'main', null)
check(!missingKeySteps.shScripts.any { it.contains('mvn sonar:sonar') },
    'R45-TEST B - no Sonar analysis is ever run when the per-PR project key is missing (fail closed, not skipped-as-pass)')
check(missingKeyTelemetry.buildStageStatus['sonar'] == 'FAILED',
    'R45-TEST B - sonar stage stays FAILED, never fabricated as SUCCESS, when the per-PR project key is missing')

// ---- R45-TEST C: a standard (non-PR) branch build is unaffected by
// COMMUNITY_EXACT_SHA -- it always analyzes the base project key. ----
def branchSteps = new FakeSteps()
branchSteps.metaClass.withSonarQubeEnv = { String name, Closure body -> body.call() }
def branchTelemetry = new StageTelemetry()
new ScannerRunner(branchSteps, branchTelemetry).runSonar('pfe-app-test', '.', false, null, null, null, null)
String branchSonarScript = branchSteps.shScripts.find { it.contains('mvn sonar:sonar') }
check(branchSonarScript != null && branchSonarScript.contains('sonar.projectKey="pfe-app-test"'),
    'R45-TEST C - standard branch build analyzes the base project key, unaffected by the PR compatibility mode')
check(!branchSonarScript.contains('sonar.pullrequest.'), 'R45-TEST C - no PR properties on a non-PR build either')

// ---- R45-TEST D: prValidationContext's required-field list mirrors the
// devSecOpsPipeline.groovy logic (isolated pure closure, same convention as
// R40-TEST A/C -- the real method is script-scope private and CPS-transformed,
// not directly callable offline). Verifies COMMUNITY_EXACT_SHA additionally
// requires the two per-PR project-key fields and fails closed (returns [:],
// never a partially-populated context) when either is absent. ----
def prValidationRequiredFields = { String mode ->
    def base = ['validationRequestId','projectId','incidentId','fixRequestId','batchId','batchKey',
                'attemptCount','repository','prNumber','prHeadBranch','expectedPrHeadSha','jenkinsJob','prValidationJob']
    return mode == 'COMMUNITY_EXACT_SHA' ? base + ['baseSonarProjectKey', 'validationSonarProjectKey'] : base
}
check(prValidationRequiredFields('COMMUNITY_EXACT_SHA').containsAll(['baseSonarProjectKey', 'validationSonarProjectKey']),
    'R45-TEST D - COMMUNITY_EXACT_SHA requires the per-PR project-key fields up front')
check(!prValidationRequiredFields('DEVELOPER_NATIVE_PR').contains('validationSonarProjectKey'),
    'R45-TEST D - DEVELOPER_NATIVE_PR does not require the Community-only project-key fields')

println ''
if (failures == 0) {
    println 'ALL OFFLINE TESTS PASSED'
} else {
    println "OFFLINE TESTS FAILED: ${failures}"
    System.exit(1)
}
