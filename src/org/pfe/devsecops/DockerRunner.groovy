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
                String fileArg = dockerfilePath ? "-f '${dockerfilePath}'" : ''
                steps.sh """
                    set -e
                    docker build ${fileArg} -t "${imageName}:${imageTag}" .
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
