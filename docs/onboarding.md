# New project onboarding

Simulated experience for a new Maven project ("orders-service") adopting
the platform:

1. Push application code (already has `pom.xml`).
2. Add a `Dockerfile` if the app needs a container image (optional).
3. Add this Jenkinsfile at the repo root:

   ```groovy
   @Library('pfe-devsecops') _

   devSecOpsPipeline {
       applicationName = 'orders-service'
   }
   ```

4. Register/create the corresponding Jenkins Pipeline job pointing at the
   repo (standard Jenkins job creation, not a DevSecOps-specific step).

That's it. No n8n URL, no Sonar URL, no ZAP/Trivy/OWASP configuration, no
credentials, no Kubernetes namespace, no callback schema. Steps 1-3 involve
zero DevSecOps platform-specific settings; step 4 is ordinary Jenkins job
administration, not something this migration changes.

**Platform-specific settings required from the developer: 0.**

If `orders-service`'s deployed Kubernetes Service is also literally named
`orders-service` and listens on port 8080 (the convention), even the
`applicationName` line becomes optional (falls back to `JOB_NAME`) --
though PFE guidance is to keep it explicit for readability.

## When an override is unavoidable

| Situation | Add this line |
|---|---|
| App lives in `backend/` of a monorepo | `workingDirectory = 'backend'` |
| Dockerfile isn't at repo root | `dockerfile = 'docker/Dockerfile'` |
| No tests exist yet, deliberately | `skipTests = true` |
| Deployed Service name/port differs from the app-name convention | `zapTargetUrl = 'http://my-svc:8080'` |

Every other value in the pipeline — Sonar host, n8n webhook, Trivy image,
ZAP image, Kubernetes namespace, credential IDs, callback schema — is owned
by the platform and cannot be set from a project Jenkinsfile.
