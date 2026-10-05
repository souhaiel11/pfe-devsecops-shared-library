// Phase 2A.9 — L1-L7. Deliberately a SEPARATE file from offline_tests.groovy
// (which already has unrelated uncommitted WIP on it) so this provenance-only
// addition stays isolatable: `git add` this file + DockerRunner.groovy +
// StageTelemetry.groovy + devSecOpsPipeline.groovy as one commit, without
// touching anything else.
//
// Run (from the repo root, with a groovy-all jar available):
//   java -cp "$GROOVY_JAR:$OUT" org.codehaus.groovy.tools.FileSystemCompiler -d "$OUT" test/docker_runner_oci_tests.groovy
//   java -cp "$GROOVY_JAR:$OUT" docker_runner_oci_tests
import org.pfe.devsecops.DockerRunner
import org.pfe.devsecops.StageTelemetry

class FakeStepsWithDockerfile extends FakeSteps {
    def fileExists(String path) { true }
}

int failures = 0
def check(boolean cond, String label) {
    if (cond) {
        println "PASS: ${label}"
    } else {
        println "FAIL: ${label}"
        failures++
    }
}

String FULL_SHA = 'a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2'

def testL1 = {
    def telemetry = new StageTelemetry()
    telemetry.checkoutFullSha = FULL_SHA
    def steps = new FakeSteps()
    new DockerRunner(steps, telemetry).build('myapp', '42', null, null)
    String script = steps.shScripts.last()
    check(script.contains("--label org.opencontainers.image.revision=\"${FULL_SHA}\""), 'L1 full checkout SHA emitted as OCI revision')
    check(telemetry.docker.build_status == 'SUCCESS', 'L1 build succeeds with a valid full SHA')
}
testL1.call()

def testL2 = {
    [null, '', 'a' * 39, 'a' * 41, 'g' * 40, FULL_SHA.toUpperCase().take(39) + ' '].each { badSha ->
        def telemetry = new StageTelemetry()
        telemetry.checkoutFullSha = badSha
        def steps = new FakeSteps()
        int before = steps.shScripts.size()
        new DockerRunner(steps, telemetry).build('myapp', '42', null, null)
        check(telemetry.docker.build_status == 'FAILED', "L2 fails closed for checkoutFullSha=${badSha.inspect()}")
        check(steps.shScripts.size() == before, "L2 never invokes docker build at all for checkoutFullSha=${badSha.inspect()}")
    }
    // A valid uppercase-hex full SHA must still be accepted (regex is case-insensitive).
    def telemetry = new StageTelemetry()
    telemetry.checkoutFullSha = FULL_SHA.toUpperCase()
    def steps = new FakeSteps()
    new DockerRunner(steps, telemetry).build('myapp', '42', null, null)
    check(telemetry.docker.build_status == 'SUCCESS', 'L2b uppercase-hex full SHA is still accepted')
}
testL2.call()

def testL3 = {
    def t1 = new StageTelemetry()
    t1.checkoutFullSha = FULL_SHA
    t1.checkoutSourceUrl = 'https://github.com/souhaiel11/pfe-app-test.git'
    def s1 = new FakeSteps()
    new DockerRunner(s1, t1).build('myapp', '42', null, null)
    check(s1.shScripts.last().contains('--label org.opencontainers.image.source="https://github.com/souhaiel11/pfe-app-test.git"'), 'L3a safe source URL rendered')

    def t2 = new StageTelemetry()
    t2.checkoutFullSha = FULL_SHA
    t2.checkoutSourceUrl = null
    def s2 = new FakeSteps()
    new DockerRunner(s2, t2).build('myapp', '42', null, null)
    check(!s2.shScripts.last().contains('image.source'), 'L3b absent source URL omits the label entirely (never guessed)')

    def t3 = new StageTelemetry()
    t3.checkoutFullSha = FULL_SHA
    t3.checkoutSourceUrl = 'https://evil.example/"; rm -rf /tmp/x; echo "'
    def s3 = new FakeSteps()
    new DockerRunner(s3, t3).build('myapp', '42', null, null)
    String script3 = s3.shScripts.last()
    check(!script3.contains('image.source'), 'L3c unsafe source URL (quotes/shell metacharacters) is omitted, never interpolated raw')
    check(!script3.contains('rm -rf'), 'L3c the unsafe payload never reaches the generated shell script at all')
}
testL3.call()

def testL4 = {
    def telemetry = new StageTelemetry()
    telemetry.checkoutFullSha = FULL_SHA
    def steps = new FakeSteps()
    new DockerRunner(steps, telemetry).build('myapp', '42', null, null)
    check(steps.shScripts.last().contains('--label org.opencontainers.image.created="$(date -u +%Y-%m-%dT%H:%M:%SZ)"'), 'L4 created label uses a valid RFC3339 UTC format expression')
}
testL4.call()

def testL5 = {
    def telemetry = new StageTelemetry()
    telemetry.checkoutFullSha = FULL_SHA
    def steps = new FakeSteps()
    new DockerRunner(steps, telemetry).build('myapp', '42', null, null)
    String script = steps.shScripts.last()
    check(script.contains('-t "myapp:42"'), 'L5 image is still built with "-t name:tag" unchanged')
    check(script.contains('docker tag "myapp:42" "myapp:latest"'), 'L5 the pre-existing local :latest alias tag is unchanged')
    check(telemetry.docker.image_tag == 'myapp:42', 'L5 telemetry.docker.image_tag unchanged')
}
testL5.call()

def testL6 = {
    def telemetry = new StageTelemetry()
    telemetry.checkoutFullSha = FULL_SHA
    def steps = new FakeSteps()
    new DockerRunner(steps, telemetry).build('myapp', '42', null, null)
    check(!steps.shScripts.any { it.contains('push') }, 'L6 no docker push appears in any generated shell script')
    check(telemetry.docker.push_status == 'NOT_ATTEMPTED', 'L6 push_status remains the honest NOT_ATTEMPTED value')
}
testL6.call()

def testL7 = {
    def detectorClass = this.class.classLoader.loadClass('org.pfe.devsecops.ProjectDetector')
    def noDockerfileSteps = new FakeSteps()
    def detectorAbsent = detectorClass.newInstance(noDockerfileSteps)
    check(detectorAbsent.detectDockerfile(null, null) == false, 'L7a detectDockerfile still returns false when fileExists reports absent (unchanged default)')
    def dockerfileSteps = new FakeStepsWithDockerfile()
    def detectorPresent = detectorClass.newInstance(dockerfileSteps)
    check(detectorPresent.detectDockerfile(null, null) == true, 'L7b detectDockerfile still returns true when fileExists reports present (unchanged)')
}
testL7.call()

println ''
if (failures == 0) {
    println 'ALL docker_runner_oci_tests PASSED'
} else {
    println "${failures} docker_runner_oci_tests FAILED"
    System.exit(1)
}
