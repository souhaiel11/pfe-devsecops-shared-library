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
                     'reports', 'tests', 'sonar', 'docker', 'kubernetes'] as Set
check(payload.keySet() == expectedKeys, 'TEST L - canonical payload keys match the pre-migration WF1 contract exactly')
check(payload.logs_url == 'http://jenkins/job/pfe-app-test/134/consoleText', 'TEST L - logs_url derived correctly')
check(payload.build_number == '134', 'TEST L - build_number forwarded (WF1 normalizes camelCase/snake_case at the boundary)')

println ''
if (failures == 0) {
    println 'ALL OFFLINE TESTS PASSED'
} else {
    println "OFFLINE TESTS FAILED: ${failures}"
    System.exit(1)
}
