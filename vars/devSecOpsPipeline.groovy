import org.pfe.devsecops.PlatformConfig
import org.pfe.devsecops.ProjectDetector
import org.pfe.devsecops.StageTelemetry
import org.pfe.devsecops.BuildRunner
import org.pfe.devsecops.DockerRunner
import org.pfe.devsecops.ScannerRunner
import org.pfe.devsecops.PlatformReporter
import org.pfe.devsecops.SafeCleanup
import groovy.json.JsonSlurperClassic

/**
 * devSecOpsPipeline -- the entire DevSecOps CI/CD platform, invoked from a
 * project Jenkinsfile as:
 *
 *   @Library('pfe-devsecops') _
 *   devSecOpsPipeline {
 *       applicationName = 'my-app'
 *   }
 *
 * JENKINS PRODUCES FACTS ONLY. This script never decides problemClass,
 * owner, route, readiness, or risk -- it reports build/test/scanner outcomes
 * and technical failure codes, and WF1/backend interpret them. No PR,
 * deployment, or remediation workflow is ever triggered from here.
 *
 * Project-specific config accepted in the closure (all optional except
 * applicationName when it cannot be reliably auto-detected from JOB_NAME):
 *   applicationName   - String
 *   workingDirectory  - String, relative path if the app doesn't live at repo root
 *   dockerfile        - String, relative path if not './Dockerfile'
 *   skipTests         - boolean, default false. Set true only when the project
 *                        genuinely does not run tests in CI yet -- this is a
 *                        truthful declaration, not a convenience default.
 *   zapTargetUrl      - String override if the deployed Service name/port differs
 *                        from the http://<applicationName>:8080 convention.
 */
