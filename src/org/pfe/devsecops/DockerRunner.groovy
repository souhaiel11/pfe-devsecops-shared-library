package org.pfe.devsecops

/** PIPELINE_GENERIC Docker image build. No push -- this platform never pushes from Jenkins. */
class DockerRunner implements Serializable {

    private final def steps
    private final StageTelemetry telemetry

    DockerRunner(steps, StageTelemetry telemetry) {
        this.steps = steps
        this.telemetry = telemetry
    }

    void build(String imageName, String imageTag, String dockerfilePath, String workingDirectory) {
        telemetry.docker.image_tag = "${imageName}:${imageTag}"
        telemetry.docker.build_status = 'FAILED'

        steps.dir(workingDirectory ?: '.') {
            steps.catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                // Phase 2A.9 -- OCI provenance label. telemetry.checkoutFullSha is
                // the SAME already-hardened producer org.opencontainers.image.revision
                // depends on (QA-BUILD-135-R1's git-rev-parse capture, never
                // env.GIT_COMMIT) -- reused here, not re-derived. Fail CLOSED before
                // any artifact exists: a build without a provable revision must
                // never produce a provenance-qualified image at all.
                if (!(telemetry.checkoutFullSha ==~ /[a-fA-F0-9]{40}/)) {
                    steps.error("OCI_REVISION_UNAVAILABLE: telemetry.checkoutFullSha is missing or not a full 40-character git SHA -- refusing to build a provenance-qualified image.")
                }
                String fileArg = dockerfilePath ? "-f '${dockerfilePath}'" : ''
                // org.opencontainers.image.source is best-effort (telemetry.checkoutSourceUrl
                // can legitimately be null) -- omitted entirely rather than guessed when
                // absent. Charset-gated (same discipline as checkoutFullSha's hex-only
                // gate just above) BEFORE GString-interpolating it into the sh script
                // below: this value isn't user input, but it's never treated as safe to
                // interpolate on trust alone -- anything outside a plain URL charset is
                // treated as "not reliably available" and the label is omitted, never
                // passed through.
                String safeSourceUrl = (telemetry.checkoutSourceUrl ==~ /[A-Za-z0-9:\/._@-]{1,500}/) ? telemetry.checkoutSourceUrl : null
                String sourceLabelArg = safeSourceUrl ? "--label org.opencontainers.image.source=\"${safeSourceUrl}\" " : ''
                steps.sh """
                    set -e
                    docker build ${fileArg} \\
                        --label org.opencontainers.image.revision="${telemetry.checkoutFullSha}" \\
                        ${sourceLabelArg}--label org.opencontainers.image.created="\$(date -u +%Y-%m-%dT%H:%M:%SZ)" \\
                        -t "${imageName}:${imageTag}" .
                    docker tag "${imageName}:${imageTag}" "${imageName}:latest"

                    echo "=== Docker image created ==="
                    docker images | grep "${imageName}" || true
                """
                telemetry.docker.build_status = 'SUCCESS'
                telemetry.buildStageStatus['docker'] = 'SUCCESS'
            }
            // No `docker push` in this pipeline -- honest value, never a fabricated
            // SUCCESS/UNKNOWN for a step that was never attempted.
            telemetry.docker.push_status = 'NOT_ATTEMPTED'
        }
    }
}
