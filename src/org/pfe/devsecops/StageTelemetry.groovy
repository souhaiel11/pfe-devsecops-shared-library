package org.pfe.devsecops

/**
 * CPS-safe mutable execution state, shared across stages.
 *
 * Kept as a plain field-holding object (not inside pipeline{}/environment{},
 * which are immutable once the pipeline starts) and mutated from script{}
 * blocks in each stage -- same pattern the original pfe-app-test Jenkinsfile
 * used, just centralized here so every project gets identical, truthful
 * telemetry semantics for free.
 *
 * Every status defaults to a value that is never mistaken for a real result:
 * UNKNOWN (stage attempted, outcome unclear) or NOT_REACHED (stage never
 * ran). Nothing here is ever set to SUCCESS/PASSED speculatively.
 */
class StageTelemetry implements Serializable {

    Map<String, String> buildStageStatus = [:]

    Map docker = [build_status: 'UNKNOWN', image_tag: null, push_status: 'NOT_ATTEMPTED']

    Map sonar = [ceTaskId: null, analysisId: null]

    Map tests = [status: 'UNKNOWN', total: null, failures: null, skipped: null, coverage: null]

    /** Captured directly from `git rev-parse HEAD` right after a successful checkout -- never from env.GIT_COMMIT alone (QA-BUILD-135-R1: env.GIT_COMMIT proved unreliable from this scripted-library flow). */
    String checkoutFullSha = null
    String checkoutShortSha = null

    /**
     * Factual ZAP execution diagnostics (QA-BUILD-135-R1 / Defect A+B+D). Every
     * field here is a fact Jenkins actually observed -- never a governance
     * conclusion (no problemClass/owner/route here; WF1 derives those from
     * technicalCode). Defaults represent "stage not entered yet".
     */
    Map zap = [
        launchAttempted  : false,
        launchSucceeded  : false,
        scanStarted      : false,
        podObserved      : false,
        resultAvailable  : false,
        technicalCode    : null,
        launchErrorType  : null,
        launchExitCode   : null,
        diagnosticMessage: null
    ]

    /**
     * Canonical ZAP stage status vocabulary (Defect B): NOT_RUN (deliberately
     * not entered -- PR build or platform policy), FAILED (entered but no
     * usable result, whatever the reason), COMPLETED (entered, launched,
     * scan produced a usable report). Never derived from bare file
     * existence -- a status-stub file is not a completed scan.
     * NOT_REACHED is handled separately by markUnreachedFromCheckoutFailure(),
     * which overwrites buildStageStatus wholesale before this is ever called.
     */
    String zapStageStatus(boolean stageEntered) {
        if (!stageEntered) return 'NOT_RUN'
        if (!zap.launchAttempted) return 'FAILED'
        if (!zap.launchSucceeded) return 'FAILED'
        if (!zap.resultAvailable) return 'FAILED'
        return 'COMPLETED'
    }

    /** Marks every known stage NOT_REACHED and downstream info blocks NOT_REACHED. Used only when checkout never completed. */
    void markUnreachedFromCheckoutFailure() {
        buildStageStatus = [
            build : 'NOT_REACHED', tests: 'NOT_REACHED', sonar: 'NOT_REACHED',
            trivy : 'NOT_REACHED', owasp: 'NOT_REACHED', zap  : 'NOT_REACHED', docker: 'NOT_REACHED'
        ]
        tests = [status: 'NOT_REACHED', total: null, failures: null, skipped: null, coverage: null]
        docker.build_status = 'NOT_REACHED'
    }
}
