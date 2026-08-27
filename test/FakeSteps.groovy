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

    def error(String msg) { throw new RuntimeException(msg) }

    def fileExists(String path) { false }
}
