package org.pfe.devsecops

/**
 * PIPELINE_GENERIC publication of THIS execution's image to the project's own
 * ACR repository, followed by provenance registration in the platform.
 *
 * Three boundaries this class refuses to blur:
 *
 *   built  != published   a local image is not in a registry
 *   published != deployed  a registry manifest is not a running container
 *   claimed != resolved    the digest this pipeline reports is a CLAIM; the
 *                          backend independently resolves it from ACR and may
 *                          disagree, which is the point of sending it.
 *
 * Identity discipline. The artifact is addressed by the EXACT reference this
 * run built -- never by listing `docker images`, never by `latest`, never by
 * "most recent". On top of that, the image's own
 * org.opencontainers.image.revision label must equal this run's commit before
 * anything is pushed: two builds of the same branch produce the same
 * `name:BUILD_NUMBER` shape, and a stale local image would otherwise be
 * publishable under a fresh build's tag. The label check is what makes the
 * published artifact provably this execution's.
 *
 * No credential value is ever interpolated into a script. Every secret is read
 * from the environment inside single-quoted shell, exactly as PlatformReporter
 * already does.
 */
class AcrPublisher implements Serializable {

    private final def steps
    private final StageTelemetry telemetry

    AcrPublisher(steps, StageTelemetry telemetry) {
        this.steps = steps
        this.telemetry = telemetry
    }

    /** ACR repositories are lowercase; tags are the registry's own charset. */
    private static final String REPOSITORY_PATTERN = /[a-z0-9][a-z0-9._\/-]{0,254}/
    private static final String TAG_PATTERN = /[A-Za-z0-9_][A-Za-z0-9._-]{0,127}/
    private static final String REGISTRY_PATTERN = /[A-Za-z0-9][A-Za-z0-9.-]{0,252}/

    /**
     * Immutable-by-construction tag for a build: the build number carries
     * ordering, the commit prefix carries identity. Neither alone is enough --
     * a build number is reused across rebuilds of a re-created job, and a
     * commit is reused across rebuilds of the same commit.
     *
     * Deliberately NOT `latest`, and never a bare build number: the whole point
     * is that the published tag names one execution and nothing else.
     */
    static String publishTag(String buildNumber, String commitSha) {
        String build = (buildNumber ?: '').trim()
        String sha = (commitSha ?: '').trim()
        if (!(build ==~ /\d{1,24}/)) {
            return null
        }
        if (!(sha ==~ /[a-fA-F0-9]{40}/)) {
            return null
        }
        return "${build}-${sha.substring(0, 12).toLowerCase()}"
    }

    /**
     * The project's own ACR coordinates, read from the platform record.
     * Returns a reason instead of a guess when anything is missing: picking a
     * registry on the project's behalf is exactly the failure this must avoid.
     */
    static Map resolveTarget(Map project) {
        if (!project) {
            return [ok: false, reason: 'PROJECT_RECORD_UNAVAILABLE']
        }
        def azure = project.azureConfig
        if (!(azure instanceof Map) || !azure) {
            return [ok: false, reason: 'ACR_NOT_CONFIGURED']
        }
        String registry = (azure.registry ?: '').toString().trim()
        String repository = (azure.imageRepository ?: '').toString().trim()
        String projectId = (project.id ?: '').toString().trim()
        if (!registry || !repository) {
            return [ok: false, reason: 'ACR_NOT_CONFIGURED']
        }
        if (!(registry ==~ REGISTRY_PATTERN)) {
            return [ok: false, reason: 'ACR_REGISTRY_INVALID']
        }
        if (!(repository ==~ REPOSITORY_PATTERN)) {
            return [ok: false, reason: 'ACR_REPOSITORY_INVALID']
        }
        if (!(projectId ==~ /[0-9a-fA-F-]{36}/)) {
            return [ok: false, reason: 'PROJECT_ID_UNAVAILABLE']
        }
        return [ok: true, registry: registry, repository: repository, projectId: projectId]
    }

    /**
     * Resolves the platform's record for this Jenkins job. The pipeline knows
     * its job name, never a project UUID, so this is the generic lookup --
     * onboarding a project requires no pipeline change.
     */
    Map fetchProject(String jobName) {
        String response = steps.withEnv([
            "PFE_BACKEND_URL=${PlatformConfig.BACKEND_URL}",
            "PFE_JOB_NAME=${jobName}"
        ]) {
            steps.sh(
                returnStdout: true,
                script: '''
                    set -e
                    curl -sS -f --max-time 20 \
                      -H "X-Internal-Secret: $N8N_INTERNAL_SECRET" \
                      "$PFE_BACKEND_URL/api/projects/internal/by-job/$PFE_JOB_NAME"
                '''
            )
        }
        def parsed = new groovy.json.JsonSlurperClassic().parseText((response ?: '[]').trim())
        // The route answers with a list (exact match, then short-name fallback).
        if (parsed instanceof List) {
            return parsed ? (parsed[0] as Map) : null
        }
        return parsed instanceof Map ? (parsed as Map) : null
    }

