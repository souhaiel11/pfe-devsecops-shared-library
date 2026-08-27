// TEMPORARY, REVERSIBLE safety measure for the pfe-devsecops shared-library
// cutover (PFE-BUILD134-CONTROLLED-RELEASE-R1).
//
// Pushing the minimal pfe-app-test/Jenkinsfile must not let the
// pfe-app-test-multibranch job's PeriodicFolderTrigger (runs every ~2 min,
// confirmed in its config.xml: interval=120000ms) auto-schedule a build the
// moment it notices the new commit on `main` (branch indexing already does
// this today -- branches/main had already reached build #7 before this
// script existed, from ordinary past pushes).
//
// This attaches jenkins.branch.NoTriggerBranchProperty to ALL branches of
// pfe-app-test-multibranch: branch discovery/indexing keeps running exactly
// as before (the job list and each branch's known SCM revision still stay
// up to date), but the periodic scan no longer *schedules a build* off of
// that indexing. Manual "Build Now" is unaffected -- only the
// indexing-triggered auto-build path is suppressed. No webhook exists on
// this repo (confirmed via the GitHub API: zero hooks configured), so the
// only auto-build path was this periodic-scan trigger in the first place.
//
// Idempotent (checks current state before changing anything). Does not
// touch pfe-app-test (the plain job) -- that job's own <triggers/> is
// already empty and was never at risk.
//
// TO REVERT once the controlled #134 validation is complete and normal
// push-triggers-CI behavior is desired again: remove this file from
// init.groovy.d and run docs/revert-notrigger.groovy once (included next to
// this file), or manually clear NoTriggerBranchProperty from the job's
// branch source strategy in the Jenkins UI (Multibranch project > Branch
// Sources > Property strategy).
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject
import jenkins.branch.BranchSource
import jenkins.branch.DefaultBranchPropertyStrategy
import jenkins.branch.NoTriggerBranchProperty

def JOB_NAME = 'pfe-app-test-multibranch'

def job = Jenkins.instance.getItemByFullName(JOB_NAME, WorkflowMultiBranchProject.class)
if (!job) {
    println "[pfe-devsecops-notrigger-temp] Job '${JOB_NAME}' not found -- nothing to do."
    return
}

def alreadySuppressed = job.sourcesList.any { BranchSource src ->
    src.strategy instanceof DefaultBranchPropertyStrategy &&
        src.strategy.props.any { it instanceof NoTriggerBranchProperty }
}

if (alreadySuppressed) {
    println "[pfe-devsecops-notrigger-temp] NoTriggerBranchProperty already present on '${JOB_NAME}' -- skipping."
    return
}

job.sourcesList.each { BranchSource src ->
    def noTrigger = new NoTriggerBranchProperty()
    // triggeredBranchesRegex left blank -> matches nothing -> no branch is
    // exempted from suppression. strategy=INDEXING targets exactly our risk
    // (the PeriodicFolderTrigger's periodic-scan-triggered build) -- there
    // are no webhook EVENTS configured on this repo to worry about anyway.
    noTrigger.setTriggeredBranchesRegex('')
    noTrigger.setStrategy(jenkins.branch.NoTriggerMultiBranchQueueDecisionHandler.SuppressionStrategy.INDEXING)
    src.strategy = new DefaultBranchPropertyStrategy([noTrigger] as jenkins.branch.BranchProperty[])
}
job.save()

println "[pfe-devsecops-notrigger-temp] NoTriggerBranchProperty applied to all branches of '${JOB_NAME}'. Branch discovery/indexing still runs; automatic build scheduling from it is suppressed until this is reverted."
