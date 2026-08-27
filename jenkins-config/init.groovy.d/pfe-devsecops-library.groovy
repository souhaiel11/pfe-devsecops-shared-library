// Idempotent registration of the pfe-devsecops Global Trusted Pipeline
// Library. Runs once on controller startup (Jenkins convention for
// init.groovy.d/). Safe to re-run: skips if already registered.
//
// FILL IN before applying: the git URL this repo is hosted at.
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.libs.GlobalLibraries
import org.jenkinsci.plugins.workflow.libs.LibraryConfiguration
import org.jenkinsci.plugins.workflow.libs.SCMSourceRetriever
import jenkins.plugins.git.GitSCMSource

def LIBRARY_NAME = 'pfe-devsecops'
def LIBRARY_REPO_URL = 'https://github.com/souhaiel11/pfe-devsecops-shared-library.git' // <-- update if hosted elsewhere
def DEFAULT_BRANCH = 'main'

def globalLibraries = Jenkins.instance.getExtensionList(GlobalLibraries.class)[0]

if (globalLibraries.libraries.any { it.name == LIBRARY_NAME }) {
    println "[pfe-devsecops-library] '${LIBRARY_NAME}' already registered -- skipping."
    return
}

def scmSource = new GitSCMSource(LIBRARY_REPO_URL)
def retriever = new SCMSourceRetriever(scmSource)
def libConfig = new LibraryConfiguration(LIBRARY_NAME, retriever)
libConfig.defaultVersion = DEFAULT_BRANCH
libConfig.implicit = false            // projects must declare @Library('pfe-devsecops') explicitly
libConfig.allowVersionOverride = true // a project may still pin @Library('pfe-devsecops@<tag>') later if ever needed

globalLibraries.libraries = globalLibraries.libraries + [libConfig]
Jenkins.instance.save()

println "[pfe-devsecops-library] Registered '${LIBRARY_NAME}' -> ${LIBRARY_REPO_URL}@${DEFAULT_BRANCH}"
