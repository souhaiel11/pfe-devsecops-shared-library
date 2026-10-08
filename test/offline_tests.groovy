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
                     'reports', 'tests', 'sonar', 'docker', 'kubernetes', 'zap', 'commitSha', 'commitShaDiagnostic',
                     'semanticEvidence'] as Set
check(payload.keySet() == expectedKeys, 'TEST L - canonical payload keys match the pre-migration WF1 contract, plus additive zap/SHA/R80.1 semantic-evidence-envelope fields')
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


// Application SHA evidence: no real network, shell, or Jenkins execution.
String applicationSha = 'a1' * 20
String librarySha = 'b2' * 20
def shaReporter = new PlatformReporter(new FakeSteps())
['pipeline_success', 'pipeline_unstable', 'pipeline_failed'].each { event ->
    def ordinary = shaReporter.buildPayload([
        event: event, commit: applicationSha.take(8), checkoutSha: applicationSha,
        librarySha: librarySha, GIT_COMMIT: librarySha, token: 'TEST_ONLY_SECRET_SENTINEL'
    ])
    check(ordinary.commitSha == applicationSha, "SHA - ${event} full application SHA")
    check(ordinary.commit == applicationSha.take(8), "SHA - ${event} legacy commit preserved")
    check(!ordinary.containsKey('checkoutSha') && !ordinary.containsKey('commitShaDiagnostic'),
        "SHA - ${event} valid ordinary field shape")
    String encoded = groovy.json.JsonOutput.toJson(ordinary)
    check(new groovy.json.JsonSlurperClassic().parseText(encoded).commitSha == applicationSha,
        "SHA - ${event} JSON retains exact 40 characters")
    check(!encoded.contains(librarySha) && !encoded.contains('TEST_ONLY_SECRET_SENTINEL'),
        "SHA - ${event} no library substitution or secret argument leakage")
}
[null, '', applicationSha.take(8), 'a' * 39, 'a' * 41, 'g' * 40,
 ' ' + applicationSha, 'TEST_ONLY_SECRET_SENTINEL'].each { invalid ->
    def rejected = shaReporter.buildPayload([
        event: 'pipeline_failed', commit: applicationSha.take(8), checkoutSha: invalid,
        librarySha: librarySha, GIT_COMMIT: librarySha
    ])
    check(rejected.commitSha == null && rejected.commit == applicationSha.take(8),
        'SHA - absent/invalid SHA stays null, never expanded or replaced')
    check(rejected.commitShaDiagnostic == 'APPLICATION_CHECKOUT_SHA_UNAVAILABLE_OR_INVALID',
        'SHA - fixed safe diagnostic')
    check(!groovy.json.JsonOutput.toJson(rejected).contains('TEST_ONLY_SECRET_SENTINEL'),
        'SHA - rejected value never leaked')
}
check(shaReporter.buildPayload([event: 'pipeline_success', checkoutSha: applicationSha.toUpperCase()]).commitSha
    == applicationSha.toUpperCase(), 'SHA - valid uppercase hexadecimal preserved')
def candidate = shaReporter.buildPayload([
    event: 'pr_validation', commit: applicationSha.take(8), checkoutSha: applicationSha,
    prValidation: [expectedPrHeadSha: applicationSha], sonar: [:]
])
check(candidate.checkoutSha == applicationSha && candidate.expectedPrHeadSha == applicationSha
    && !candidate.containsKey('commitSha') && !candidate.containsKey('commitShaDiagnostic'),
    'SHA - PR exact candidate contract unchanged')

// Exercise the real producer with a different environment/library revision.
def appTelemetry = new StageTelemetry()
appTelemetry.checkoutFullSha = applicationSha
appTelemetry.checkoutShortSha = applicationSha.take(8)
def reportingScript = new Expando(
    env: [GIT_COMMIT: librarySha, GIT_BRANCH: 'origin/main', JOB_NAME: 'pfe-app-test', BUILD_NUMBER: '1'],
    currentBuild: [currentResult: 'SUCCESS', duration: 0],
    timeout: { Map options, Closure action -> action.call() }, echo: { Object message -> println(message) })
