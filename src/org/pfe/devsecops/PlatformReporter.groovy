package org.pfe.devsecops

/**
 * Owns the canonical WF1 callback: schema, serialization, safe retries,
 * timeout. The project Jenkinsfile never knows this contract exists.
 *
 * Jenkins produces FACTS ONLY here -- no problemClass, owner, route,
 * readiness, or risk scoring. Those are WF1/backend's job.
 */
class PlatformReporter implements Serializable {

    private final def steps

    PlatformReporter(steps) {
        this.steps = steps
    }

    /**
     * Builds the canonical payload object. Field names/shape are unchanged
     * from the pre-migration pfe-app-test Jenkinsfile -- WF1's Normalize
     * Incident Payload node already consumes this exact structure.
     */
    Map buildPayload(Map args) {
        Map payload = [
            event           : args.event,
            job             : args.job,
            build_number    : args.buildNumber,
            build_url       : args.buildUrl,
            logs_url        : "${args.buildUrl}consoleText",
            branch          : args.branch,
            commit          : args.commit,
            status          : args.buildStatus,
            severity_hint   : args.severityHint,
            duration_ms     : args.durationMs,
            buildStageStatus: args.buildStageStatus,
            technicalFailure: args.technicalFailure,
            pull_request    : args.pullRequest,
            reports         : args.reports,
            tests           : args.tests,
            sonar           : args.sonar,
            docker          : args.docker,
            kubernetes      : args.kubernetes,
            // Additive (QA-BUILD-135-R1 Defect D): factual ZAP execution diagnostics.
            // null when the ZAP stage was never entered (PR build / disabled by policy).
            // Existing consumers reading only reports.available.zap are unaffected.
            zap             : args.zap
        ]
        if (args.event == 'pr_validation') {
            Map correlation = args.prValidation ?: [:]
            payload.putAll([
                validationRequestId: correlation.validationRequestId,
                projectId          : correlation.projectId,
                incidentId         : correlation.incidentId,
                fixRequestId       : correlation.fixRequestId,
                requestId          : correlation.fixRequestId,
                batchId            : correlation.batchId,
                batchKey           : correlation.batchKey,
                attemptCount       : correlation.attemptCount,
                repository         : correlation.repository,
                prNumber           : correlation.prNumber,
                prHeadBranch       : correlation.prHeadBranch,
                expectedPrHeadSha  : correlation.expectedPrHeadSha,
                checkoutSha        : args.checkoutSha,
                jenkinsJob         : correlation.jenkinsJob,
                prValidationJob    : correlation.prValidationJob,
                jenkinsBuildNumber : args.buildNumber,
                jenkinsBuildUrl    : args.buildUrl,
                jenkinsStatus      : args.buildStatus,
                ceTaskId           : args.sonar?.ceTaskId,
                analysisId         : args.sonar?.analysisId
            ])
        }
        return payload
    }

    /** Serializes, writes a copy alongside the Jenkins reports, and POSTs to n8n with retries. Never throws -- degrades currentBuild to UNSTABLE on repeated failure, exactly like the pre-migration behavior. */
    void send(Map payloadObject, String reportBase, def currentBuild) {
        String payload = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(payloadObject))
        steps.writeFile file: 'jenkins-webhook-payload.json', text: payload

        if (reportBase) {
            steps.withEnv(["REPORT_BASE=${reportBase}"]) {
                steps.sh 'mkdir -p "$REPORT_BASE" && cp jenkins-webhook-payload.json "$REPORT_BASE/payload.json" || true'
            }
        }

        boolean notified = false
        for (int attempt = 1; attempt <= 3 && !notified; attempt++) {
            steps.echo "n8n notification: attempt ${attempt}/3"
            int code = steps.withEnv(["N8N_WEBHOOK_URL=${PlatformConfig.N8N_WEBHOOK_URL}"]) {
                steps.sh(
                    returnStatus: true,
                    script: '''
                        curl -sS -o /tmp/n8n_resp.txt -w "%{http_code}" \
                          -X POST "$N8N_WEBHOOK_URL" \
                          -H "Content-Type: application/json" \
                          -H "X-API-Key: $N8N_API_KEY" \
                          --data-binary @jenkins-webhook-payload.json \
                          --max-time 20 > /tmp/n8n_code.txt 2>/tmp/n8n_err.txt
                        CODE=$(cat /tmp/n8n_code.txt)
                        echo "HTTP $CODE"
                        case "$CODE" in 2*) exit 0 ;; *) exit 1 ;; esac
                    '''
                )
            }
            if (code == 0) {
                notified = true
                steps.echo 'n8n notified successfully'
            } else {
                steps.echo "Attempt ${attempt} failed"
                steps.sleep(time: 5, unit: 'SECONDS')
            }
        }
        if (!notified) {
            steps.echo 'ERROR: n8n unreachable after 3 attempts.'
            if (currentBuild.currentResult == 'SUCCESS') {
                currentBuild.result = 'UNSTABLE'
            }
        }
    }
}
