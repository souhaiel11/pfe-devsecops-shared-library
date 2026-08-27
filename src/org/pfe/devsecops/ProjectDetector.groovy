package org.pfe.devsecops

/**
 * Convention-over-configuration detection of project technology, so the
 * developer never has to declare buildType/dockerfile presence by hand
 * unless their repository layout is genuinely ambiguous.
 */
class ProjectDetector implements Serializable {

    private final def steps

    ProjectDetector(steps) {
        this.steps = steps
    }

    /** Returns 'maven', 'gradle', 'node', or null if nothing recognizable was found. */
    String detectBuildType(String workingDirectory) {
        if (fileExists(workingDirectory, 'pom.xml')) return 'maven'
        if (fileExists(workingDirectory, 'build.gradle') || fileExists(workingDirectory, 'build.gradle.kts')) return 'gradle'
        if (fileExists(workingDirectory, 'package.json')) return 'node'
        return null
    }

    boolean detectDockerfile(String workingDirectory, String explicitPath) {
        if (explicitPath) return fileExists(workingDirectory, explicitPath)
        return fileExists(workingDirectory, 'Dockerfile')
    }

    private boolean fileExists(String workingDirectory, String relativePath) {
        String path = workingDirectory ? "${workingDirectory}/${relativePath}" : relativePath
        return steps.fileExists(path)
    }
}
