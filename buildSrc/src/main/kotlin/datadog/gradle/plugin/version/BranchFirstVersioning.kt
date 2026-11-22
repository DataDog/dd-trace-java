package datadog.gradle.plugin.version

import com.github.zafarkhaja.semver.Version
import datadog.gradle.plugin.version.TracerVersionPlugin.TracerVersionExtension
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import javax.inject.Inject

/**
 * The `X.Y` version line of a `release/vX.Y.x` branch.
 *
 * In the branch-first release process, `release/vX.Y.x` is cut from master before `vX.Y.0` is
 * tagged, so the branch name, not the last tag, tells which line a build belongs to.
 */
internal data class ReleaseLine(val major: Long, val minor: Long) : Comparable<ReleaseLine> {
  override fun compareTo(other: ReleaseLine): Int = compareValuesBy(this, other, ReleaseLine::major, ReleaseLine::minor)

  /** The `git describe --match` pattern of the tags of this line, like `v1.69.[0-9]*`. */
  fun tagPattern(tagPrefix: String): String = "$tagPrefix$major.$minor.[0-9]*"

  override fun toString(): String = "$major.$minor"

  companion object {
    private val BRANCH_REGEX = Regex("release/v(\\d+)\\.(\\d+)\\.x")

    /** Parses a `release/vX.Y.x` branch name, or returns `null` for any other branch name. */
    fun fromBranch(branch: String): ReleaseLine? = BRANCH_REGEX.matchEntire(branch)?.let {
      val (major, minor) = it.destructured
      ReleaseLine(major.toLong(), minor.toLong())
    }
  }
}

/**
 * Computes the tracer version of a git clone in the branch-first release process.
 *
 * - `release/vX.Y.x` branches build `X.Y.0-rc.N` before the `vX.Y.0` tag, where `N` is the number
 *   of first-parent commits since the branch was cut from master, then `X.Y.(Z+1)-rc.N` after the
 *   `vX.Y.Z` tag, where `N` is the number of commits since that tag.
 * - Other `release/v*` branches build the next patch `-rc.N` version of the last tag.
 * - Master and feature branches build the next minor `-SNAPSHOT` version, one above the newer of
 *   the last tag line and the newest `release/vX.Y.x` branch line.
 * - A build checked out on a release tag builds that exact version.
 *
 * @param warn reports the warnings, such as a release branch with master merged into it
 */
