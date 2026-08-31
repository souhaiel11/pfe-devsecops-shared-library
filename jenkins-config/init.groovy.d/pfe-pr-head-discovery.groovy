// Canonical PR-validation checkout policy: discover origin pull requests by
// HEAD, never by a synthetic merge revision. Branch indexing remains unable
// to schedule builds because pfe-devsecops-notrigger-temp.groovy installs the
// NoTriggerBranchProperty independently.
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource
import org.jenkinsci.plugins.github_branch_source.OriginPullRequestDiscoveryTrait

def job = Jenkins.instance.getItemByFullName('pfe-app-test-multibranch', WorkflowMultiBranchProject.class)
if (!job) {
    println '[pfe-pr-head-discovery] multibranch job not found'
    return
}

boolean changed = false
job.sourcesList.each { branchSource ->
    if (!(branchSource.source instanceof GitHubSCMSource)) return
    def traits = branchSource.source.traits.collect { trait ->
        if (trait instanceof OriginPullRequestDiscoveryTrait && trait.strategyId != 2) {
            changed = true
            return new OriginPullRequestDiscoveryTrait(2)
        }
        return trait
    }
    branchSource.source.setTraits(traits)
}
if (changed) job.save()
println "[pfe-pr-head-discovery] origin PR strategy=${changed ? 'updated to HEAD' : 'already HEAD'}"
