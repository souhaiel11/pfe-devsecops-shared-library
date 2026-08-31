// Canonical PR-validation checkout policy: discover origin pull requests by
// HEAD, never by a synthetic merge revision. Branch indexing remains unable
// to schedule builds because pfe-devsecops-notrigger-temp.groovy installs the
// NoTriggerBranchProperty independently.
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource
import org.jenkinsci.plugins.github_branch_source.OriginPullRequestDiscoveryTrait
import hudson.model.ParametersDefinitionProperty
import hudson.model.StringParameterDefinition

def job = Jenkins.instance.getItemByFullName('pfe-app-test-multibranch', WorkflowMultiBranchProject.class)
if (!job) {
    println '[pfe-pr-head-discovery] multibranch job not found'
    return
}

boolean changed = false
job.sourcesList.each { branchSource ->
    if (!(branchSource.source instanceof GitHubSCMSource)) return
    def traits = branchSource.source.traits.collect { discoveryTrait ->
        if (discoveryTrait instanceof OriginPullRequestDiscoveryTrait && discoveryTrait.strategyId != 2) {
            changed = true
            return new OriginPullRequestDiscoveryTrait(2)
        }
        return discoveryTrait
    }
    branchSource.source.setTraits(traits)
}
if (changed) job.save()
job.items.findAll { it.name.startsWith('PR-') }.each { prJob ->
    def existing = prJob.getProperty(ParametersDefinitionProperty)
    def definitions = existing?.parameterDefinitions?.findAll { it.name != 'PFE_VALIDATION_CONTEXT' } ?: []
    definitions << new StringParameterDefinition('PFE_VALIDATION_CONTEXT', '', 'Opaque non-secret PR validation correlation supplied by the authenticated platform.')
    prJob.removeProperty(ParametersDefinitionProperty)
    prJob.addProperty(new ParametersDefinitionProperty(definitions))
    prJob.save()
}
println "[pfe-pr-head-discovery] origin PR strategy=${changed ? 'updated to HEAD' : 'already HEAD'}"
if (changed) {
    job.scheduleBuild2(0)
    println '[pfe-pr-head-discovery] branch indexing scheduled; NoTrigger prevents branch builds'
}