internal class BranchFirstVersioning(
  private val providerFactory: ProviderFactory,
  private val repoWorkingDirectory: File,
  private val warn: (String) -> Unit = { LOGGER.warn(it) },
) {
  /**
   * Computes the version of the clone.
   *
   * @param branch the current branch, already resolved from `CI_COMMIT_BRANCH` on detached HEADs
   * @param extension the version settings: tag prefix, qualifier, dirtiness, snapshot, default version
   * @param isReleaseTag checks whether a build checked out on that exact tag, with its prefix, may
   *     use it as the release version
   */
  fun version(
    branch: String,
    extension: TracerVersionExtension,
    isReleaseTag: (String) -> Boolean,
  ): String {
    val releaseLine = ReleaseLine.fromBranch(branch)
    if (releaseLine != null) {
      return releaseLineVersion(releaseLine, extension, isReleaseTag)
    }
    val isReleaseBranch = branch.startsWith(RELEASE_BRANCH_PREFIX)
    // Use --first-parent only on release branches so they stay anchored to their own version
    // line; feature branches pick up newer tags merged in from master.
    val describeString = describe(extension, firstParent = isReleaseBranch)
    return tagVersion(describeString, extension, isReleaseBranch, isReleaseTag)
  }

  /**
   * Computes the version of a `release/vX.Y.x` branch from the tags of its `X.Y` line only.
   */
  private fun releaseLineVersion(
    releaseLine: ReleaseLine,
    extension: TracerVersionExtension,
    isReleaseTag: (String) -> Boolean,
  ): String {
    val tagPrefix = extension.tagVersionPrefix.get()
    val linePattern = releaseLine.tagPattern(tagPrefix)
    // --always prints the commit hash, with the dirtiness, while the line has no tag
    val describeString = describe(extension, firstParent = true, match = linePattern, always = true)
    if (describeString.startsWith(tagPrefix)) {
      return tagVersion(describeString, extension, isReleaseBranch = true, isReleaseTag)
    }

    LOGGER.info("Git describe output: {} (no {} tag yet)", describeString, linePattern)
    val commitsSinceCut = commitsSinceMasterCut(releaseLine) ?: run {
      val lastTagDescribe = describe(extension, firstParent = true)
      commitsSinceTag(tagRegex(tagPrefix).find(lastTagDescribe)?.groupValues?.get(2).orEmpty())
    }
    return firstReleaseCandidateVersion(
      releaseLine,
      commitsSinceCut,
      extension.versionQualifier.orNull,
      dirty = describeString.endsWith("-dirty"),
      useSnapshot = extension.useSnapshot.get(),
    )
  }

  /**
   * Builds the version from the `git describe` output of a version tag.
   *
   * Builds with commits after the last tag get the next minor `-SNAPSHOT` version, except on
   * release branches, which get the next patch `-rc.N` version, where `N` is the number of commits
   * since the last tag.
   */
  private fun tagVersion(
    describeString: String,
    extension: TracerVersionExtension,
    isReleaseBranch: Boolean,
    isReleaseTag: (String) -> Boolean,
  ): String {
    LOGGER.info("Git describe output: {}", describeString)

    val tagPrefix = extension.tagVersionPrefix.get()
    val matchResult = tagRegex(tagPrefix).find(describeString)
      ?: return extension.defaultVersion.get()

    val (lastTagVersion, describeTrailer) = matchResult.destructured
    val hasLaterCommits = describeTrailer.isNotBlank() || !isReleaseTag("$tagPrefix$lastTagVersion")
    val version = Version.parse(lastTagVersion).let {
      when {
        !hasLaterCommits -> it

        isReleaseBranch -> {
          LOGGER.info("Incrementing patch because release branch")
          it.nextPatchVersion()
        }

        else -> {
          LOGGER.info("Incrementing minor")
          nextMinorVersion(it, newestReleaseLine(releaseBranchRefs()))
        }
      }
    }

    return buildString {
      append(version.toString())

      // Add optional version qualifier (e.g., "-ddprof")
      val qualifier = extension.versionQualifier.orNull
      if (!qualifier.isNullOrBlank()) {
        append("-").append(qualifier)
      }

      if (hasLaterCommits) {
        append(
          when {
            isReleaseBranch -> "-rc.${commitsSinceTag(describeTrailer)}"
            extension.useSnapshot.get() || describeTrailer.isBlank() -> "-SNAPSHOT"
            else -> describeTrailer
          }
        )
      }

      if (describeTrailer.endsWith("-dirty")) {
        append(if (extension.useSnapshot.get()) "-DIRTY" else "-dirty")
      }
    }
  }

  /**
   * Counts the first-parent commits of HEAD since it was cut from master, so later master commits
   * are never counted.
   *
   * Warns when the cut commit, the merge base with master, is not on the first-parent history of
   * HEAD: master was merged into the release branch, which moved the merge base to the merged
   * master commit.
   *
   * @return the commit count, or `null` when there is no master ref or no merge base with it
   */
  private fun commitsSinceMasterCut(releaseLine: ReleaseLine): Int? {
    val existingRefs = git("for-each-ref", "--format=%(refname)", *MASTER_REFS).lines()
    val masterRef = MASTER_REFS.firstOrNull { it in existingRefs }
    if (masterRef == null) {
      warn(
        "Master is missing from the git clone (no ${MASTER_REFS.joinToString(" or ")}): " +
          "cannot find where release/v$releaseLine.x was cut from master, " +
          "so rc.N counts the commits since the last tag instead"
      )
      return null
    }

    // Fails, for instance, on shallow clones without the cut commit
    val cut = git("merge-base", masterRef, "HEAD", ignoreFailure = true)
    if (cut.isBlank()) {
      warn(
        "No merge base between $masterRef and HEAD in the git clone (shallow clone?): " +
          "cannot find where release/v$releaseLine.x was cut from master, " +
          "so rc.N counts the commits since the last tag instead"
      )
      return null
    }

    val count = git("rev-list", "--count", "--first-parent", "$cut..HEAD").toInt()
    // HEAD~count is the cut commit when it is on the first-parent history
    val countedFrom = git("rev-parse", "--verify", "--quiet", "HEAD~$count", ignoreFailure = true)
    if (countedFrom != cut) {
      warn(
        "release/v$releaseLine.x looks corrupted, master was merged into it: its merge base with " +
          "$masterRef, $cut, is the merged master commit, not on its first-parent history. " +
          "rc.$count counts its first-parent commits since " +
          countedFrom.ifBlank { "its root" } + " instead."
      )
    }
    return count
  }

  /**
   * Lists the release branches of origin, or the local remote-tracking ones when origin can't be
   * reached or has none.
   */
  private fun releaseBranchRefs(): List<String> {
    val refs = git("ls-remote", "--heads", ORIGIN, "refs/heads/release/v*", ignoreFailure = true)
      .ifBlank { git("for-each-ref", "--format=%(refname)", "refs/remotes/$ORIGIN/release/") }
    return refs.lines().filter { it.isNotBlank() }
  }

  private fun describe(
    extension: TracerVersionExtension,
    firstParent: Boolean,
    match: String = "${extension.tagVersionPrefix.get()}[0-9].[0-9]*.[0-9]*",
    always: Boolean = false,
  ): String {
    val args = mutableListOf("describe", "--abbrev=8", "--tags", "--match=$match")
    if (firstParent) {
      args += "--first-parent"
    }
    if (always) {
      args += "--always"
    }
    if (extension.detectDirty.getOrElse(false)) {
      args += "--dirty"
    }
    return git(*args.toTypedArray())
  }

  private fun git(vararg args: String, ignoreFailure: Boolean = false): String = providerFactory.of(BranchFirstGitValueSource::class.java) {
    parameters {
      gitCommand.add("git")
      gitCommand.addAll(*args)
      this.ignoreFailure.set(ignoreFailure)
      workingDirectory.set(repoWorkingDirectory)
    }
  }.get()

  /** Pure version computations of the branch-first release process. */
  companion object {
    private val LOGGER = Logging.getLogger(BranchFirstVersioning::class.java)

    private const val ORIGIN = "origin"

    /** Branches starting with this prefix build the next patch `-rc.N` version. */
    private const val RELEASE_BRANCH_PREFIX = "release/v"

    /** Master refs, by preference: the remote one is fresher than a possibly stale local branch. */
    private val MASTER_REFS = arrayOf("refs/remotes/$ORIGIN/master", "refs/heads/master")

    /** Matches the `git describe` output of a version tag: the tag version, then the trailer. */
    private fun tagRegex(tagPrefix: String) = Regex("$tagPrefix(\\d+\\.\\d+\\.\\d+)(.*)")

    private val COMMITS_SINCE_TAG_REGEX = Regex("^-(\\d+)-g[0-9a-f]+")

    /**
     * Finds the newest release line among release branch refs.
     *
     * @param refs lines of `git ls-remote` (`<sha>\trefs/heads/release/vX.Y.x`) or
     *     `git for-each-ref` (`refs/remotes/origin/release/vX.Y.x`) output; other names are ignored
     */
    fun newestReleaseLine(refs: List<String>): ReleaseLine? = refs.asSequence()
      .map { it.substringAfterLast('\t').trim().removePrefix("refs/heads/").removePrefix("refs/remotes/origin/") }
      .mapNotNull(ReleaseLine::fromBranch)
      .maxOrNull()

    /**
     * Returns the next minor version of master and feature branches: one above the newer of the
     * last tag line and the newest release branch line, since a release branch may exist before
     * its `vX.Y.0` tag.
     */
    fun nextMinorVersion(lastTagVersion: Version, newestReleaseLine: ReleaseLine?): Version {
      val lastTagLine = ReleaseLine(lastTagVersion.majorVersion(), lastTagVersion.minorVersion())
      return if (newestReleaseLine != null && newestReleaseLine > lastTagLine) {
        Version.of(newestReleaseLine.major, newestReleaseLine.minor + 1, 0)
      } else {
        lastTagVersion.nextMinorVersion()
      }
    }

    /**
     * Builds the `X.Y.0-rc.N` version of a release branch that has no tag of its line yet, in the
     * same format as tagged release branches: `X.Y.0[-qualifier]-rc.N[-DIRTY|-dirty]`.
     *
     * @param commitsSinceCut the number of commits added to the release branch since it was cut
     */
    fun firstReleaseCandidateVersion(
      line: ReleaseLine,
      commitsSinceCut: Int,
      qualifier: String?,
      dirty: Boolean,
      useSnapshot: Boolean,
    ): String = buildString {
      append(Version.of(line.major, line.minor, 0).toString())
      if (!qualifier.isNullOrBlank()) {
        append("-").append(qualifier)
      }
      append("-rc.").append(commitsSinceCut)
      if (dirty) {
        append(if (useSnapshot) "-DIRTY" else "-dirty")
      }
    }

    /**
     * Reads the number of commits since the last tag from the `git describe` trailer, like
     * `-3-g1234abcd` or `-3-g1234abcd-dirty`. The trailer is blank, or only `-dirty`, on the
     * tagged commit itself.
     */
    private fun commitsSinceTag(describeTrailer: String): Int = COMMITS_SINCE_TAG_REGEX.find(describeTrailer)?.groupValues?.get(1)?.toInt() ?: 0
  }
}

