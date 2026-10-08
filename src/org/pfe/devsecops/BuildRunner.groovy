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
                // R46 -- `-Djacoco.skip=true` est retire de CETTE etape.
                //
                // C'est la seule etape ou les tests tournent : y desactiver
                // l'agent JaCoCo garantissait qu'aucun rapport de couverture ne
                // puisse exister, et donc que Sonar affiche 0 % pour tout
                // projet, meme correctement configure. Constate en reel sur
                // un projet reel pourtant correctement configure : « No coverage
                // report can be found with sonar.coverage.jacoco.xmlReportPaths ».
                //
                // Le drapeau reste en place dans l'analyse Sonar
                // (ScannerRunner.runSonar), ou aucun test ne tourne et ou
                // l'agent n'aurait rien a mesurer.
                //
                // Generique et sans effet de bord : un projet qui ne declare
                // pas jacoco-maven-plugin ne voit aucun changement -- la
                // propriete n'est alors lue par personne. Un projet qui le
                // declare obtient enfin une couverture reelle.
                steps.sh """
                    set -e
                    mvn clean package -B -DskipTests=${skipTests}
                """
                telemetry.buildStageStatus['build'] = 'SUCCESS'
            }

            if (skipTests) {
                // Deliberate pipeline-level skip: never fabricate total=0 as "tests passed".
                // Explicit SKIPPED, consumed as-is by WF1 (mapped to NOT_RUN, never PASSED).
                telemetry.tests = [status: 'SKIPPED', total: 0, failures: 0, skipped: 0, coverage: null]
            } else {
                processSurefireResults()
            }
        }
    }

    /**
     * R80 -- reads every Surefire XML report EXACTLY ONCE and derives BOTH
     * the aggregate suite totals and the per-testcase semantic-evidence
     * facts from that SAME parsed structure. Replaces the previous
     * parseSurefireResults(), which derived the aggregate from
     * `cat target/surefire-reports/*.txt | grep 'Tests run:' | tail -1` --
     * `tail -1` silently kept only whichever CLASS happened to sort last
     * alphabetically, undercounting the real suite total (proven live:
     * build #4 actually ran 17 tests across 3 classes; the old code
     * reported 10, matching only TaskServiceTest.txt's own count). Also
     * publishes the same XML to Jenkins' own `junit` step so a real
     * testReport becomes available (previously never called at all, which
     * is why /testReport/api/json 404'd).
     */
    private void processSurefireResults() {
        steps.junit(testResults: 'target/surefire-reports/*.xml', allowEmptyResults: true)

        List<Map> testcases = []
        int total = 0, failures = 0, errors = 0, skipped = 0
        boolean anyFileParsed = false

        String listing = steps.sh(
            script: 'ls target/surefire-reports/TEST-*.xml 2>/dev/null || true',
            returnStdout: true,
        ).trim()
        if (listing) {
            for (String path : listing.split('\n')) {
                String trimmedPath = path?.trim()
                if (!trimmedPath) continue
                String xml
                try {
                    xml = steps.readFile(trimmedPath)
                } catch (ignored) {
                    continue
                }
                def parsed
                try {
                    parsed = new XmlSlurper().parseText(xml)
                } catch (ignored) {
                    continue // malformed XML for this one file -- skip it, never fabricate a result
                }
                anyFileParsed = true
                // Prefer the <testsuite> element's OWN reported attributes
                // (what Surefire itself asserts about this file) over
                // re-deriving them by counting <testcase> children --
                // both should agree, but the element's own totals are the
                // more authoritative, direct source.
                total    += (parsed.@tests.text()    ?: '0') as Integer
                failures += (parsed.@failures.text()  ?: '0') as Integer
                errors   += (parsed.@errors.text()    ?: '0') as Integer
                skipped  += (parsed.@skipped.text()   ?: '0') as Integer

                parsed.testcase.each { tc ->
                    String status = 'PASS'
                    if (tc.failure.size() > 0) status = 'FAILURE'
                    else if (tc.error.size() > 0) status = 'ERROR'
                    else if (tc.skipped.size() > 0) status = 'SKIPPED'
                    testcases << [classname: tc.@classname.text(), name: tc.@name.text(), status: status]
                }
            }
        }

        if (anyFileParsed) {
            int totalFailures = failures + errors
            telemetry.tests = [
                status  : totalFailures > 0 ? 'FAILED' : 'SUCCESS',
                total   : total, failures: totalFailures, skipped: skipped, coverage: null,
            ]
        } else {
            // Tests were supposed to run but no usable, parseable report was found:
            // honest UNKNOWN, never a fabricated 0/PASSED.
            telemetry.tests = [status: 'UNKNOWN', total: null, failures: null, skipped: null, coverage: null]
        }

        // R80 -- bounded raw <testcase> facts for the platform's semantic
        // evidence bridge. Never assigns meaning to a test name here --
        // that decision belongs entirely to the backend adapter (see
        // JUnitSemanticEvidenceAdapter), the only place a naming
        // convention is actually parsed. Present-with-empty-list (not
        // null) whenever at least one report was found, even with zero
        // testcases in it -- a caller can tell "ran, found nothing" from
        // "did not run at all".
        telemetry.semanticTestEvidence = anyFileParsed ? [testcases: testcases] : null
    }
}