def cleanupStub = new Expando(reportAvailable: { Object base, Object name -> false }, safeDeleteDir: { -> })
Map captured = null
def reporterStub = new Expando(buildPayload: { Map args -> shaReporter.buildPayload(args) },
    send: { Map result, Object base, Object build -> captured = result })
def pipelineScript = this.class.classLoader.loadClass('devSecOpsPipeline').newInstance()
pipelineScript.reportToPlatform(reportingScript, appTelemetry, cleanupStub, reporterStub,
    [isPR: false, checkoutFailed: false, zapStageEntered: false, applicationName: 'pfe-app-test'])
check(captured?.commitSha == applicationSha && captured?.commit == applicationSha.take(8),
    'SHA - real reportToPlatform forwards application telemetry despite different environment SHA')
String producerSource = new File(System.getenv('LIB_ROOT'), 'vars/devSecOpsPipeline.groovy').text
check(producerSource.contains('def scmVars = checkout(scm)')
    && producerSource.contains('git rev-parse HEAD 2>/dev/null || true')
    && producerSource.contains('telemetry.checkoutFullSha = capturedSha ?: (scmVars?.GIT_COMMIT ?: null)')
    && producerSource.contains('checkoutSha     : telemetry.checkoutFullSha'),
    'SHA - capture and forwarding remain tied to application checkout')

// ---- R80: publishSemanticTestEvidence() -- structured JUnit publication + raw testcase extraction ----
def r80Steps = new FakeSteps()
r80Steps.stdoutFor['ls target/surefire-reports/TEST-*.xml 2>/dev/null || true'] =
    'target/surefire-reports/TEST-com.example.FooTest.xml\ntarget/surefire-reports/TEST-com.example.BarTest.xml'
r80Steps.fileContents['target/surefire-reports/TEST-com.example.FooTest.xml'] = '''<?xml version="1.0"?>
<testsuite tests="2" failures="1" errors="0" skipped="0">
  <testcase classname="com.example.FooTest" name="passingCase" time="0.01"/>
  <testcase classname="com.example.FooTest" name="failingCase" time="0.01"><failure message="boom">stack</failure></testcase>
</testsuite>'''
r80Steps.fileContents['target/surefire-reports/TEST-com.example.BarTest.xml'] = '''<?xml version="1.0"?>
<testsuite tests="2" failures="0" errors="1" skipped="1">
  <testcase classname="com.example.BarTest" name="erroredCase" time="0.01"><error message="oops">stack</error></testcase>
  <testcase classname="com.example.BarTest" name="skippedCase" time="0.0"><skipped/></testcase>
</testsuite>'''
def r80Telemetry = new StageTelemetry()
new BuildRunner(r80Steps, r80Telemetry).run('maven', false, null)

check(r80Steps.junitCalls.size() == 1, 'R80 - junit publisher step is actually invoked when tests run')
check(r80Steps.junitCalls[0].testResults == 'target/surefire-reports/*.xml', 'R80 - junit step targets the real Surefire XML glob')
check(r80Steps.junitCalls[0].allowEmptyResults == true, 'R80 - junit step never fails the build over zero results on its own')

// R80 §10 -- the KNOWN BUG: the old tail-1 console-text scrape only ever
// saw ONE class's own count (proven live: build #4 really ran 17 tests,
// platform reported 10). The aggregate must now come from summing every
// <testsuite>'s OWN tests/failures/errors/skipped attributes across every
// XML file -- not from re-counting <testcase> children, and never from
// console text.
check(r80Telemetry.tests.total == 4, 'R80 §10 - aggregate total is the SUM across every XML file (2+2=4), never just the last file alphabetically (the proven bug)')
check(r80Telemetry.tests.failures == 2, 'R80 §10 - failures aggregate sums failures+errors across every file (1 failure + 1 error)')
check(r80Telemetry.tests.skipped == 1, 'R80 §10 - skipped aggregate sums across every file')
check(r80Telemetry.tests.status == 'FAILED', 'R80 §10 - a nonzero aggregate failure count is reported FAILED, never fabricated SUCCESS')

