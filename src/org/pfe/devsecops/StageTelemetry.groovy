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