def call(Closure body = null) {
    Map config = [:]
    if (body) {
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body.delegate = config
        body()
    }

    // Mandatory platform policy: not a per-project choice (Section 12).
    properties([
        disableConcurrentBuilds(),
        buildDiscarder(logRotator(numToKeepStr: '15')),
        parameters([
            booleanParam(
                name: 'JENKINS_HARD_GATE',
                defaultValue: PlatformConfig.DEFAULT_JENKINS_HARD_GATE,
                description: 'If true, Jenkins fails itself on a CVSS-critical finding. Default false: enforcement is delegated to the platform (WF1/backend).'
            ),
            string(
                name: 'CVSS_FAIL_THRESHOLD',
                defaultValue: PlatformConfig.DEFAULT_CVSS_FAIL_THRESHOLD,
                description: 'CVSS blocking threshold, used only when JENKINS_HARD_GATE=true.'
            ),
            string(
                name: 'PFE_VALIDATION_CONTEXT',
                defaultValue: '',
                description: 'Opaque non-secret PR validation correlation supplied only by the authenticated platform.'
            )
        ])
    ])

    timeout(time: PlatformConfig.TIMEOUT_PIPELINE_HOURS, unit: 'HOURS') {
        node {
            def telemetry = new StageTelemetry()
            def detector = new ProjectDetector(this)
            def buildRunner = new BuildRunner(this, telemetry)
            def dockerRunner = new DockerRunner(this, telemetry)
            def scanners = new ScannerRunner(this, telemetry)
            def reporter = new PlatformReporter(this)
            def cleanup = new SafeCleanup(this)

            String applicationName = config.applicationName ?: env.JOB_NAME?.tokenize('/')?.get(0)
            if (!applicationName) {
                error('devSecOpsPipeline: applicationName could not be auto-detected from JOB_NAME and was not set explicitly. Add: devSecOpsPipeline { applicationName = \'your-app\' }')
            }
            String imageName = applicationName
            String workingDirectory = config.workingDirectory ?: null
            boolean configuredSkipTests = config.containsKey('skipTests') ? config.skipTests : false
            String zapTargetUrl = config.zapTargetUrl ?: "http://${applicationName}:8080"

            boolean isPR = env.CHANGE_ID != null
            Map prValidation = isPR ? prValidationContext(params.PFE_VALIDATION_CONTEXT) : [:]
            if (isPR && !prValidation) {
                error('PR_VALIDATION_CONTEXT_MISSING: launch PR validation through the authenticated platform action.')
            }
            boolean skipTests = isPR ? false : configuredSkipTests
            telemetry.prValidation = prValidation
            String imageTag = env.BUILD_NUMBER
            String reportBase = "${PlatformConfig.JENKINS_REPORT_ROOT}/${applicationName}/${env.BUILD_NUMBER}"
            String n8nReportBase = "${PlatformConfig.N8N_REPORT_ROOT}/${applicationName}/${env.BUILD_NUMBER}"

            boolean checkoutFailed = false
            boolean zapStageEntered = false
            boolean dockerfilePresent = false

            withCredentials([
                string(credentialsId: PlatformConfig.CRED_SONAR_TOKEN, variable: 'SONAR_TOKEN'),
                string(credentialsId: PlatformConfig.CRED_N8N_API_KEY, variable: 'N8N_API_KEY'),
                string(credentialsId: PlatformConfig.CRED_NVD_API_KEY, variable: 'NVD_API_KEY')
            ]) {
                stage('Init') {
                    env.PATH = "${tool 'M3'}/bin:${env.PATH}"
                    sh "mkdir -p '${reportBase}'"
                    echo """============================================
 Job              : ${env.JOB_NAME}
 Build            : #${env.BUILD_NUMBER}
 App              : ${applicationName}
 Image            : ${imageName}:${imageTag}
 Is PR            : ${isPR}
 Jenkins reports  : ${reportBase}
============================================"""
                }

                try {
                    stage('Checkout') {
                        def scmVars = checkout(scm)
                        // Defect C (QA-BUILD-135-R1): env.GIT_COMMIT proved unreliable from
                        // this scripted-library flow (build #135 sent commit="unknown" despite
                        // a confirmed-successful checkout). Capture the SHA directly instead of
                        // trusting env.GIT_COMMIT as the source of truth -- fallback order is
                        // git rev-parse (this checkout, guaranteed accurate) -> the checkout
                        // step's own return map -> null (never fabricated).
                        String capturedSha = sh(script: 'git rev-parse HEAD 2>/dev/null || true', returnStdout: true).trim()
                        telemetry.checkoutFullSha = capturedSha ?: (scmVars?.GIT_COMMIT ?: null)
                        telemetry.checkoutShortSha = telemetry.checkoutFullSha ? telemetry.checkoutFullSha.take(8) : null
                        if (isPR) {
                            String expected = String(prValidation.expectedPrHeadSha ?: '').toLowerCase()
                            if (!(expected ==~ /[a-f0-9]{40}/) || telemetry.checkoutFullSha?.toLowerCase() != expected) {
                                error("PR_HEAD_SHA_MISMATCH: checkout does not match the persisted validation target")
                            }
                        }
                        echo "Commit: ${telemetry.checkoutShortSha ?: 'unknown'}"
                    }
                } catch (checkoutEx) {
                    checkoutFailed = true
                    currentBuild.result = 'FAILURE'
                    echo "Checkout failed: ${checkoutEx.message}"
                }

                if (!checkoutFailed) {
                    String buildType = detector.detectBuildType(workingDirectory)
                    if (!buildType) {
                        error("devSecOpsPipeline: could not auto-detect a supported build type (pom.xml/build.gradle/package.json) under '${workingDirectory ?: '.'}'. If the app lives in a subdirectory, set workingDirectory.")
                    }

                    stage('Build') {
                        buildRunner.run(buildType, skipTests, workingDirectory)
                    }

                    if (PlatformConfig.SONAR_ENABLED) {
                        stage('SonarQube Analysis') {
                            scanners.runSonar(applicationName, workingDirectory, isPR, env.CHANGE_ID, env.CHANGE_BRANCH, env.CHANGE_TARGET)
                            if (isPR) {
                                catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                                    scanners.resolveExactAnalysis()
                                }
                            }
                        }
                    }

                    dockerfilePresent = detector.detectDockerfile(workingDirectory, config.dockerfile)
                    if (dockerfilePresent) {
                        stage('Docker Build') {
                            dockerRunner.build(imageName, imageTag, config.dockerfile, workingDirectory)
                        }
                    }

                    if (PlatformConfig.TRIVY_ENABLED && dockerfilePresent) {
                        stage('Trivy Scan') {
                            timeout(time: PlatformConfig.TIMEOUT_TRIVY_MINUTES, unit: 'MINUTES') {
                                scanners.runTrivy(imageName, imageTag, env.BUILD_NUMBER, reportBase)
                            }
                        }
                    }

                    if (PlatformConfig.OWASP_ENABLED) {
                        stage('OWASP Dependency Check') {
                            timeout(time: PlatformConfig.TIMEOUT_OWASP_MINUTES, unit: 'MINUTES') {
                                scanners.runOwasp(reportBase, params.JENKINS_HARD_GATE, params.CVSS_FAIL_THRESHOLD, workingDirectory)
                            }
                        }
                    }

                    if (!isPR && PlatformConfig.ZAP_ENABLED_ON_BRANCH_BUILDS) {
                        zapStageEntered = true
                        stage('Kubernetes Target Check') {
                            scanners.checkKubernetesTarget(PlatformConfig.K8S_NAMESPACE, PlatformConfig.KUBECONFIG_PATH, applicationServiceName(zapTargetUrl))
                        }
                        stage('ZAP DAST Scan') {
                            timeout(time: PlatformConfig.TIMEOUT_ZAP_MINUTES, unit: 'MINUTES') {
                                scanners.runZap(PlatformConfig.K8S_NAMESPACE, PlatformConfig.KUBECONFIG_PATH, zapTargetUrl, env.BUILD_NUMBER, reportBase)
                            }
                        }
                    } else if (!isPR) {
                        // ZAP_ENABLED_ON_BRANCH_BUILDS=false is mandatory platform policy, not
                        // a project choice -- still worth a reachability signal for humans.
                        stage('Kubernetes Target Check') {
                            scanners.checkKubernetesTarget(PlatformConfig.K8S_NAMESPACE, PlatformConfig.KUBECONFIG_PATH, applicationServiceName(zapTargetUrl))
                        }
                    }
                }

                // N8N_API_KEY must stay bound through the platform callback below, on
                // every path including a checkout failure -- WF1 must be notified of a
                // pre-stage failure too, not just of completed runs.
                reportToPlatform(this, telemetry, cleanup, reporter, [
                    applicationName: applicationName, imageName: imageName, imageTag: imageTag,
                    isPR: isPR, reportBase: reportBase, n8nReportBase: n8nReportBase,
                    checkoutFailed: checkoutFailed, zapTargetUrl: zapTargetUrl, zapStageEntered: zapStageEntered,
                    prValidation: prValidation, dockerfilePresent: dockerfilePresent
                ])
            }
        }
    }
}