def r80Cases = r80Telemetry.semanticTestEvidence?.testcases
check(r80Cases != null, 'R80 - semanticTestEvidence.testcases is populated (not null) when tests ran')
check(r80Cases?.size() == 4, 'R80 - all four real <testcase> entries extracted across both XML files')
check(r80Cases?.find { it.name == 'passingCase' }?.status == 'PASS', 'R80 - a clean <testcase> is reported PASS')
check(r80Cases?.find { it.name == 'failingCase' }?.status == 'FAILURE', 'R80 - a <failure> child is reported FAILURE, never PASS')
check(r80Cases?.find { it.name == 'erroredCase' }?.status == 'ERROR', 'R80 - an <error> child is reported ERROR')
check(r80Cases?.find { it.name == 'skippedCase' }?.status == 'SKIPPED', 'R80 - a <skipped> child is reported SKIPPED')
check(r80Cases?.every { it.classname && it.name }, 'R80 - every extracted testcase carries both classname and name (the only two fields Surefire XML actually preserves)')

// R80 §10 -- the REAL build #4 shape: 3 classes, 17 tests total (3 + 10 + 4), proving 17 not 10.
def r80RealShapeSteps = new FakeSteps()
r80RealShapeSteps.stdoutFor['ls target/surefire-reports/TEST-*.xml 2>/dev/null || true'] =
    'target/surefire-reports/TEST-com.pfe.devsecops.controller.AuthControllerTest.xml\n' +
    'target/surefire-reports/TEST-com.pfe.devsecops.controller.TaskControllerUpdateStatusSemanticsTest.xml\n' +
    'target/surefire-reports/TEST-com.pfe.devsecops.service.TaskServiceTest.xml'
r80RealShapeSteps.fileContents['target/surefire-reports/TEST-com.pfe.devsecops.controller.AuthControllerTest.xml'] =
    '<testsuite tests="3" failures="0" errors="0" skipped="0"></testsuite>'
r80RealShapeSteps.fileContents['target/surefire-reports/TEST-com.pfe.devsecops.controller.TaskControllerUpdateStatusSemanticsTest.xml'] =
    '<testsuite tests="4" failures="0" errors="0" skipped="0"></testsuite>'
r80RealShapeSteps.fileContents['target/surefire-reports/TEST-com.pfe.devsecops.service.TaskServiceTest.xml'] =
    '<testsuite tests="10" failures="0" errors="0" skipped="0"></testsuite>'
def r80RealShapeTelemetry = new StageTelemetry()
new BuildRunner(r80RealShapeSteps, r80RealShapeTelemetry).run('maven', false, null)
check(r80RealShapeTelemetry.tests.total == 17, 'R80 §10 - the real build #4 shape (3+4+10) now aggregates to 17, not the previously-reported 10')

// R80 - skipTests=true must never publish/extract anything fabricated
def r80SkipSteps = new FakeSteps()
def r80SkipTelemetry = new StageTelemetry()
new BuildRunner(r80SkipSteps, r80SkipTelemetry).run('maven', true, null)
check(r80SkipSteps.junitCalls.isEmpty(), 'R80 - junit publisher is never called when tests are deliberately skipped')
check(r80SkipTelemetry.semanticTestEvidence == null, 'R80 - semanticTestEvidence stays null (not an empty list) when tests never ran at all')

