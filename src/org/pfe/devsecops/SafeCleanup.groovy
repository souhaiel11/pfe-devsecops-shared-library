package org.pfe.devsecops

/**
 * Workspace-safe cleanup and factual-availability helpers.
 *
 * Rule this class exists to enforce (QA-BUILD-133 hardening, generalized):
 * REPORTING/CLEANUP MUST NEVER MASK THE ORIGINAL PIPELINE FAILURE. Every
 * method here swallows its own exceptions and logs them, but never rethrows
 * over a real build failure, and never touches currentBuild.result upward.
 */
class SafeCleanup implements Serializable {

    private final def steps

    SafeCleanup(steps) {
        this.steps = steps
    }

    /** True only if the workspace-relative path exists and is non-empty. Never throws. */
    boolean reportAvailable(String reportBase, String relativePath) {
        if (!reportBase) return false
        try {
            return steps.sh(script: "test -s '${reportBase}/${relativePath}'", returnStatus: true) == 0
        } catch (ignored) {
            return false
        }
    }

    /** Best-effort informational signal only -- never authoritative, never overrides real findings. */
    boolean grepMarker(String filePath, String pattern) {
        if (!filePath) return false
        try {
            return steps.sh(script: "grep -q '${pattern}' '${filePath}'", returnStatus: true) == 0
        } catch (ignored) {
            return false
        }
    }

    /** Deletes the workspace if one is held. Never lets a cleanup failure surface as the build result. */
    void safeDeleteDir() {
        try {
            steps.deleteDir()
        } catch (cleanupEx) {
            steps.echo "Workspace cleanup skipped (non-fatal): ${cleanupEx.message}"
        }
    }
}
