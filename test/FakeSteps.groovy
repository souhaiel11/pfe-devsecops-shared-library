/**
 * Minimal stand-in for the Jenkins pipeline `steps` object, used only by
 * the offline test harness in this directory. Real Jenkins DSL steps
 * (sh, dir, catchError, withEnv, withSonarQubeEnv, ...) are dynamically
 * resolved at pipeline runtime and cannot be unit-tested outside Jenkins;
 * this stub exists so the pure decision logic in each class (skip-tests
 * semantics, NOT_REACHED telemetry, payload shape, file-existence checks)
 * can be verified without a live controller.
 */
class FakeSteps {
    List<String> shScripts = []
    Map<String, String> stdoutFor = [:]
    Map<String, Integer> statusFor = [:]
    List<String> echoed = []

    /** Optional: given the script text, return an exit status, or null to fall back to statusFor/0. Lets tests key off a substring instead of an exact huge-script match. */
    Closure<Integer> statusDecider = null
    /** Optional: given the script text, return stdout, or null to fall back to stdoutFor/''. */
    Closure<String> stdoutDecider = null

    def dir(String path, Closure body) { body.call() }

    def catchError(Map args, Closure body) {
        try { body.call() } catch (ignored) { /* mimics Jenkins catchError swallowing */ }
    }

    def withEnv(List envVars, Closure body) { body.call() }

    def sh(Map args) {
        String script = (args.script ?: '').toString()
        shScripts << script
        if (args.returnStdout) {
            if (stdoutDecider) {
                def decided = stdoutDecider.call(script)
                if (decided != null) return decided
            }
            return stdoutFor.get(script, '')
        }
        if (args.returnStatus) {
            if (statusDecider) {
                def decided = statusDecider.call(script)
                if (decided != null) return decided
            }
            return statusFor.containsKey(script) ? statusFor[script] : 0
        }
        return null
    }

    def sh(String script) {
        shScripts << script
        return null
    }

    def echo(String msg) { echoed << msg }

    /**
     * R51 — credential binding. `missingCredentialIds` simulates a credential
     * that does not exist on the controller, which is exactly what Jenkins does
     * (CredentialNotFoundException). `requestedCredentialIds` records which ones
     * were asked for, so a test can prove an unconfigured project never requests
     * a publication credential at all.
     */
    Set<String> missingCredentialIds = [] as Set
    List<String> requestedCredentialIds = []

    def string(Map args) { [kind: 'string', id: args.credentialsId, variable: args.variable] }
    def usernamePassword(Map args) {
        [kind: 'usernamePassword', id: args.credentialsId,
         usernameVariable: args.usernameVariable, passwordVariable: args.passwordVariable]
    }
    def withCredentials(List bindings, Closure body) {
        bindings.each { b ->
            String id = String.valueOf(b instanceof Map ? b.id : b)
            requestedCredentialIds << id
            if (missingCredentialIds.contains(id)) {
                throw new RuntimeException("Could not find credentials entry with ID '${id}'")
            }
        }
        return body.call()
    }

    /** R50 — captures what the publication stage would have written to disk. */
    Map<String, String> writtenFiles = [:]
    def writeFile(Map args) {
        writtenFiles[(args.file ?: '').toString()] = (args.text ?: '').toString()
        return null
    }

    def sleep(Map args) { return null }

    def error(String msg) { throw new RuntimeException(msg) }

    def fileExists(String path) { false }

    // R80 -- minimal stand-ins for the two new steps BuildRunner's
    // publishSemanticTestEvidence() calls. junitCalls records every
    // invocation so a test can assert it was actually called (and with
    // what args) without needing a real Jenkins junit publisher.
    List<Map> junitCalls = []
    def junit(Map args) { junitCalls << args; return null }

    Map<String, String> fileContents = [:]
    def readFile(String path) {
        if (!fileContents.containsKey(path)) throw new java.io.FileNotFoundException(path)
        return fileContents[path]
    }
}