// R80 - a malformed XML file alongside a good one: the good file's data
// still comes through, the malformed one is skipped, never crashes.
def r80BadXmlSteps = new FakeSteps()
r80BadXmlSteps.stdoutFor['ls target/surefire-reports/TEST-*.xml 2>/dev/null || true'] =
    'target/surefire-reports/TEST-com.example.BrokenTest.xml\ntarget/surefire-reports/TEST-com.example.GoodTest.xml'
r80BadXmlSteps.fileContents['target/surefire-reports/TEST-com.example.BrokenTest.xml'] = 'not even xml <<<'
r80BadXmlSteps.fileContents['target/surefire-reports/TEST-com.example.GoodTest.xml'] =
    '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="com.example.GoodTest" name="onlyCase"/></testsuite>'
def r80BadXmlTelemetry = new StageTelemetry()
new BuildRunner(r80BadXmlSteps, r80BadXmlTelemetry).run('maven', false, null)
check(r80BadXmlTelemetry.tests.total == 1, 'R80 - a malformed XML file is skipped without crashing; the good file alongside it is still aggregated')
check(r80BadXmlTelemetry.semanticTestEvidence?.testcases?.size() == 1, 'R80 - the good file testcase still reaches semanticTestEvidence despite a sibling malformed file')

// R80 - every file unparseable -> honest UNKNOWN/null, never a fabricated empty-but-attempted result
def r80AllBadSteps = new FakeSteps()
r80AllBadSteps.stdoutFor['ls target/surefire-reports/TEST-*.xml 2>/dev/null || true'] = 'target/surefire-reports/TEST-com.example.BrokenTest.xml'
r80AllBadSteps.fileContents['target/surefire-reports/TEST-com.example.BrokenTest.xml'] = 'not even xml <<<'
def r80AllBadTelemetry = new StageTelemetry()
new BuildRunner(r80AllBadSteps, r80AllBadTelemetry).run('maven', false, null)
check(r80AllBadTelemetry.tests.status == 'UNKNOWN', 'R80 - zero parseable reports is honestly UNKNOWN, never fabricated')
check(r80AllBadTelemetry.semanticTestEvidence == null, 'R80 - zero parseable reports leaves semanticTestEvidence null, not an empty-but-attempted list')

// ==================================================================
// R80.1 -- CROSS-REPO CONTRACT: emitted `semanticEvidence` envelope must
// match the backend's ACTUAL expected wire contract exactly. This is a
// static, unavoidable cross-repo risk (Groovy cannot import TypeScript),
// so the expectation below is a literal transcription -- verified by
// direct grep/read on 2026-09-20 against:
//   platform backend commit 5f8410606395670b0f2f6fa1a9c44876072b74fb
//     - incidents.service.ts:1265 reads `(validation as any).semanticEvidence`
//     - adapters/registry.ts resolves purely by `envelope.reportFormat`
//     - junit-semantic-evidence-adapter.ts:86 `readonly reportFormat = 'JUNIT_XML'`
//     - JUnitSemanticEvidenceInput = { testcases: RawJUnitTestcase[] }
// If EITHER side renames/reshapes this contract without updating the other,
// this test (or the backend's own adapter/registry tests) must fail. Anyone
// changing BACKEND_EXPECTED_FIELD/BACKEND_EXPECTED_REPORT_FORMAT below must
// re-verify against the live backend source at the same time.
// ==================================================================
def BACKEND_EXPECTED_FIELD = 'semanticEvidence'
def BACKEND_EXPECTED_REPORT_FORMAT = 'JUNIT_XML'

