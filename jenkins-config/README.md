# Registering pfe-devsecops on the Jenkins controller

**Status: NOT YET APPLIED.** This directory contains the config to register
the shared library globally so projects only need `@Library('pfe-devsecops') _`
with no repository URL. Applying it requires either a Jenkins UI action or a
container-level change to the running `jenkins` controller (`docker exec` +
restart) — that is a live-system change outside what a coding agent should
do unattended, so it is left here as a reviewed, ready-to-apply artifact
instead of being auto-applied.

This Jenkins instance (`jenkins/jenkins:2.568.1-lts-jdk21`, container name
`jenkins`) already has the `pipeline-groovy-lib` plugin installed (confirmed:
`/var/jenkins_home/plugins/pipeline-groovy-lib.jpi` exists) — no plugin
install is required, only configuration.

## Prerequisite: host this repository somewhere Jenkins can pull it from

Pick one:

- **GitHub** (recommended, matches how `pfe-app-test` is already hosted):
  push this repo to a new `souhaiel11/pfe-devsecops-shared-library` GitHub
  repository, then use its clone URL in `init.groovy.d/pfe-devsecops-library.groovy`.
- **Local bind mount**: mount this directory read-only into the Jenkins
  container and reference it as a `file://` git remote, or as a local-branch
  `LibraryRetriever` (avoids needing network access from Jenkins to GitHub,
  but is a container/compose change of its own).

## Option A (used here): `init.groovy.d` Groovy init script

`init.groovy.d/pfe-devsecops-library.groovy` is idempotent (checks for an
existing library named `pfe-devsecops` before adding one) and registers the
library via `GlobalLibraries`, matching what "Manage Jenkins > System >
Global Trusted Pipeline Libraries" would produce through the UI. Jenkins
runs everything under `init.groovy.d/` once on controller startup.

To apply:

```bash
# 1. Fill in the real git URL at the top of the script below.
# 2. Copy it into the running controller's init.groovy.d:
docker cp jenkins-config/init.groovy.d/pfe-devsecops-library.groovy jenkins:/var/jenkins_home/init.groovy.d/
# 3. Restart the controller so it runs (does NOT trigger any job/build):
docker restart jenkins
# 4. Verify: Manage Jenkins > System > Global Trusted Pipeline Libraries
#    should show "pfe-devsecops".
```

This does not build, trigger, or affect job #133 in any way — it only
changes what `@Library('pfe-devsecops')` resolves to for future runs.

## Option B: JCasC (for later, once the plugin is installed)

`jcasc/jenkins.yaml` is the equivalent declarative form, kept here for when
this controller adopts `configuration-as-code` more broadly (Section 20/21
prefers JCasC when already in use — it is not currently in use on this
controller, so Option A is the pragmatic default for now).
