package datadog.gradle.plugin.version

import datadog.gradle.plugin.version.TracerVersionPlugin.TracerVersionExtension
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests [BranchFirstVersioning] against real git repositories, in the branch-first release
 * process where `release/vX.Y.x` is cut from master before `vX.Y.0` is tagged on the release
 * branch.
 */
class BranchFirstVersioningIntegrationTest : VersionPluginsFixture() {
  @TempDir
  lateinit var originDir: File

  @TempDir
  lateinit var gradleProjectDir: File

  private val warnings = mutableListOf<String>()

  @Test
  fun `should build first release candidate at release branch cut`() {
    initMasterWithOrigin()
    exec("git", "switch", "-c", "release/v1.69.x")

    assertVersion("1.69.0-rc.0")
  }

  @Test
  fun `should count release branch commits since cut before line tag`() {
    initMasterWithOrigin()
    exec("git", "switch", "-c", "release/v1.69.x")
    commit("pin-system-tests")

    assertVersion("1.69.0-rc.1")
  }

  @Test
  fun `should not count master commits after release branch cut`() {
    initMasterWithOrigin()
    exec("git", "switch", "-c", "release/v1.69.x")
    commit("pin-system-tests")
    exec("git", "switch", "master")
    repeat(3) { commit("master-after-cut-$it") }
    exec("git", "push", "origin", "master")
    exec("git", "fetch", "origin")
    exec("git", "switch", "release/v1.69.x")

    assertVersion("1.69.0-rc.1")
  }

  @Test
  fun `should build release candidate in GitLab detached HEAD before line tag`() {
    initMasterWithOrigin()
    exec("git", "switch", "-c", "release/v1.69.x")
    commit("pin-system-tests")
    exec("git", "switch", "--detach")

    // The plugin resolves the detached HEAD branch from CI_COMMIT_BRANCH
    assertVersion("1.69.0-rc.1", branch = "release/v1.69.x", env = mapOf("GITLAB_CI" to "true"))
  }

  @Test
  fun `should prefer origin master over stale local master`() {
    initMasterWithOrigin()
    repeat(2) { commit("master-before-cut-$it") }
    exec("git", "push", "origin", "master")
    exec("git", "fetch", "origin")
    exec("git", "switch", "-c", "release/v1.69.x")
    commit("pin-system-tests")
    // The local master branch was not updated before the release branch was cut
    exec("git", "branch", "-f", "master", "v1.68.0")

    assertVersion("1.69.0-rc.1")
  }

  @Test
  fun `should use local master without origin`() {
    initMasterRepo()
    exec("git", "switch", "-c", "release/v1.69.x")
    commit("pin-system-tests")

    assertVersion("1.69.0-rc.1")
  }

  @Test
  fun `should count commits since last tag and warn without master ref`() {
    // The repository default branch is main, and there is no origin
    initRepo()
    exec("git", "tag", "v1.68.0", "-m", "")
    commit("main-commit")
    exec("git", "switch", "-c", "release/v1.69.x")
    commit("pin-system-tests")

    assertVersion("1.69.0-rc.2")
    assertThat(warnings).singleElement().asString().startsWith("Master is missing from the git clone")
  }

  @Test
  fun `should warn about corrupted release branch with master merged into it`() {
    initMasterWithOrigin()
    exec("git", "switch", "-c", "release/v1.69.x")
    commit("pin-system-tests")
    exec("git", "switch", "master")
    repeat(2) { commit("master-after-cut-$it") }
    exec("git", "push", "origin", "master")
    exec("git", "fetch", "origin")
    exec("git", "switch", "release/v1.69.x")
    exec("git", "merge", "--no-edit", "origin/master")
    commit("fix")

    // The first-parent count still starts from the original cut: pin, merge and fix commits
    assertVersion("1.69.0-rc.3")
    assertThat(warnings).singleElement().asString()
      .startsWith("release/v1.69.x looks corrupted, master was merged into it")
      .contains(gitOutput("rev-parse", "origin/master"), gitOutput("rev-parse", "HEAD~3"))
  }