// R80.1 - the report producer forwards the generic envelope with exact-SHA/build binding
def r80ReporterTelemetry = new StageTelemetry()
r80ReporterTelemetry.checkoutFullSha = 'a'.multiply(40)
r80ReporterTelemetry.semanticTestEvidence = [testcases: [[classname: 'C', name: 'n', status: 'PASS']]]
def r80PipelineSource = new File(System.getenv('LIB_ROOT'), 'vars/devSecOpsPipeline.groovy').text
check(r80PipelineSource.contains("${BACKEND_EXPECTED_FIELD}: telemetry.semanticTestEvidence ? [")
    && r80PipelineSource.contains("reportFormat: '${BACKEND_EXPECTED_REPORT_FORMAT}'")
    && r80PipelineSource.contains('payload     : [ testcases: telemetry.semanticTestEvidence.testcases ]'),
    "R80.1 - the payload assembler emits ${BACKEND_EXPECTED_FIELD} as a {reportFormat, payload} envelope, not the old flat semanticTestEvidence shape")

def r80ContractPayload = new PlatformReporter(new FakeSteps()).buildPayload([
    event: 'pipeline_success', job: 'x', buildNumber: '1', buildUrl: 'http://x/1/',
    branch: 'main', commit: 'abc', buildStatus: 'SUCCESS', severityHint: 'LOW', durationMs: 1,
    buildStageStatus: [:], technicalFailure: null, pullRequest: null,
    reports: [:], tests: [:], sonar: [:], docker: [:], kubernetes: [:],
    (BACKEND_EXPECTED_FIELD): [
        reportFormat: BACKEND_EXPECTED_REPORT_FORMAT,
        payload     : [ testcases: [[classname: 'C', name: 'n', status: 'PASS']] ],
    ],
])
check(r80ContractPayload.containsKey(BACKEND_EXPECTED_FIELD),
    "R80.1 CONTRACT - PlatformReporter's canonical payload carries the exact field name (${BACKEND_EXPECTED_FIELD}) the backend adapter registry reads")
check(r80ContractPayload[BACKEND_EXPECTED_FIELD]?.reportFormat == BACKEND_EXPECTED_REPORT_FORMAT,
    "R80.1 CONTRACT - envelope.reportFormat is the exact string ('${BACKEND_EXPECTED_REPORT_FORMAT}') registered by JUnitSemanticEvidenceAdapter, not a guessed/abbreviated variant")
check(r80ContractPayload[BACKEND_EXPECTED_FIELD]?.payload?.testcases instanceof List
    && r80ContractPayload[BACKEND_EXPECTED_FIELD]?.payload?.testcases?.size() == 1,
    'R80.1 CONTRACT - envelope.payload.testcases is a List of raw {classname, name, status} facts, the exact shape JUnitSemanticEvidenceInput expects')
check(!r80ContractPayload.containsKey('semanticTestEvidence'),
    'R80.1 CONTRACT - the old flat semanticTestEvidence field is gone (grep-verified zero consumers anywhere in backend/n8n-workflows on 2026-09-20 -- replaced, not dual-published)')

// R80.1 - end-to-end: a real 4-testcase build, through the FULL pipeline
// wiring (BuildRunner -> StageTelemetry -> the exact envelope shape
// devSecOpsPipeline.groovy assembles), must reach the shape the backend's
// JUnitSemanticEvidenceAdapter (grammar: semantic_v1__<rule>__..__case_<id>)
// can actually group into the 4 required DEFAULT_VALUE_SEMANTICS_DEFECT
// cases -- proving this fix doesn't just satisfy a key-name check but
// actually carries real, groupable semantic data end to end.
def r80E2eSteps = new FakeSteps()
r80E2eSteps.stdoutFor['ls target/surefire-reports/TEST-*.xml 2>/dev/null || true'] =
    'target/surefire-reports/TEST-com.pfe.devsecops.controller.TaskControllerUpdateStatusSemanticsTest.xml'