private Map prValidationContext(def rawContext) {
    String encoded = String.valueOf(rawContext ?: '').trim()
    if (!encoded) return [:]
    try {
        String json = new String(Base64.urlDecoder.decode(encoded), 'UTF-8')
        Map value = (Map) new JsonSlurperClassic().parseText(json)
        def required = ['validationRequestId','projectId','incidentId','fixRequestId','batchId','batchKey',
                        'attemptCount','repository','prNumber','prHeadBranch','expectedPrHeadSha','jenkinsJob','prValidationJob']
        if (required.any { value[it] == null || String.valueOf(value[it]).trim() == '' }) return [:]
        return value
    } catch (ignored) {
        return [:]
    }
}

/** Extracts the k8s Service name from a http://<service>:<port> convention URL, for the reachability check. */
def applicationServiceName(String zapTargetUrl) {
    def m = (zapTargetUrl =~ /^https?:\/\/([^:\/]+)/)
    return m.find() ? m.group(1) : zapTargetUrl
}

/**
 * Equivalent of the original post{always{}} block. Runs inside the same
 * node context that held the checkout/build/scan stages -- never a
 * separately re-acquired node -- so it is never at risk of the
 * MissingContextVariableException the pre-migration pipeline had to work
 * around (Section 13). Any failure here is swallowed and logged: it must
 * never mask the pipeline's real result (Section 14).
 */