  @Test
  fun `should not warn on release branch with another branch merged into it`() {
    initMasterWithOrigin()
    exec("git", "switch", "-c", "release/v1.69.x")
    commit("pin-system-tests")
    exec("git", "switch", "-c", "fix")
    commit("fix")
    exec("git", "switch", "release/v1.69.x")
    exec("git", "merge", "--no-ff", "--no-edit", "fix")

    // The pin and merge commits
    assertVersion("1.69.0-rc.2")
    assertThat(warnings).isEmpty()
  }

  @Test
  fun `should increment patch after line tag on release branch`() {
    initReleaseBranchWithLineTag()
    commit("backport")

    assertVersion("1.69.1-rc.1")
  }

  @Test
  fun `should increment patch on line tag checked out by a GitLab release branch pipeline`() {
    initReleaseBranchWithLineTag()
    exec("git", "switch", "--detach", "v1.69.0")

    assertVersion("1.69.1-rc.0", branch = "release/v1.69.x", env = mapOf("GITLAB_CI" to "true"))
  }

  @Test
  fun `should use exact version on line tag in GitLab tag pipeline`() {
    initReleaseBranchWithLineTag()
    exec("git", "switch", "--detach", "v1.69.0")

    // The plugin keeps the release branch name when CI_COMMIT_BRANCH is unset
    assertVersion(
      "1.69.0",
      branch = "release/v1.69.x",
      env = mapOf("GITLAB_CI" to "true", "CI_COMMIT_TAG" to "v1.69.0"),
    )
  }

  @Test
  fun `should ignore older line tag on release branch`() {
    initMasterWithOrigin()
    exec("git", "switch", "-c", "release/v1.69.x")
    commit("pin-system-tests")
    exec("git", "tag", "v1.68.1", "-m", "")
    commit("fix")

    assertVersion("1.69.0-rc.2")
  }

  @Test
  fun `should keep last tag version line on unparsable release branch`() {
    initMasterWithOrigin()
    exec("git", "switch", "-c", "release/v1.69.x-hotfix")
    commit("pin-system-tests")

    assertVersion("1.68.1-rc.2")
  }

  @Test
  fun `should mark qualifier and dirtiness on release branch before line tag`() {
    initMasterWithOrigin()
    exec("git", "switch", "-c", "release/v1.69.x")
    commit("pin-system-tests")
    writeFile("pin-system-tests.txt", "An uncommitted modification", append = true)

    assertVersion("1.69.0-ddprof-rc.1-DIRTY", detectDirty = true, qualifier = "ddprof")
  }

  @Test
  fun `should skip newer release branch line on master`() {
    initMasterWithOrigin()
    pushReleaseBranch("release/v1.69.x")
    commit("master-after-cut")

    assertVersion("1.70.0-SNAPSHOT")
  }

  @Test
  fun `should skip newer release branch line on feature branch`() {
    initMasterWithOrigin()
    pushReleaseBranch("release/v1.69.x")
    exec("git", "switch", "-c", "mo/feature")
    commit("feature")

    assertVersion("1.70.0-SNAPSHOT")
  }

  @Test
  fun `should increment minor of last tag on master without newer release branch`() {
    initMasterWithOrigin()
    pushReleaseBranch("release/v1.68.x")
    pushReleaseBranch("release/v1.70.x-test")
    commit("another-master-commit")

    assertVersion("1.69.0-SNAPSHOT")
  }

  @Test
  fun `should use remote-tracking release branches when ls-remote fails`() {
    initMasterWithOrigin()
    pushReleaseBranch("release/v1.69.x")
    exec("git", "fetch", "origin")
    exec("git", "remote", "set-url", "origin", File(originDir, "missing").absolutePath)
    commit("master-after-cut")

    assertVersion("1.70.0-SNAPSHOT")
  }