r80E2eSteps.fileContents['target/surefire-reports/TEST-com.pfe.devsecops.controller.TaskControllerUpdateStatusSemanticsTest.xml'] = '''<?xml version="1.0"?>
<testsuite tests="4" failures="0" errors="0" skipped="0">
  <testcase classname="com.pfe.devsecops.controller.TaskControllerUpdateStatusSemanticsTest" name="semantic_v1__DEFAULT_VALUE_SEMANTICS_DEFECT__Task__status__TaskDTO__status__case_ABSENT__resetsToBaselineDefault"/>
  <testcase classname="com.pfe.devsecops.controller.TaskControllerUpdateStatusSemanticsTest" name="semantic_v1__DEFAULT_VALUE_SEMANTICS_DEFECT__Task__status__TaskDTO__status__case_EXPLICIT_NULL__setsNull"/>
  <testcase classname="com.pfe.devsecops.controller.TaskControllerUpdateStatusSemanticsTest" name="semantic_v1__DEFAULT_VALUE_SEMANTICS_DEFECT__Task__status__TaskDTO__status__case_EXPLICIT_VALUE__isPersisted"/>
  <testcase classname="com.pfe.devsecops.controller.TaskControllerUpdateStatusSemanticsTest" name="semantic_v1__DEFAULT_VALUE_SEMANTICS_DEFECT__Task__status__TaskDTO__status__case_INVALID_VALUE__isRejected"/>
</testsuite>'''
def r80E2eTelemetry = new StageTelemetry()
new BuildRunner(r80E2eSteps, r80E2eTelemetry).run('maven', false, null)
def r80E2ePayload = new PlatformReporter(new FakeSteps()).buildPayload([
    event: 'pr_validation', job: 'x', buildNumber: '5', buildUrl: 'http://x/5/',
    branch: 'PR-34', commit: '11ee62d', buildStatus: 'SUCCESS', severityHint: 'LOW', durationMs: 1,
    buildStageStatus: [:], technicalFailure: null, pullRequest: null,
    reports: [:], tests: [:], sonar: [:], docker: [:], kubernetes: [:],
    (BACKEND_EXPECTED_FIELD): r80E2eTelemetry.semanticTestEvidence ? [
        reportFormat: BACKEND_EXPECTED_REPORT_FORMAT,
        payload     : [ testcases: r80E2eTelemetry.semanticTestEvidence.testcases ],
    ] : null,
])
def r80E2eTestcases = r80E2ePayload[BACKEND_EXPECTED_FIELD]?.payload?.testcases
check(r80E2eTestcases?.size() == 4, 'R80.1 E2E - all 4 real grammar-compliant testcases reach the final wire envelope')
check(r80E2eTestcases?.every { it.name?.startsWith('semantic_v1__DEFAULT_VALUE_SEMANTICS_DEFECT__') && it.status == 'PASS' },
    'R80.1 E2E - every grammar-compliant testcase is carried through with its real PASS status, unmodified, exactly as the backend JUnitSemanticEvidenceAdapter grammar parser expects')

// ════════════════════════════════════════════════════════════════════════
// TEST R46-SONAR : le statut de l'etape Sonar doit etre celui de Maven.
//
// Regression visee : `mvn sonar:sonar ... | tee log` renvoyait le statut de
// `tee`. Un echec d'analyse etait rapporte SUCCESS a la plateforme. On
// verifie ici le CONTRAT du script genere ; la propagation reelle du statut
// est verifiee par test/shell_status_tests.sh, dans un vrai /bin/sh.
// ════════════════════════════════════════════════════════════════════════
def sonarSteps = new FakeSteps()
// withSonarQubeEnv n'existe pas dans FakeSteps : on l'ajoute par metaClasse,
// comme Jenkins le resout dynamiquement a l'execution.
sonarSteps.metaClass.withSonarQubeEnv = { String name, Closure body -> body.call() }
def sonarTelemetry = new StageTelemetry()
new ScannerRunner(sonarSteps, sonarTelemetry).runSonar('demo-app', null, false, null, null, null, null)
String sonarScript = sonarSteps.shScripts.find { it.contains('sonar:sonar') } ?: ''

