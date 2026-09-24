package versioning

import org.gradle.api.Project
import java.io.ByteArrayOutputStream

/**
 * Utility for computing the version string of AeroAC artifacts.
 *
 * Uses Gradle's providers.exec for git invocations so that
 * org.gradle.configuration-cache=true can serialize the task graph.
 * Each helper takes a Project so the git workingDir is anchored to the
 * project root (was ambient JVM cwd before; flaky when invoked from
 * the workspace composite root).
 *
 * Source-tree fallback (no VCS checkout):
 * the project intentionally does NOT create a git repository. When no
 * `.git` metadata is present (or git is unavailable) every git helper
 * degrades to the deterministic sentinels [NO_GIT] / "unknown" instead of
 * emitting git noise, and the version becomes `<base>-nogit[+modifiers]`.
 */
object VersionUtil {

    /** Deterministic version suffix used when no VCS metadata is available. */
    const val NO_GIT = "nogit"

    /**
     * True when the project root carries git metadata (`.git` directory or
     * worktree `.git` file). Never creates a repository.
     */
    private fun hasGitMetadata(project: Project): Boolean =
        project.rootProject.file(".git").exists()

    fun computeVersion(project: Project, baseVersion: String): String {
        if (BuildConfig.release) {
            return baseVersion
        }

        val commitHash = getGitCommitHash(project)
        val branch = getGitBranch(project)

        val modifiers = buildList {
            if (!BuildConfig.shadePE) add("lite")
            if (!BuildConfig.relocate) add("no_relocate")
        }.joinToString("-").takeIf { it.isNotEmpty() }

        return buildString {
            append(baseVersion)
            append("-")
            // NO_GIT is already carried by the commit-hash component; don't repeat it.
            branch?.takeIf { it != NO_GIT }?.let { append("$it-") }
            append(commitHash)
            modifiers?.let { append("+$it") }
        }
    }

    fun getGitCommitHash(project: Project, full: Boolean = false): String {
        if (!hasGitMetadata(project)) return NO_GIT
        return try {
            val args = if (full) listOf("git", "rev-parse", "HEAD")
                       else listOf("git", "rev-parse", "--short", "HEAD")
            val out = project.providers.exec {
                commandLine(args)
                workingDir(project.projectDir)
                isIgnoreExitValue = true
                errorOutput = ByteArrayOutputStream() // keep stderr out of the console
            }.standardOutput.asText.get().trim()
            out.take(minOf(out.length, 7)).ifEmpty { NO_GIT }
        } catch (e: Exception) {
            NO_GIT
        }
    }

    fun getGitBranch(project: Project, raw: Boolean = false): String? {
        if (!hasGitMetadata(project)) return if (raw) NO_GIT else null

        val rawBranch = try {
            project.providers.exec {
                commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
                workingDir(project.projectDir)
                isIgnoreExitValue = true
                errorOutput = ByteArrayOutputStream()
            }.standardOutput.asText.get().trim()
        } catch (e: Exception) {
            return null
        }

        if (rawBranch.isEmpty()) return if (raw) NO_GIT else null
        if (raw) return rawBranch

        val branch = rawBranch
            .replace(Regex("[^a-zA-Z0-9_.-]+"), "_")
            .replace(Regex("_{2,}"), "_")
            .trim(' ', '.', '_', '-')
            .removePrefix("heads_")

        val mainBranch = System.getenv("AEROAC_MAIN_BRANCH") ?: "2.0"

        return when (branch) {
            "lightning" -> null
            "main", mainBranch -> null
            else -> branch
        }
    }

    fun getGitUser(project: Project): String {
        if (!hasGitMetadata(project)) return "unknown"
        return try {
            project.providers.exec {
                commandLine("git", "config", "user.name")
                workingDir(project.projectDir)
                isIgnoreExitValue = true
                errorOutput = ByteArrayOutputStream()
            }.standardOutput.asText.get().trim().ifEmpty { "unknown" }
        } catch (_: Exception) {
            "unknown"
        }
    }

}