def reportToPlatform(script, telemetry, cleanup, reporter, Map ctx) {
    script.timeout(time: PlatformConfig.TIMEOUT_POST_REPORT_MINUTES, unit: 'MINUTES') {
        try {
            def env = script.env
            def currentBuild = script.currentBuild
            String buildStatus = currentBuild.currentResult ?: 'SUCCESS'

            String event
            if (ctx.isPR) {
                event = 'pr_validation'
            } else {
                event = buildStatus == 'SUCCESS'  ? 'pipeline_success'
                      : buildStatus == 'UNSTABLE' ? 'pipeline_unstable'
                      :                             'pipeline_failed'
            }

            String branch = env.GIT_BRANCH?.replaceAll('origin/', '') ?: 'main'
            // Defect C fallback order: captured checkout SHA (git rev-parse, taken at
            // the moment of checkout -- see Checkout stage) -> env.GIT_COMMIT -> only
            // 'unknown' if genuinely unresolved. Never fabricated.
            String commit = telemetry.checkoutShortSha ?: (env.GIT_COMMIT?.take(8)) ?: 'unknown'

            Map technicalFailure = null
            if (ctx.checkoutFailed) {
                // Nothing beyond checkout was attempted: NOT_REACHED everywhere, never
                // a fabricated status. Jenkins reports the raw technical code only --
                // WF1 owns the problemClass/owner/route classification (Section 9/13),
                // so it is deliberately absent here.
                telemetry.markUnreachedFromCheckoutFailure()
                technicalFailure = [
                    phase        : 'SCM_CHECKOUT',
                    technicalCode: 'SCM_CHECKOUT_NETWORK_FAILURE',
                    message      : 'Git checkout did not complete -- see Jenkins console for the underlying git/network error.'
                ]
            }

            boolean trivyAvailable = cleanup.reportAvailable(ctx.reportBase, 'trivy-report.json')
            boolean zapAvailable = cleanup.reportAvailable(ctx.reportBase, 'zap-report.json')
            boolean owaspAvailable = cleanup.reportAvailable(ctx.reportBase, 'dependency-check-report.json')

            if (!ctx.checkoutFailed) {
                telemetry.buildStageStatus['tests'] = telemetry.tests.status
                telemetry.buildStageStatus['trivy'] = trivyAvailable ? 'COMPLETED' : 'UNKNOWN'
                telemetry.buildStageStatus['owasp'] = owaspAvailable ? 'COMPLETED' : 'UNKNOWN'
                // Defect B: never "COMPLETED" from bare file existence -- a status-stub
                // report (zap_k8s_unreachable / zap_pod_launch_failed / zap_report_missing)
                // is a diagnostic artifact, not a successful scan. Canonical vocabulary:
                // NOT_RUN (deliberately not entered) | FAILED (entered, no usable result) |
                // COMPLETED (launched and produced a usable report).
                telemetry.buildStageStatus['zap'] = telemetry.zapStageStatus(ctx.zapStageEntered)
                telemetry.buildStageStatus['build'] = telemetry.buildStageStatus['build'] ?: 'UNKNOWN'
                telemetry.buildStageStatus['sonar'] = telemetry.buildStageStatus['sonar'] ?: 'UNKNOWN'
                telemetry.buildStageStatus['docker'] = telemetry.docker.build_status
            }

            // severity_hint: never authoritative (WF1 already ignores this for its
            // canonical calculation), purely informative for a human reading the raw
            // payload. Cheap real-scanner signal, never overwrites real findings.
            boolean criticalTrivy = trivyAvailable &&
                cleanup.grepMarker("${ctx.reportBase}/trivy-report.json", 'CRITICAL')
            boolean criticalOwasp = owaspAvailable &&
                cleanup.grepMarker("${ctx.reportBase}/owasp.log", 'CVSS score greater than or equal to')

            String severityHint = buildStatus == 'FAILURE'          ? 'HIGH'
                                 : (criticalTrivy || criticalOwasp) ? 'HIGH'
                                 : buildStatus == 'UNSTABLE'         ? 'MEDIUM'
                                 :                                    'LOW'

            Map payloadObject = reporter.buildPayload([
                event           : event,
                job             : env.JOB_NAME,
                buildNumber     : env.BUILD_NUMBER,
                buildUrl        : env.BUILD_URL,
                branch          : branch,
                commit          : commit,
                buildStatus     : buildStatus,
                severityHint    : severityHint,
                durationMs      : currentBuild.duration,
                prValidation    : ctx.prValidation,
                checkoutSha     : telemetry.checkoutFullSha,
                buildStageStatus: telemetry.buildStageStatus,
                technicalFailure: technicalFailure,
                pullRequest     : env.CHANGE_ID ? [
                    number: env.CHANGE_ID, branch: env.CHANGE_BRANCH,
                    target: env.CHANGE_TARGET, url: env.CHANGE_URL, title: env.CHANGE_TITLE
                ] : null,
                reports: [
                    jenkinsBasePath: ctx.reportBase,
                    basePath       : ctx.n8nReportBase,
                    trivyPath      : "${ctx.n8nReportBase}/trivy-report.json",
                    zapPath        : "${ctx.n8nReportBase}/zap-report.json",
                    owaspPath      : "${ctx.n8nReportBase}/dependency-check-report.json",
                    available      : [trivy: trivyAvailable, zap: zapAvailable, owasp: owaspAvailable]
                ],
                tests : telemetry.tests,
                sonar : [
                    project_key  : ctx.applicationName,
                    dashboard_url: "${PlatformConfig.SONAR_HOST_URL}/dashboard?id=${ctx.applicationName}",
                    ceTaskId     : telemetry.sonar.ceTaskId,
                    analysisId   : telemetry.sonar.analysisId
                ],
                docker: [
                    image       : "${ctx.imageName}:${ctx.imageTag}",
                    build_status: telemetry.docker.build_status,
                    image_tag   : telemetry.docker.image_tag,
                    push_status : telemetry.docker.push_status
                ],
                kubernetes: [namespace: PlatformConfig.K8S_NAMESPACE, target: ctx.zapTargetUrl],
                // Defect D: factual ZAP execution diagnostics, additive alongside the
                // existing reports.available.zap flag WF1 already consumes. Jenkins states
                // facts only (launchAttempted/launchSucceeded/podObserved/resultAvailable/
                // technicalCode) -- it never decides problemClass/owner/route; WF1 maps
                // these facts to that governance classification itself.
                zap: ctx.zapStageEntered ? telemetry.zap : null
            ])

            if (ctx.isPR) {
                def stageStatus = { String key ->
                    String value = String.valueOf(telemetry.buildStageStatus[key] ?: (key == 'tests' ? telemetry.tests.status : 'UNKNOWN')).toUpperCase()
                    ['SUCCESS','COMPLETED','PASSED'].contains(value) ? 'PASSED' : value
                }
                payloadObject.requiredStages = [
                    [stage:'build', required:true, status:stageStatus('build')],
                    [stage:'tests', required:true, status:stageStatus('tests')],
                    [stage:'sonar', required:true, status:stageStatus('sonar')],
                    [stage:'docker', required:ctx.dockerfilePresent, status:stageStatus('docker')],
                    [stage:'trivy', required:ctx.dockerfilePresent && PlatformConfig.TRIVY_ENABLED, status:stageStatus('trivy')],
                    [stage:'owasp', required:PlatformConfig.OWASP_ENABLED, status:stageStatus('owasp')],
                    [stage:'zap', required:false, status:stageStatus('zap')]
                ]
            }

            reporter.send(payloadObject, ctx.reportBase, currentBuild)
        } catch (ex) {
            // Reporting/cleanup must never mask the pipeline's real result
            // (Section 14) -- log and move on, never rethrow, never touch
            // currentBuild.result here.
            script.echo "Reporting failed (non-fatal, original build result preserved): ${ex.message}"
        } finally {
            cleanup.safeDeleteDir()
        }
    }
}
