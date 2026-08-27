// Reverts pfe-devsecops-notrigger-temp.groovy: restores the plain
// DefaultBranchPropertyStrategy with no properties (empty-list), i.e. the
// exact pre-cutover state (see jenkins-config/backups/pre-notrigger-*/
// for the byte-for-byte original config.xml + checksums).
//
// Run this the same way the temp script was applied: docker cp into
// /var/jenkins_home/init.groovy.d/ (or paste into Manage Jenkins > Script
// Console for an immediate, no-restart effect) once normal
// push-triggers-CI behavior on pfe-app-test-multibranch is wanted again.
// Remove pfe-devsecops-notrigger-temp.groovy at the same time so it
// doesn't just re-apply the suppression on the next restart.
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject
import jenkins.branch.BranchSource
import jenkins.branch.DefaultBranchPropertyStrategy

def JOB_NAME = 'pfe-app-test-multibranch'

def job = Jenkins.instance.getItemByFullName(JOB_NAME, WorkflowMultiBranchProject.class)
if (!job) {
    println "[revert-notrigger] Job '${JOB_NAME}' not found -- nothing to do."
    return
}

job.sourcesList.each { BranchSource src ->
    src.strategy = new DefaultBranchPropertyStrategy([] as jenkins.branch.BranchProperty[])
}
job.save()

println "[revert-notrigger] Restored plain DefaultBranchPropertyStrategy (no NoTrigger) on '${JOB_NAME}'."
