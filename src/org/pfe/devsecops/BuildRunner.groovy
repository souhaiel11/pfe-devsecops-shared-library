package org.pfe.devsecops

/**
 * PIPELINE_GENERIC build/test execution. Only Maven is implemented today
 * because it is the only build type this platform has actually exercised
 * end to end (pfe-app-test). Gradle/Node detection exists in
 * ProjectDetector for future onboarding, but BuildRunner refuses to
 * fabricate support for a build type it has never run -- it fails loudly
 * instead of silently no-op'ing, which would produce fake SUCCESS telemetry.
 */
class BuildRunner implements Serializable {

    private final def steps
    private final StageTelemetry telemetry

    BuildRunner(steps, StageTelemetry telemetry) {
        this.steps = steps
        this.telemetry = telemetry
    }

    /**
     * @param buildType     result of ProjectDetector.detectBuildType()
     * @param skipTests     project-level decision, forwarded to the tool flags -- never
     *                      silently re-derived. When true, tests.status is reported as the
     *                      explicit SKIPPED, never fabricated as PASSED/0-failures.
     */
    void run(String buildType, boolean skipTests, String workingDirectory) {
        if (buildType != 'maven') {
            steps.error("devSecOpsPipeline: buildType '${buildType}' is not supported yet by the shared library BuildRunner. " +
                "Only Maven projects are currently onboarded. Add support in BuildRunner before using this on a ${buildType} project.")
        }

        telemetry.buildStageStatus['build'] = 'FAILED'
        steps.dir(workingDirectory ?: '.') {
            steps.catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                steps.sh """
                    set -e
                    mvn clean package -B -DskipTests=${skipTests} -Djacoco.skip=true
                """
                telemetry.buildStageStatus['build'] = 'SUCCESS'
            }

            if (skipTests) {
                // Deliberate pipeline-level skip: never fabricate total=0 as "tests passed".
                // Explicit SKIPPED, consumed as-is by WF1 (mapped to NOT_RUN, never PASSED).
                telemetry.tests = [status: 'SKIPPED', total: 0, failures: 0, skipped: 0, coverage: null]
            } else {
                parseSurefireResults()
            }
        }
    }

    private void parseSurefireResults() {
        String summary = steps.sh(
            script: "cat target/surefire-reports/*.txt 2>/dev/null | grep 'Tests run:' | tail -1 || true",
            returnStdout: true
        ).trim()
        def m = (summary =~ /Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+),\s*Skipped:\s*(\d+)/)
        if (summary && m.find()) {
            int total = m.group(1) as Integer
            int failures = (m.group(2) as Integer) + (m.group(3) as Integer)
            int skipped = m.group(4) as Integer
            telemetry.tests = [
                status  : failures > 0 ? 'FAILED' : 'SUCCESS',
                total   : total, failures: failures, skipped: skipped, coverage: null
            ]
        } else {
            // Tests were supposed to run but no usable result was found: honest UNKNOWN,
            // never a fabricated 0/PASSED.
            telemetry.tests = [status: 'UNKNOWN', total: null, failures: null, skipped: null, coverage: null]
        }
    }
}