  @Test
  fun `should increment minor of last tag on master when ls-remote fails without remote-tracking release branch`() {
    initMasterRepo()
    commit("another-master-commit")

    assertVersion("1.69.0-SNAPSHOT")
  }

  @Test
  fun `should mark qualifier and dirtiness on master with newer release branch`() {
    initMasterWithOrigin()
    pushReleaseBranch("release/v1.69.x")
    commit("master-after-cut")
    writeFile("master-after-cut.txt", "An uncommitted modification", append = true)

    assertVersion("1.70.0-ddprof-SNAPSHOT-DIRTY", detectDirty = true, qualifier = "ddprof")
  }

  /** Initializes a repository on main with a first commit. */
  private fun initRepo() {
    writeFile("README.md", "test repository")
    initGitRepo()
  }

  /** Initializes a repository on master with a `v1.68.0` tag followed by a commit. */
  private fun initMasterRepo() {
    initRepo()
    exec("git", "branch", "-m", "master")
    exec("git", "tag", "v1.68.0", "-m", "")
    commit("master-commit")
  }

  /** Same as [initMasterRepo], with master pushed to a local bare `origin` repository. */
  private fun initMasterWithOrigin() {
    initMasterRepo()
    exec(originDir, "git", "init", "--bare")
    exec("git", "remote", "add", "origin", originDir.absolutePath)
    exec("git", "push", "origin", "master")
    exec("git", "fetch", "origin")
  }

  /** Cuts `release/v1.69.x` from master, adds a commit, and tags it `v1.69.0`. */
  private fun initReleaseBranchWithLineTag() {
    initMasterWithOrigin()
    exec("git", "switch", "-c", "release/v1.69.x")
    commit("pin-system-tests")
    exec("git", "tag", "v1.69.0", "-m", "")
    // Master moves on without the release tag
    exec("git", "switch", "master")
    commit("master-after-cut")
    exec("git", "push", "origin", "master")
    exec("git", "switch", "release/v1.69.x")
  }

  /** Pushes a branch at HEAD to origin without fetching it, so only `git ls-remote` sees it. */
  private fun pushReleaseBranch(branch: String) {
    exec("git", "push", "origin", "HEAD:refs/heads/$branch")
  }

  private fun commit(name: String) {
    writeFile("$name.txt", name)
    commitAll(name)
  }

  private fun gitOutput(vararg args: String): String = ProcessBuilder("git", *args)
    .directory(projectDir)
    .start()
    .let { process -> process.inputStream.bufferedReader().readText().trim().also { process.waitFor() } }

  private fun currentBranch(): String = gitOutput("rev-parse", "--abbrev-ref", "HEAD")

  /**
   * Computes the version of the [projectDir] repository like the tracer version plugin does.
   *
   * @param branch the branch the plugin resolves, defaults to the checked out branch
   * @param env the GitLab variables deciding whether the checked out tag is the release version
   */
  private fun assertVersion(
    expectedVersion: String,
    branch: String = currentBranch(),
    env: Map<String, String> = emptyMap(),
    detectDirty: Boolean = false,
    qualifier: String? = null,
  ) {
    // A project outside the repository, so its files never make the repository dirty
    val project = ProjectBuilder.builder().withProjectDir(gradleProjectDir).build()
    val extension = project.objects.newInstance(TracerVersionExtension::class.java).apply {
      this.detectDirty.set(detectDirty)
      versionQualifier.set(qualifier)
    }
    val isReleaseTag = { tag: String -> "GITLAB_CI" !in env || tag == env["CI_COMMIT_TAG"] }

    val version = BranchFirstVersioning(project.providers, projectDir) { warnings += it }
      .version(branch, extension, isReleaseTag)

    assertThat(version).isEqualTo(expectedVersion)
  }
}
