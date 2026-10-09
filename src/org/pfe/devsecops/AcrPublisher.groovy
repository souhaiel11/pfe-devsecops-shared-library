package org.pfe.devsecops

/**
 * PIPELINE_GENERIC publication of THIS execution's image to the project's own
 * ACR repository, and registration of its provenance in the platform.
 *
 * Three boundaries this class refuses to blur:
 *
 *   built  != published   a local image is not in a registry
 *   published != deployed  a registry manifest is not a running container
 *   pushed != recorded     a push that happened stays true even when the
 *                          platform then refuses to record it
 *
 * -- Identity discipline --
 * The artifact is addressed by the EXACT reference this run built -- never by
 * listing `docker images`, never by `latest`, never by "most recent". On top of
 * that, the image's own org.opencontainers.image.revision label must equal this
 * run's full 40-character commit before anything is pushed: two builds of the
 * same branch produce the same `name:BUILD_NUMBER` shape, so a stale local
 * image would otherwise be publishable under a fresh build's tag.
 *
 * -- Digest resolution: BACKEND_AGENT --
 * This pipeline does NOT resolve the registry digest. Qualification of the real
 * Jenkins execution context found no `az` binary and therefore no Azure session
 * there, and adding a second Azure authentication model to Jenkins would be a
 * fragile duplicate of one that already works. The backend already owns that
 * path (/artifacts/provenance -> agent /resolve-digest ->
 * `az acr repository show`) and is authoritative over the digest.
 *
 * So no digest is claimed here. The DTO makes it optional precisely so a caller
 * that cannot address the registry authoritatively says nothing rather than
 * guessing. Pushing needs only `docker login` -- no Azure CLI.
 *
 * -- Credential scope --
 * No credential is bound globally. A project with no ACR configuration must
 * build normally without any publication credential existing at all, so each
 * secret is acquired inside the narrowest path that actually uses it:
 *
 *   N8N_INTERNAL_SECRET  the project-config lookup, and the provenance POST
 *   ACR_CREDENTIALS      the push, and only once a registry is known
 *
 * A missing credential degrades to NOT_CONFIGURED for a project that never
 * asked to publish, and fails closed for one that did (requirePublish).
 *
 * No credential value is ever interpolated into a script: every secret is read
 * from the environment inside single-quoted shell, as PlatformReporter does.
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
     * Immutable-by-construction tag: the build number carries ordering, the
     * commit prefix carries identity. Neither alone is enough -- a build number
     * repeats when a job is recreated, a commit repeats across rebuilds of the
     * same commit.
     *
     * The 12-character prefix is a REFERENCE for humans and registries. The
     * authoritative commit in provenance is always the full 40-character sha
     * sent as `commitSha`, which the backend re-derives independently anyway.
     *
     * Deliberately not `latest`, and never a bare build number.
     */
    static String publishTag(String buildNumber, String commitSha) {
        String build = (buildNumber ?: '').trim()
        String sha = (commitSha ?: '').trim()
        if (!(build ==~ /\d{1,24}/)) { return null }
        if (!(sha ==~ /[a-fA-F0-9]{40}/)) { return null }
        return "${build}-${sha.substring(0, 12).toLowerCase()}"
    }

    /**
     * The project's own ACR coordinates. Returns a reason instead of a guess
     * when anything is missing: choosing a registry on the project's behalf is
     * exactly the failure this must avoid.
     */
    static Map resolveTarget(Map project) {
        if (!project) { return [ok: false, reason: 'PROJECT_RECORD_UNAVAILABLE'] }
        def azure = project.azureConfig
        if (!(azure instanceof Map) || !azure) { return [ok: false, reason: 'ACR_NOT_CONFIGURED'] }
        String registry = (azure.registry ?: '').toString().trim()
        String repository = (azure.imageRepository ?: '').toString().trim()
        String projectId = (project.id ?: '').toString().trim()
        if (!registry || !repository) { return [ok: false, reason: 'ACR_NOT_CONFIGURED'] }
        if (!(registry ==~ REGISTRY_PATTERN)) { return [ok: false, reason: 'ACR_REGISTRY_INVALID'] }
        if (!(repository ==~ REPOSITORY_PATTERN)) { return [ok: false, reason: 'ACR_REPOSITORY_INVALID'] }
        if (!(projectId ==~ /[0-9a-fA-F-]{36}/)) { return [ok: false, reason: 'PROJECT_ID_UNAVAILABLE'] }
        return [ok: true, registry: registry, repository: repository, projectId: projectId]
    }

    /**
     * Runs `body` with the platform's internal secret bound, or reports that
     * the credential does not exist on this controller. Never lets a missing
     * publication credential break a project that does not publish.
     */
    private Map withInternalSecret(Closure body) {
        try {
            def out = steps.withCredentials([
                steps.string(credentialsId: PlatformConfig.CRED_INTERNAL_SECRET, variable: 'N8N_INTERNAL_SECRET')
            ]) { body.call() }
            return [ok: true, value: out]
        } catch (err) {
            if (isCredentialMissing(err)) {
                return [ok: false, credentialMissing: true, reason: 'INTERNAL_SECRET_UNAVAILABLE']
            }
            throw err
        }
    }

    /** Same, for the ACR username/password -- bound only once a registry is known. */
    private Map withAcrCredentials(Closure body) {
        try {
            def out = steps.withCredentials([
                steps.usernamePassword(credentialsId: PlatformConfig.CRED_ACR,
                    usernameVariable: 'ACR_USERNAME', passwordVariable: 'ACR_PASSWORD')
            ]) { body.call() }
            return [ok: true, value: out]
        } catch (err) {
            if (isCredentialMissing(err)) {
                return [ok: false, credentialMissing: true, reason: 'ACR_CREDENTIALS_UNAVAILABLE']
            }
            throw err
        }
    }

    /**
     * A credential that does not exist is a configuration state, not a crash.
     * Anything else -- a real push failure, a network error -- keeps
     * propagating: this must never become a catch-all.
     */
    private static boolean isCredentialMissing(Throwable err) {
        String message = String.valueOf(err?.message ?: '')
        String type = err?.getClass()?.getName() ?: ''
        return type.contains('CredentialNotFoundException') ||
               message.contains('Could not find credentials') ||
               message.contains('could not find any unique credentials')
    }

    /** The platform's record for this Jenkins job. Requires the internal secret. */
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
        if (parsed instanceof List) { return parsed ? (parsed[0] as Map) : null }
        return parsed instanceof Map ? (parsed as Map) : null
    }

    /**
     * Verifies the local reference really is the image this run produced, by
     * reading the OCI revision label off that exact reference.
     *
     * `docker image inspect <ref>` addresses one image; it is not a listing and
     * cannot drift to another tag. A missing image, a missing label, or a label
     * disagreeing with this run's commit are all refusals.
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
        if (!actual) { return [ok: false, reason: 'LOCAL_IMAGE_REVISION_LABEL_MISSING'] }
        if (!actual.equalsIgnoreCase(expectedSha)) {
            return [ok: false, reason: 'LOCAL_IMAGE_REVISION_MISMATCH', found: actual]
        }
        return [ok: true]
    }

    /**
     * Tags the verified local image with its unique tag and pushes it.
     * Needs only `docker login` -- no Azure CLI, so no second Azure auth model
     * in Jenkins. Returns the shell status; nonzero is a real failure.
     */
    int pushImage(Map target, String localRef, String tag) {
        String remoteRef = "${target.registry}.azurecr.io/${target.repository}:${tag}"
        return steps.withEnv([
            "PFE_LOCAL_REF=${localRef}",
            "PFE_REMOTE_REF=${remoteRef}",
            "PFE_REGISTRY=${target.registry}"
        ]) {
            steps.sh(
                returnStatus: true,
                script: '''
                    set -e
                    # Credentials arrive only through the environment; they are
                    # never part of this script's text, so xtrace cannot leak them.
                    echo "$ACR_PASSWORD" | docker login "$PFE_REGISTRY.azurecr.io" \
                      --username "$ACR_USERNAME" --password-stdin >/dev/null 2>&1
                    docker tag "$PFE_LOCAL_REF" "$PFE_REMOTE_REF"
                    docker push "$PFE_REMOTE_REF"
                    docker logout "$PFE_REGISTRY.azurecr.io" >/dev/null 2>&1 || true
                '''
            )
        }
    }

    /**
     * Records provenance through the platform's existing CI contract
     * (POST /api/azure-deploy/artifacts/provenance, RegisterArtifactDto).
     *
     * No new endpoint and no new field. No `digest` is claimed: the backend
     * resolves it from the registry through its agent and is authoritative. The
     * DTO has no `registry` field -- the backend reads it from the project --
     * and `provenanceVerified` is a result, never an input.
     */
    int registerProvenance(Map target, int buildNumber, String commitSha, String tag) {
        Map body = [
            projectId  : target.projectId,
            buildNumber: buildNumber,
            commitSha  : commitSha,
            repository : target.repository,
            tag        : tag
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

    // -- Stage 1 of 2: discover, verify, push --
    /**
     * Publishes this run's image. Runs mid-pipeline, right after the Docker
     * build, because the artifact exists then and pushing needs no platform
     * state.
     *
     * Provenance is deliberately NOT registered here: the backend re-derives
     * build identity from the incident WF1 creates out of this build's
     * end-of-pipeline report, so that incident does not exist yet. Registering
     * now would be rejected for every project. See recordProvenance().
     */
    Map publishImage(Map args) {
        String jobName = args.jobName
        String imageName = args.imageName
        String imageTag = args.imageTag
        boolean requirePublish = args.requirePublish == true
        String commitSha = telemetry.checkoutFullSha

        if (!(commitSha ==~ /[a-fA-F0-9]{40}/)) {
            telemetry.docker.push_status = 'FAILED'
            steps.error('ACR_PUBLISH_REVISION_UNAVAILABLE: refusing to publish an artifact whose source commit cannot be proven.')
        }

        // The project-config lookup is the FIRST thing needing the internal
        // secret, and its answer decides whether anything else runs at all.
        Map lookup = withInternalSecret { fetchProject(jobName) }
        if (!lookup.ok) {
            return skipOrFail(requirePublish, lookup.reason,
                'the platform internal secret is not available on this controller, so the project configuration cannot be read')
        }

        Map target = resolveTarget(lookup.value as Map)
        if (!target.ok) {
            return skipOrFail(requirePublish, target.reason,
                'no ACR registry/repository is configured for this project')
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

        // ACR credentials are requested ONLY here -- a registry is known to
        // exist and an identity-verified artifact is ready to go.
        Map pushed = withAcrCredentials { pushImage(target, localRef, tag) }
        if (!pushed.ok) {
            return skipOrFail(requirePublish, pushed.reason,
                'the ACR credential is not available on this controller')
        }
        if (pushed.value != 0) {
            telemetry.docker.push_status = 'FAILED'
            steps.error("ACR_PUSH_FAILED: docker push to ${target.registry}.azurecr.io/${target.repository}:${tag} exited ${pushed.value}.")
        }

        // The push really happened. Recorded before provenance is attempted so
        // a later registration failure can never rewrite this fact.
        telemetry.docker.push_status = 'SUCCESS'
        telemetry.docker.registry = target.registry
        telemetry.docker.repository = target.repository
        telemetry.docker.published_tag = tag
        telemetry.docker.published_reference = "${target.registry}.azurecr.io/${target.repository}:${tag}"
        // Registration is deferred by design, so say so instead of leaving a
        // stale NOT_ATTEMPTED in the report this build is about to send.
        telemetry.docker.provenance_status = 'PENDING'
        // digest stays null here: the backend resolves it authoritatively.
        steps.echo "Image published: ${telemetry.docker.published_reference} (provenance registration deferred until the platform has ingested this build)"
        return [published: true, target: target, tag: tag, commitSha: commitSha, buildNumber: imageTag]
    }

    /** One place for "not configured" vs "you asked for this, so it must work". */
    private Map skipOrFail(boolean requirePublish, String reason, String explanation) {
        if (requirePublish) {
            telemetry.docker.push_status = 'FAILED'
            steps.error("ACR_PUBLISH_REQUIRED_BUT_UNAVAILABLE (${reason}): this project asked for image publication but ${explanation}. Configure it -- nothing is ever chosen on the project's behalf.")
        }
        telemetry.docker.push_status = 'NOT_CONFIGURED'
        steps.echo "ACR publication skipped (${reason}): ${explanation}. No registry or credential is assumed on the project's behalf."
        return [published: false, reason: reason]
    }

    // -- Stage 2 of 2: record provenance, after the platform has the build --
    /**
     * Registers the published artifact's provenance, AFTER this build's report
     * has been sent, because the backend re-derives build identity from the
     * incident WF1 creates from that report. Ingestion is asynchronous, so this
     * retries within a bounded window rather than assuming instant arrival.
     *
     * Fails the build when a published artifact cannot be recorded: under
     * governance an unrecorded artifact is not deployable, so a green build
     * implying otherwise would be a lie. The push itself stays SUCCESS.
     */
    void recordProvenance(Map publication, int attempts, int waitSeconds) {
        if (!publication || publication.published != true) { return }

        int buildNumber = 0
        try { buildNumber = Integer.parseInt(String.valueOf(publication.buildNumber).trim()) }
        catch (ignored) { buildNumber = 0 }
        if (buildNumber < 1) {
            telemetry.docker.provenance_status = 'FAILED'
            steps.error("ACR_PROVENANCE_BUILD_NUMBER_INVALID: '${publication.buildNumber}' is not a usable build number for the provenance contract.")
        }

        int total = Math.max(1, attempts)
        Map attempt = withInternalSecret {
            int last = 1
            for (int i = 1; i <= total; i++) {
                last = registerProvenance(publication.target as Map, buildNumber,
                    publication.commitSha as String, publication.tag as String)
                if (last == 0) { return 0 }
                if (i < total) {
                    steps.echo "Provenance not recorded yet (attempt ${i}/${total}); the platform may not have ingested this build. Retrying."
                    steps.sleep(time: waitSeconds, unit: 'SECONDS')
                }
            }
            return last
        }

        if (!attempt.ok) {
            telemetry.docker.provenance_status = 'FAILED'
            steps.error("ACR_PROVENANCE_SECRET_UNAVAILABLE (${attempt.reason}): the image was published but provenance cannot be recorded without the platform internal secret.")
        }
        if (attempt.value != 0) {
            telemetry.docker.provenance_status = 'FAILED'
            steps.error('ACR_PROVENANCE_REGISTRATION_FAILED: the image was published but the platform refused or could not record its provenance. Failing the build: an unrecorded artifact is not deployable under governance.')
        }
        telemetry.docker.provenance_status = 'SUCCESS'
        steps.echo "Provenance recorded for ${telemetry.docker.published_reference}. Published is not deployed: no deployment state is implied."
    }
}