/**
 * Runs a git command of [BranchFirstVersioning] and returns its standard output.
 *
 * Returns a blank output outside a git repository, when `git describe` finds no tag, or, with
 * [Parameters.ignoreFailure], when the command fails.
 */
internal abstract class BranchFirstGitValueSource @Inject constructor(
  private val execOperations: ExecOperations,
) : ValueSource<String, BranchFirstGitValueSource.Parameters> {
  override fun obtain(): String {
    val commands = parameters.gitCommand.get()
    val standardOutput = ByteArrayOutputStream()
    val errorOutput = ByteArrayOutputStream()
    val result = try {
      execOperations.exec {
        commandLine(commands)
        workingDir(parameters.workingDirectory.get())
        this.standardOutput = standardOutput
        this.errorOutput = errorOutput
        isIgnoreExitValue = true
      }
    } catch (e: Exception) {
      throw GradleException("Failed to run: ${commands.joinToString(" ")}", e)
    }

    val charset = Charset.defaultCharset().name()
    val output = standardOutput.toString(charset).trim()
    val error = errorOutput.toString(charset).trim()
    return when {
      result.exitValue == 0 -> output

      parameters.ignoreFailure.getOrElse(false) -> ""

      // Behaves as if not a git repository
      result.exitValue == 128 &&
        (error.startsWith("fatal: not a git repository") || error.startsWith("fatal: No names found")) -> ""

      else -> throw GradleException(
        """
          Failed to run: ${commands.joinToString(" ")}
            (exit code: ${result.exitValue})
          Output:
          $output
          $error
        """.trimIndent()
      )
    }
  }

  interface Parameters : ValueSourceParameters {
    val workingDirectory: DirectoryProperty
    val gitCommand: ListProperty<String>

    /** Returns a blank output instead of failing when the command exits with a non-zero code. */
    val ignoreFailure: Property<Boolean>
  }
}