    /**
     * Verifies that the local reference really is the image this run produced,
     * by reading the OCI revision label off that exact reference.
     *
     * `docker image inspect <ref>` addresses one image; it is not a listing and
     * cannot drift to another tag. A missing image, a missing label or a label
     * that disagrees with this run's commit are all refusals -- never a
     * best-effort push of whatever happens to be lying around.
     */
    Map verifyLocalImage(String localRef, String expectedSha) {
        if (!localRef || !(expectedSha ==~ /[a-fA-F0-9]{40}/)) {
            return [ok: false, reason: 'LOCAL_IMAGE_IDENTITY_UNVERIFIABLE']
        }
        String label = steps.withEnv(["PFE_LOCAL_REF=${localRef}"]) {
            steps.sh(
                returnStdout: true,
                script: '''
                    set -e
                    docker image inspect "$PFE_LOCAL_REF" \
                      --format '{{index .Config.Labels "org.opencontainers.image.revision"}}'
                '''
            )
        }
        String actual = (label ?: '').trim()
        if (!actual) {
            return [ok: false, reason: 'LOCAL_IMAGE_REVISION_LABEL_MISSING']
        }
        if (!actual.equalsIgnoreCase(expectedSha)) {
            return [ok: false, reason: 'LOCAL_IMAGE_REVISION_MISMATCH', found: actual]
        }
        return [ok: true]
    }

    /**
     * Pushes the verified local image under its unique tag and returns the
     * digest ACR itself reports for it.
     *
     * The digest is read back FROM THE REGISTRY, not from the local daemon,
     * using the SAME read-only command the platform's Azure agent already uses
     * in /resolve-digest (`az acr repository show --image repo:tag`). What the
     * registry stored is the only digest that can be deployed later, and a local
     * RepoDigest can be absent or stale.
     */
    String pushAndResolveDigest(Map target, String localRef, String tag) {
        String remoteRef = "${target.registry}.azurecr.io/${target.repository}:${tag}"
        String digest = steps.withEnv([
            "PFE_LOCAL_REF=${localRef}",
            "PFE_REMOTE_REF=${remoteRef}",
            "PFE_REGISTRY=${target.registry}",
            "PFE_REPOSITORY=${target.repository}",
            "PFE_TAG=${tag}"
        ]) {
            steps.sh(
                returnStdout: true,
                script: '''
                    set -e
                    # Credentials arrive only through the environment; they are
                    # never part of this script's text, so xtrace cannot leak them.
                    echo "$ACR_PASSWORD" | docker login "$PFE_REGISTRY.azurecr.io" \
                      --username "$ACR_USERNAME" --password-stdin >/dev/null 2>&1
                    docker tag "$PFE_LOCAL_REF" "$PFE_REMOTE_REF"
                    docker push "$PFE_REMOTE_REF" >&2
                    # The registry's own answer, not the local daemon's.
                    # Deliberately the SAME read-only command the platform's
                    # Azure agent already uses in /resolve-digest
                    # (az acr repository show --image repo:tag), so the digest
                    # this pipeline claims and the one the backend resolves come
                    # from one command, not two that could diverge.
                    az acr repository show -n "$PFE_REGISTRY" \
                      --image "$PFE_REPOSITORY:$PFE_TAG" --query digest -o tsv
                '''
            )
        }
        String resolved = (digest ?: '').trim()
        return resolved ==~ /sha256:[a-f0-9]{64}/ ? resolved : null
    }

    /**
     * Records provenance through the platform's existing CI contract
     * (POST /api/azure-deploy/artifacts/provenance, RegisterArtifactDto).
     *
     * No new endpoint and no new field: the DTO deliberately carries no
     * `registry` (the backend reads it from the project) and treats `digest` as
     * a claim it re-resolves itself. Sending the claim is what lets the backend
     * contradict a pipeline that pushed something else.
     */
    int registerProvenance(Map target, int buildNumber, String commitSha, String tag, String digest) {
        Map body = [
            projectId  : target.projectId,
            buildNumber: buildNumber,
            commitSha  : commitSha,
            repository : target.repository,
            tag        : tag,
            digest     : digest
        ]
        steps.writeFile file: 'acr-provenance-request.json',
            text: groovy.json.JsonOutput.toJson(body)
        return steps.withEnv(["PFE_BACKEND_URL=${PlatformConfig.BACKEND_URL}"]) {
            steps.sh(
                returnStatus: true,
                script: '''
                    set -e
                    CODE=$(curl -sS -o /tmp/acr_prov_resp.txt -w "%{http_code}" \
                      -X POST "$PFE_BACKEND_URL/api/azure-deploy/artifacts/provenance" \
                      -H "Content-Type: application/json" \
                      -H "X-Internal-Secret: $N8N_INTERNAL_SECRET" \
                      --data-binary @acr-provenance-request.json \
                      --max-time 30)
                    echo "provenance HTTP $CODE"
                    cat /tmp/acr_prov_resp.txt || true
                    case "$CODE" in 2*) exit 0 ;; *) exit 1 ;; esac
                '''
            )
        }
    }

