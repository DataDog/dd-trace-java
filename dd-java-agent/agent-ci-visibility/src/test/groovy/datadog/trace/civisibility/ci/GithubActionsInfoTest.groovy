package datadog.trace.civisibility.ci

import datadog.trace.civisibility.ci.env.CiEnvironmentImpl
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class GithubActionsInfoTest extends CITagsProviderTest {

  @TempDir
  Path temporaryFolder

  @Override
  String getProviderName() {
    return GithubActionsInfo.GHACTIONS_PROVIDER_NAME
  }

  @Override
  Map<String, String> buildRemoteGitInfoEmpty() {
    final Map<String, String> map = new HashMap<>()
    map.put(GithubActionsInfo.GHACTIONS, "true")
    map.put(GithubActionsInfo.GHACTIONS_WORKSPACE_PATH, localFSGitWorkspace)
    return map
  }

  @Override
  Map<String, String> buildRemoteGitInfoMismatchLocalGit() {
    final Map<String, String> map = new HashMap<>()
    map.put(GithubActionsInfo.GHACTIONS, "true")
    map.put(GithubActionsInfo.GHACTIONS_WORKSPACE_PATH, localFSGitWorkspace)
    map.put(GithubActionsInfo.GHACTIONS_SHA, "0000000000000000000000000000000000000000")
    return map
  }

  def "test pull request info parsing"() {
    setup:
    def githubEvent = GithubActionsInfoTest.getResource("/ci/github-event.json")
    def githubEventPath = Paths.get(githubEvent.toURI())

    env.set(GithubActionsInfo.GITHUB_BASE_REF, "base-ref")
    env.set(GithubActionsInfo.GITHUB_EVENT_PATH, githubEventPath.toString())

    when:
    def pullRequestInfo = new GithubActionsInfo(new CiEnvironmentImpl(env.getAll())).buildPullRequestInfo()

    then:
    pullRequestInfo.getBaseBranch() == "base-ref"
    pullRequestInfo.getBaseBranchSha() == null
    pullRequestInfo.getBaseBranchHeadSha() == "52e0974c74d41160a03d59ddc73bb9f5adab054b"
    pullRequestInfo.getHeadCommit().getSha() == "df289512a51123083a8e6931dd6f57bb3883d4c4"
    pullRequestInfo.getPullRequestNumber() == "1"
  }

  def "test parseCheckRunIdFromContent extracts ID from content"() {
    setup:
    def info = new GithubActionsInfo(new CiEnvironmentImpl([:]), temporaryFolder, temporaryFolder)

    expect:
    info.parseCheckRunIdFromContent(content) == expected

    where:
    content                                                                    | expected
    // Basic format with decimal
    '{"k":"check_run_id","v":55411116365.0}'                                   | "55411116365"
    // Format with spaces
    '{"k" : "check_run_id" , "v" : 55411116365.0}'                             | "55411116365"
    // Integer format (no decimal)
    '{"k":"check_run_id","v":55411116365}'                                     | "55411116365"
    // Multiple entries - should return the last one
    '{"k":"check_run_id","v":111.0}\n{"k":"check_run_id","v":222.0}'           | "222"
    // Content with surrounding text
    '[2025-01-15 INFO] {"k":"check_run_id","v":12345.0} some other text'      | "12345"
    // No match
    'some random content without check_run_id'                                 | null
    // Empty content
    ''                                                                         | null
    // Malformed JSON (no match)
    '{"k":"check_run_id"}'                                                     | null
  }

  def "test job ID from environment variable takes precedence"() {
    setup:
    env.set(GithubActionsInfo.GHACTIONS, "true")
    env.set(GithubActionsInfo.GHACTIONS_URL, "https://github.com")
    env.set(GithubActionsInfo.GHACTIONS_REPOSITORY, "owner/repo")
    env.set(GithubActionsInfo.GHACTIONS_PIPELINE_ID, "12345")
    env.set(GithubActionsInfo.GHACTIONS_JOB, "build")
    env.set(GithubActionsInfo.GHACTIONS_SHA, "abc123")
    env.set(GithubActionsInfo.GHACTIONS_JOB_CHECK_RUN_ID, "99999")

    // Create a diagnostics directory with a worker file (should be ignored)
    def diagDir = Files.createDirectories(temporaryFolder.resolve("diag"))
    def workerFile = diagDir.resolve("Worker_20250115-123456-utc.log")
    Files.write(workerFile, '{"k":"check_run_id","v":11111.0}'.getBytes())

    when:
    def info = new GithubActionsInfo(new CiEnvironmentImpl(env.getAll()), diagDir, temporaryFolder)
    def ciInfo = info.buildCIInfo()

    then:
    ciInfo.getCiJobId() == "99999"
    ciInfo.getCiJobUrl() == "https://github.com/owner/repo/actions/runs/12345/job/99999"
    ciInfo.getCiJobName() == "build"
  }

  def "test job ID from diagnostics file when env var not set"() {
    setup:
    env.set(GithubActionsInfo.GHACTIONS, "true")
    env.set(GithubActionsInfo.GHACTIONS_URL, "https://github.com")
    env.set(GithubActionsInfo.GHACTIONS_REPOSITORY, "owner/repo")
    env.set(GithubActionsInfo.GHACTIONS_PIPELINE_ID, "12345")
    env.set(GithubActionsInfo.GHACTIONS_JOB, "build")
    env.set(GithubActionsInfo.GHACTIONS_SHA, "abc123")
    // No JOB_CHECK_RUN_ID env var set

    // Create a diagnostics directory with a worker file
    def diagDir = Files.createDirectories(temporaryFolder.resolve("diag"))
    def workerFile = diagDir.resolve("Worker_20250115-123456-utc.log")
    Files.write(workerFile, '[2025-01-15 INFO] {"job":{"d":[{"k":"check_run_id","v":77777.0}]}}'.getBytes())

    when:
    def info = new GithubActionsInfo(new CiEnvironmentImpl(env.getAll()), diagDir, temporaryFolder)
    def ciInfo = info.buildCIInfo()

    then:
    ciInfo.getCiJobId() == "77777"
    ciInfo.getCiJobUrl() == "https://github.com/owner/repo/actions/runs/12345/job/77777"
    ciInfo.getCiJobName() == "build"
  }

  def "test fallback to cached diagnostics directory"() {
    setup:
    env.set(GithubActionsInfo.GHACTIONS, "true")
    env.set(GithubActionsInfo.GHACTIONS_URL, "https://github.com")
    env.set(GithubActionsInfo.GHACTIONS_REPOSITORY, "owner/repo")
    env.set(GithubActionsInfo.GHACTIONS_PIPELINE_ID, "12345")
    env.set(GithubActionsInfo.GHACTIONS_JOB, "build")
    env.set(GithubActionsInfo.GHACTIONS_SHA, "abc123")

    // Create only the cached diagnostics directory (primary doesn't exist)
    def cachedDiagDir = Files.createDirectories(temporaryFolder.resolve("cached_diag"))
    def workerFile = cachedDiagDir.resolve("Worker_20250115-123456-utc.log")
    Files.write(workerFile, '{"k":"check_run_id","v":88888.0}'.getBytes())

    // Use a non-existent path for primary diag dir
    def nonExistentDir = temporaryFolder.resolve("non_existent")

    when:
    def info = new GithubActionsInfo(new CiEnvironmentImpl(env.getAll()), nonExistentDir, cachedDiagDir)
    def ciInfo = info.buildCIInfo()

    then:
    ciInfo.getCiJobId() == "88888"
    ciInfo.getCiJobUrl() == "https://github.com/owner/repo/actions/runs/12345/job/88888"
  }

  def "test fallback to commit-based URL when no job ID available"() {
    setup:
    env.set(GithubActionsInfo.GHACTIONS, "true")
    env.set(GithubActionsInfo.GHACTIONS_URL, "https://github.com")
    env.set(GithubActionsInfo.GHACTIONS_REPOSITORY, "owner/repo")
    env.set(GithubActionsInfo.GHACTIONS_PIPELINE_ID, "12345")
    env.set(GithubActionsInfo.GHACTIONS_JOB, "build")
    env.set(GithubActionsInfo.GHACTIONS_SHA, "abc123def456")
    // No JOB_CHECK_RUN_ID env var and no diagnostics files

    // Use non-existent directories
    def nonExistentDir1 = temporaryFolder.resolve("non_existent1")
    def nonExistentDir2 = temporaryFolder.resolve("non_existent2")

    when:
    def info = new GithubActionsInfo(new CiEnvironmentImpl(env.getAll()), nonExistentDir1, nonExistentDir2)
    def ciInfo = info.buildCIInfo()

    then:
    ciInfo.getCiJobId() == "build"
    ciInfo.getCiJobUrl() == "https://github.com/owner/repo/commit/abc123def456/checks"
    ciInfo.getCiJobName() == "build"
  }

  def "test empty diagnostics directory"() {
    setup:
    env.set(GithubActionsInfo.GHACTIONS, "true")
    env.set(GithubActionsInfo.GHACTIONS_URL, "https://github.com")
    env.set(GithubActionsInfo.GHACTIONS_REPOSITORY, "owner/repo")
    env.set(GithubActionsInfo.GHACTIONS_PIPELINE_ID, "12345")
    env.set(GithubActionsInfo.GHACTIONS_JOB, "build")
    env.set(GithubActionsInfo.GHACTIONS_SHA, "abc123")

    // Create empty diagnostics directory (no worker files)
    def diagDir = Files.createDirectories(temporaryFolder.resolve("diag"))

    when:
    def info = new GithubActionsInfo(new CiEnvironmentImpl(env.getAll()), diagDir, temporaryFolder)
    def ciInfo = info.buildCIInfo()

    then:
    // Falls back to GITHUB_JOB for job ID
    ciInfo.getCiJobId() == "build"
    ciInfo.getCiJobUrl() == "https://github.com/owner/repo/commit/abc123/checks"
  }

  def "test worker file without check_run_id"() {
    setup:
    env.set(GithubActionsInfo.GHACTIONS, "true")
    env.set(GithubActionsInfo.GHACTIONS_URL, "https://github.com")
    env.set(GithubActionsInfo.GHACTIONS_REPOSITORY, "owner/repo")
    env.set(GithubActionsInfo.GHACTIONS_PIPELINE_ID, "12345")
    env.set(GithubActionsInfo.GHACTIONS_JOB, "build")
    env.set(GithubActionsInfo.GHACTIONS_SHA, "abc123")

    // Create diagnostics directory with worker file that lacks check_run_id
    def diagDir = Files.createDirectories(temporaryFolder.resolve("diag"))
    def workerFile = diagDir.resolve("Worker_20250115-123456-utc.log")
    Files.write(workerFile, '[2025-01-15 INFO] Some log content without the expected JSON'.getBytes())

    when:
    def info = new GithubActionsInfo(new CiEnvironmentImpl(env.getAll()), diagDir, temporaryFolder)
    def ciInfo = info.buildCIInfo()

    then:
    ciInfo.getCiJobId() == "build"
    ciInfo.getCiJobUrl() == "https://github.com/owner/repo/commit/abc123/checks"
  }
}