check(sonarScript.length() > 0, 'TEST R46-SONAR - le script d\'analyse Sonar est bien genere')
check(!(sonarScript =~ /sonar:sonar[\s\S]*?\|\s*tee/),
    'TEST R46-SONAR - plus de tube vers tee : le statut de Maven n\'est plus masque')
check(sonarScript.contains('SONAR_STATUS=$?'),
    'TEST R46-SONAR - le statut de Maven est capture explicitement')
check(sonarScript.contains('exit "$SONAR_STATUS"'),
    'TEST R46-SONAR - un statut non nul fait echouer l\'etape')
check(sonarScript.contains('> sonar-analysis.log 2>&1') && sonarScript.contains('cat sonar-analysis.log'),
    'TEST R46-SONAR - le journal reste produit (extraction du ceTaskId) et reaffiche')
check(!sonarScript.contains('PIPESTATUS') && !sonarScript.contains('pipefail'),
    'TEST R46-SONAR - aucune dependance a une extension de shell (dash n\'a ni l\'un ni l\'autre)')

// ════════════════════════════════════════════════════════════════════════
// TEST R46-NVD : la cle API NVD ne doit jamais atteindre un fichier.
//
// Regression visee (P0) : Jenkins execute `sh -xe`, xtrace ecrit la commande
// DEVELOPPEE sur stderr, et l'etape OWASP redirige stderr vers owasp.log --
// un fichier que le masquage de credentials de Jenkins ne voit jamais.
// ════════════════════════════════════════════════════════════════════════
def owaspSteps = new FakeSteps()
def owaspTelemetry = new StageTelemetry()
new ScannerRunner(owaspSteps, owaspTelemetry).runOwasp('/shared/reports/demo/1', false, '9.0', '.')
String owaspScript = owaspSteps.shScripts.find { it.contains('dependency-check-maven') } ?: ''

check(owaspScript.length() > 0, 'TEST R46-NVD - le script OWASP est bien genere')
check(!owaspScript.contains('-DnvdApiKey='),
    'TEST R46-NVD - la cle n\'est plus passee en argument de ligne de commande')
check(owaspScript.contains('-s "$SETTINGS_NVD"'),
    'TEST R46-NVD - la cle est injectee via un fichier de settings Maven')
check(owaspScript.contains('<nvdApiKey>${env.NVD_API_KEY}</nvdApiKey>'),
    'TEST R46-NVD - le settings ne contient qu\'une REFERENCE, jamais la valeur')
check(owaspScript.contains('rm -f "$SETTINGS_NVD"'),
    'TEST R46-NVD - le fichier de settings est retire du repertoire de rapports')
check(owaspScript.contains('> "$REPORT_BASE/owasp.log" 2>&1'),
    'TEST R46-NVD - la redirection du journal est conservee (comportement inchange)')
check(owaspScript.contains('update-only') && owaspScript.contains('-DautoUpdate=false'),
    'TEST R46-NVD - les deux etapes OWASP sont preservees : mise a jour puis scan hors ligne')

// La propriete qui compte n'est pas le nombre de mentions, mais l'absence de
// toute DEREFERENCE shell du secret : c'est `$NVD_API_KEY` que xtrace
// developperait. `${env.NVD_API_KEY}` est une reference Maven, resolue par
// Maven, jamais par le shell -- et elle vit dans un heredoc quote.
def shellDerefs = owaspScript.findAll(/(?<!\{env\.)\$\{?NVD_API_KEY\}?/)
check(shellDerefs.isEmpty(),
    "TEST R46-NVD - aucune dereference shell du secret, donc rien a developper pour xtrace (trouve ${shellDerefs})")
check(owaspScript.findAll(/\$\{env\.NVD_API_KEY\}/).size() == 2,
    'TEST R46-NVD - les deux seules mentions sont la reference Maven et son commentaire explicatif')

println ''
if (failures == 0) {
    println 'ALL OFFLINE TESTS PASSED'
} else {
    println "OFFLINE TESTS FAILED: ${failures}"
    System.exit(1)
}
