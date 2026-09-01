package org.pfe.devsecops

/**
 * PLATFORM_INFRASTRUCTURE defaults for the pfe-devsecops shared library.
 *
 * These are the values a project developer must never have to know or type:
 * internal service URLs, credential IDs, namespaces, tool images. They are
 * consumed with lowest priority in the config hierarchy (Jenkins Credentials
 * > Jenkins global config/JCasC > these Shared Library defaults > explicit
 * per-project override in the Jenkinsfile). Until this Jenkins installation
 * has JCasC/global env vars for these, they live here as the single owned
 * copy -- no project Jenkinsfile may repeat them.
 */
class PlatformConfig implements Serializable {

    // ---- Jenkins Credentials IDs (never values) ----
    static final String CRED_SONAR_TOKEN = 'SONAR_TOKEN'
    static final String CRED_N8N_API_KEY = 'N8N_API_KEY'
    static final String CRED_NVD_API_KEY = 'NVD_API_KEY'

    // ---- SonarQube ----
    static final String SONAR_ENV_NAME = 'sq1'          // withSonarQubeEnv() name configured in Jenkins
    static final String SONAR_HOST_URL = 'http://sonarqube:9000'
    // R45 -- explicit, never inferred from missing fields. This local instance
    // is Community Edition (H2, no license): sonar.pullrequest.* is rejected
    // outright ("Developer Edition or above is required"), proven on real
    // PR-24 build #2. COMMUNITY_EXACT_SHA runs a standard analysis against a
    // dedicated per-PR project key instead -- never native PR analysis, never
    // claimed as such. Flip to DEVELOPER_NATIVE_PR only after a real Developer
    // Edition migration (see the R44 stop report: no supported H2 migration
    // path exists today).
    static final String SONAR_ANALYSIS_MODE = 'COMMUNITY_EXACT_SHA' // or 'DEVELOPER_NATIVE_PR'

    // ---- Platform / n8n / backend ----
    static final String BACKEND_URL     = 'http://pfe-backend:3001'
    static final String N8N_WEBHOOK_URL = 'http://n8n:5678/webhook/jenkins-event'

    // ---- Kubernetes / ZAP ----
    static final String K8S_NAMESPACE = 'pfe-devsecops'
    static final String KUBECONFIG_PATH = '/var/jenkins_home/.kube/config'
    static final String ZAP_IMAGE = 'zaproxy/zap-stable:latest'

    // ---- Report storage ----
    static final String JENKINS_REPORT_ROOT = '/shared/reports'
    static final String N8N_REPORT_ROOT = '/home/node/.n8n-files/reports'

    // ---- Mandatory scanner policy (not a project choice) ----
    static final boolean SONAR_ENABLED = true
    static final boolean TRIVY_ENABLED = true
    static final boolean OWASP_ENABLED = true
    static final boolean ZAP_ENABLED_ON_BRANCH_BUILDS = true // never on PR builds

    // ---- Default governance-neutral gate (Jenkins never enforces by default) ----
    static final boolean DEFAULT_JENKINS_HARD_GATE = false
    static final String DEFAULT_CVSS_FAIL_THRESHOLD = '9.0'

    // ---- Timeouts (minutes) ----
    static final int TIMEOUT_PIPELINE_HOURS = 3
    static final int TIMEOUT_TRIVY_MINUTES = 35
    static final int TIMEOUT_OWASP_MINUTES = 40
    static final int TIMEOUT_ZAP_MINUTES = 40
    static final int TIMEOUT_POST_REPORT_MINUTES = 2

    static final String OWASP_DC_VERSION = '12.2.2'
    static final String OWASP_DC_DATA_DIR = '/var/jenkins_home/dependency-check-data-v12'
}