    /**
     * The stage body. Publishing is REQUIRED once a project declares an ACR
     * repository: a configured project whose push or registration fails must
     * fail the build, because the platform would otherwise show a build that
     * looks complete while no deployable artifact exists.
     *
     * A project with no ACR configuration is not an error -- it simply never
     * asked to publish. It records NOT_CONFIGURED, which is a truthful state
     * and never a fabricated success. `requirePublish` turns that same case
     * into a hard failure for a project that explicitly opted in, so nothing
     * silently degrades.
     */
    void publish(Map args) {
        String jobName = args.jobName
        String imageName = args.imageName
        String imageTag = args.imageTag
        boolean requirePublish = args.requirePublish == true
        String commitSha = telemetry.checkoutFullSha

        telemetry.docker.provenance_status = 'NOT_ATTEMPTED'

        if (!(commitSha ==~ /[a-fA-F0-9]{40}/)) {
            telemetry.docker.push_status = 'FAILED'
            steps.error('ACR_PUBLISH_REVISION_UNAVAILABLE: refusing to publish an artifact whose source commit cannot be proven.')
        }

        Map project = null
        try {
            project = fetchProject(jobName)
        } catch (err) {
            telemetry.docker.push_status = 'FAILED'
            steps.error("ACR_PUBLISH_PROJECT_LOOKUP_FAILED: ${err?.message}")
        }

        Map target = resolveTarget(project)
        if (!target.ok) {
            if (requirePublish) {
                telemetry.docker.push_status = 'FAILED'
                steps.error("ACR_PUBLISH_TARGET_UNRESOLVED (${target.reason}): this project asked for image publication but no ACR registry/repository is configured for it. Configure azureConfig.registry and azureConfig.imageRepository on the project -- no registry is ever chosen on its behalf.")
            }
            telemetry.docker.push_status = 'NOT_CONFIGURED'
            steps.echo "ACR publication skipped: ${target.reason}. No registry is chosen on the project's behalf."
            return
        }

        String localRef = "${imageName}:${imageTag}"
        Map identity = verifyLocalImage(localRef, commitSha)
        if (!identity.ok) {
            telemetry.docker.push_status = 'FAILED'
            steps.error("ACR_PUBLISH_IMAGE_IDENTITY_REFUSED (${identity.reason}): ${localRef} is not provably the image built by this execution${identity.found ? " (label=${identity.found}, expected=${commitSha})" : ''}.")
        }

        String tag = publishTag(imageTag, commitSha)
        if (!tag || !(tag ==~ TAG_PATTERN)) {
            telemetry.docker.push_status = 'FAILED'
            steps.error("ACR_PUBLISH_TAG_UNDERIVABLE: cannot build a unique tag from build '${imageTag}' and this run's commit.")
        }

        String digest = null
        try {
            digest = pushAndResolveDigest(target, localRef, tag)
        } catch (err) {
            telemetry.docker.push_status = 'FAILED'
            steps.error("ACR_PUBLISH_FAILED: ${err?.message}")
        }
        if (!digest) {
            telemetry.docker.push_status = 'FAILED'
            steps.error('ACR_DIGEST_UNRESOLVED: the registry did not return a sha256 digest for the pushed tag; refusing to record provenance for an artifact we cannot address immutably.')
        }

        // The push really happened; record it before provenance is attempted so
        // a registration failure never erases a true publication.
        telemetry.docker.push_status = 'SUCCESS'
        telemetry.docker.registry = target.registry
        telemetry.docker.repository = target.repository
        telemetry.docker.published_tag = tag
        telemetry.docker.digest = digest
        // Published is NOT deployed. This pipeline observes no deployment and
        // therefore never writes one.
        telemetry.docker.published_reference = "${target.registry}.azurecr.io/${target.repository}:${tag}"

        int buildNumber = 0
        try {
            buildNumber = Integer.parseInt((imageTag ?: '').trim())
        } catch (ignored) {
            buildNumber = 0
        }
        if (buildNumber < 1) {
            telemetry.docker.provenance_status = 'FAILED'
            steps.error("ACR_PROVENANCE_BUILD_NUMBER_INVALID: '${imageTag}' is not a usable build number for the provenance contract.")
        }

        int code = registerProvenance(target, buildNumber, commitSha, tag, digest)
        if (code != 0) {
            telemetry.docker.provenance_status = 'FAILED'
            steps.error('ACR_PROVENANCE_REGISTRATION_FAILED: the image was published but the platform refused or could not record its provenance. Failing the build: an unrecorded artifact is not deployable under governance.')
        }
        telemetry.docker.provenance_status = 'SUCCESS'
        steps.echo "Image published and provenance recorded: ${telemetry.docker.published_reference} (${digest})"
    }
}
