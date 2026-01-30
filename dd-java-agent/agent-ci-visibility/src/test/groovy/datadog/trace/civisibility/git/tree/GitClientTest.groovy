package datadog.trace.civisibility.git.tree

import static datadog.trace.civisibility.TestUtils.lines

import datadog.communication.util.IOUtils
import datadog.trace.api.git.CommitInfo
import datadog.trace.civisibility.git.GitObject
import datadog.trace.civisibility.git.pack.V2PackGitInfoExtractor
import datadog.trace.civisibility.telemetry.CiVisibilityMetricCollectorImpl
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import spock.lang.Specification
import spock.lang.TempDir

class GitClientTest extends Specification {

  private static final int GIT_COMMAND_TIMEOUT_MILLIS = 10_000

  private static final String GIT_FOLDER = ".git"

  @TempDir
  private Path tempDir

  def "test buildGitCommand adds safe directory option with absolute path"() {
    given:
    def repoRoot = "/path/to/repo"
    def metricCollector = Stub(CiVisibilityMetricCollectorImpl)
    def gitClient = new ShellGitClient(metricCollector, repoRoot, "25 years ago", 10, GIT_COMMAND_TIMEOUT_MILLIS)

    when:
    def command = gitClient.buildGitCommand("status", "--porcelain")

    then:
    command.length == 5
    command[0] == "git"
    command[1] == "-c"
    command[2] == "safe.directory=/path/to/repo"
    command[3] == "status"
    command[4] == "--porcelain"
  }

  def "test buildGitCommand resolves relative path to absolute"() {
    given:
    def repoRoot = "."
    def metricCollector = Stub(CiVisibilityMetricCollectorImpl)
    def gitClient = new ShellGitClient(metricCollector, repoRoot, "25 years ago", 10, GIT_COMMAND_TIMEOUT_MILLIS)

    when:
    def command = gitClient.buildGitCommand("status")

    then:
    command.length == 4
    command[0] == "git"
    command[1] == "-c"
    // The relative path "." should be resolved to an absolute path
    command[2].startsWith("safe.directory=/")
    !command[2].contains("safe.directory=.")
    command[3] == "status"
  }

  def "test buildGitCommand finds repo root from subdirectory"() {
    given:
    givenGitRepo()
    // Create a subdirectory within the git repo
    def subDir = tempDir.resolve("subdir")
    Files.createDirectories(subDir)
    def metricCollector = Stub(CiVisibilityMetricCollectorImpl)
    def gitClient = new ShellGitClient(metricCollector, subDir.toString(), "25 years ago", 10, GIT_COMMAND_TIMEOUT_MILLIS)

    when:
    def command = gitClient.buildGitCommand("status")

    then:
    command[2] == "safe.directory=" + tempDir.toRealPath().toString()
  }

  def "test is not shallow"() {
    given:
    givenGitRepo()

    when:
    def gitClient = givenGitClient()
    def shallow = gitClient.isShallow()

    then:
    !shallow
  }

  def "test is shallow"() {
    given:
    givenGitRepo("ci/git/shallow/git")

    when:
    def gitClient = givenGitClient()
    def shallow = gitClient.isShallow()

    then:
    shallow
  }

  def "test repo root"() {
    given:
    givenGitRepo()

    when:
    def gitClient = givenGitClient()
    def repoRoot = gitClient.getRepoRoot()

    then:
    repoRoot == tempDir.toRealPath().toString()
  }

  def "test get upstream branch SHA"() {
    given:
    givenGitRepo("ci/git/shallow/git")

    when:
    def gitClient = givenGitClient()
    def upstreamBranch = gitClient.getUpstreamBranchSha()

    then:
    upstreamBranch == "98b944cc44f18bfb78e3021de2999cdcda8efdf6"
  }

  def "test unshallow: sha-#remoteSha"() {
    given:
    givenGitRepo("ci/git/shallow/git")

    when:
    def gitClient = givenGitClient()
    def shallow = gitClient.isShallow()
    def commits = gitClient.getLatestCommits()

    then:
    shallow
    commits.size() == 1

    when:
    gitClient.unshallow(remoteSha)
    shallow = gitClient.isShallow()
    commits = gitClient.getLatestCommits()

    then:
    !shallow
    commits.size() == 10

    where:
    remoteSha << [GitClient.HEAD, null]
  }

  def "test get git folder"() {
    given:
    givenGitRepo()

    when:
    def gitClient = givenGitClient()
    def folder = gitClient.getGitFolder()

    then:
    folder == tempDir.resolve(GIT_FOLDER).toRealPath().toString()
  }

  def "test get remote url"() {
    given:
    givenGitRepo()

    when:
    def gitClient = givenGitClient()
    def remoteUrl = gitClient.getRemoteUrl("origin")

    then:
    remoteUrl == "git@github.com:DataDog/dd-trace-dotnet.git"
  }

  def "test get current branch"() {
    given:
    givenGitRepo()

    when:
    def gitClient = givenGitClient()
    def branch = gitClient.getCurrentBranch()

    then:
    branch == "master"
  }

  def "test get tags"() {
    given:
    givenGitRepo()

    when:
    def gitClient = givenGitClient()
    def tags = gitClient.getTags(GitClient.HEAD)

    then:
    tags.empty
  }

  def "test get sha"() {
    given:
    givenGitRepo()

    when:
    def gitClient = givenGitClient()
    def sha = gitClient.getSha(GitClient.HEAD)

    then:
    sha == "5b6f3a6dab5972d73a56dff737bd08d995255c08"
  }

  def "test get commit info with fetching"() {
    given:
    givenGitRepo("ci/git/shallow/git")

    when:
    def commit = "f4377e97f10c2d58696192b170b2fef2a8464b04"
    def gitClient = givenGitClient()
    def commitInfo = gitClient.getCommitInfo(commit, false)

    then:
    commitInfo == CommitInfo.NOOP

    when:
    commitInfo = gitClient.getCommitInfo(commit, true)

    then:
    commitInfo.sha == commit
    commitInfo.author.name == "sullis"
    commitInfo.author.email == "github@seansullivan.com"
    commitInfo.author.iso8601Date == "2023-05-30T07:07:35-07:00"
    commitInfo.committer.name == "GitHub"
    commitInfo.committer.email == "noreply@github.com"
    commitInfo.committer.iso8601Date == "2023-05-30T07:07:35-07:00"
    commitInfo.fullMessage == "brotli4j 1.12.0 (#1592)"
  }

  def "test get latest commits"() {
    given:
    givenGitRepo()

    when:
    def gitClient = givenGitClient()
    def commits = gitClient.getLatestCommits()

    then:
    commits == [
        "5b6f3a6dab5972d73a56dff737bd08d995255c08",
        "98cd7c8e9cf71e02dc28bd9b13928bee0f85b74c",
        "31ca182c0474f6265e660498c4fbcf775e23bba0",
        "1bd740dd476c38d4b4d706d3ad7cb59cd0b84f7d",
        "2b788c66fc4b58ce6ca7b94fbaf1b94a3ea3a93e",
        "15d5d8e09cbf369f2fa6929c0b0c74b2b0a22193",
        "6aaa4085c10d16b63a910043e35dbd35d2ef7f1c",
        "10599ae3c17d66d642f9f143b1ff3dd236111e2a",
        "5128e6f336cce5a431df68fa0ec42f8c8d0776b1",
        "0c623e9dab4349960930337c936bf9975456e82f"
    ]
  }

  def "test get objects"() {
    given:
    givenGitRepo()

    when:
    def gitClient = givenGitClient()
    def objects = gitClient.getObjects(["98cd7c8e9cf71e02dc28bd9b13928bee0f85b74c"], ["5b6f3a6dab5972d73a56dff737bd08d995255c08"])

    then:
    objects == [
        "5b6f3a6dab5972d73a56dff737bd08d995255c08",
        "c52914110869ff3999bca4837410511f17787e87",
        "cd3407343e846f6707d34b77f38e86345063d0bf",
        "e7fff9f77d05daca86a6bbec334a3304da23278b",
        "a70ad1f15bda97e2f154a0ac6577e11d55ee05d3",
        "3d02ff4958a9ef00b36b1f6e755e3e4e9c92ba5f",
        "5ba3615fbe9ae3dd4338fae6f67f013c212f83b5",
        "fd408d6995f1651a245c227d57529bf8a51ffe45"
    ]
  }

  def "test create pack files"() {
    given:
    givenGitRepo()

    when:
    def gitClient = givenGitClient()
    def packFilesDir = gitClient.createPackFiles([
        "5b6f3a6dab5972d73a56dff737bd08d995255c08",
        "c52914110869ff3999bca4837410511f17787e87",
        "cd3407343e846f6707d34b77f38e86345063d0bf",
        "e7fff9f77d05daca86a6bbec334a3304da23278b",
        "a70ad1f15bda97e2f154a0ac6577e11d55ee05d3",
        "3d02ff4958a9ef00b36b1f6e755e3e4e9c92ba5f",
        "5ba3615fbe9ae3dd4338fae6f67f013c212f83b5",
        "fd408d6995f1651a245c227d57529bf8a51ffe45"
    ])

    then:
    def indexFile = Files.list(packFilesDir).filter { it.toString().endsWith(".idx") }.iterator().next().toFile()
    def packFile = Files.list(packFilesDir).filter { it.toString().endsWith(".pack") }.iterator().next().toFile()

    def extractor = new V2PackGitInfoExtractor()
    def gitPackObject = extractor.extract(indexFile, packFile, "5b6f3a6dab5972d73a56dff737bd08d995255c08")

    !gitPackObject.raisedError()
    gitPackObject.getType() == GitObject.COMMIT_TYPE
  }

  def "test git diff"() {
    given:
    givenGitRepo()

    when:
    def gitClient = givenGitClient()
    def diff = gitClient.getGitDiff("10599ae3c17d66d642f9f143b1ff3dd236111e2a", "6aaa4085c10d16b63a910043e35dbd35d2ef7f1c")

    then:
    diff.linesByRelativePath == [
        "src/Datadog.Trace/Logging/DatadogLogging.cs": lines(26, 32, 91, 95, 159, 160)
    ]
  }

  def "test remove remote prefix"() {
    def gitClient = givenGitClient()

    expect:
    gitClient.removeRemotePrefix(branchName, remoteName) == expected

    where:
    branchName                 | remoteName | expected
    "origin/main"              | "origin"   | "main"
    "upstream/master"          | "upstream" | "master"
    "origin/feature/test"      | "origin"   | "feature/test"
    "main"                     | "origin"   | "main"
    "upstream/main"            | "origin"   | "upstream/main"
    "upstream/bad_origin/main" | "origin"   | "upstream/bad_origin/main"
    ""                         | "origin"   | ""
  }

  def "test branch equals"() {
    def gitClient = givenGitClient()

    expect:
    gitClient.branchesEquals(branchA, branchB, remoteName) == expected

    where:
    branchA        | branchB       | remoteName | expected
    "main"         | "main"        | "origin"   | true
    "upsteam/main" | "main"        | "upsteam"  | true
    "origin/main"  | "origin/main" | "origin"   | true
    "main"         | "master"      | "origin"   | false
    "upsteam/main" | "origin/main" | "origin"   | false
  }

  def "test base like branch match"() {
    def gitClient = givenGitClient()

    expect:
    gitClient.isBaseLikeBranch(branchName, remoteName) == expected

    where:
    branchName            | remoteName | expected
    "main"                | "origin"   | true
    "master"              | "origin"   | true
    "preprod"             | "origin"   | true
    "prod"                | "origin"   | true
    "dev"                 | "origin"   | true
    "development"         | "origin"   | true
    "trunk"               | "origin"   | true
    "release/v1.0"        | "origin"   | true
    "release/2023.1"      | "origin"   | true
    "hotfix/critical"     | "origin"   | true
    "hotfix/bug-123"      | "origin"   | true
    "origin/main"         | "origin"   | true
    "origin/master"       | "origin"   | true
    "upstream/main"       | "upstream" | true
    "feature/test"        | "origin"   | false
    "bugfix/issue-123"    | "origin"   | false
    "update/dependencies" | "origin"   | false
    "my-feature-branch"   | "origin"   | false
    ""                    | "origin"   | false
    "main-backup"         | "origin"   | false
    "maintenance"         | "origin"   | false
  }

  def "test is default branch"() {
    def gitClient = givenGitClient()

    expect:
    gitClient.isDefaultBranch(branch, defaultBranch, remoteName) == expected

    where:
    branch            | defaultBranch | remoteName | expected
    "main"            | "main"        | "origin"   | true
    "master"          | "master"      | "origin"   | true
    "origin/main"     | "main"        | "origin"   | true
    "upstream/master" | "master"      | "upstream" | true
    "feature/test"    | "main"        | "origin"   | false
    "origin/feature"  | "main"        | "origin"   | false
    "main"            | "master"      | "origin"   | false
    "main"            | null          | "origin"   | false
  }

  def "test get remote name"() {
    givenGitRepo(repoPath)
    def gitClient = givenGitClient()

    expect:
    gitClient.getRemoteName() == remoteName

    where:
    repoPath                                 | remoteName
    "ci/git/impacted/ghub_actions_clone/git" | "origin" // get remote from upstream
    "ci/git/impacted/source_repo/git"        | "origin" // no upstream configured for branch
    "ci/git/with_pack/git"                   | "origin" // ambiguous '@{upstream}' argument
  }

  def "test detect default branch"() {
    given:
    givenGitRepo("ci/git/impacted/source_repo/git")
    def gitClient = givenGitClient()

    when:
    def defaultBranch = gitClient.detectDefaultBranch("origin")

    then:
    defaultBranch == "master"
  }

  def "test compute branch metrics"() {
    given:
    givenGitRepo("ci/git/impacted/source_repo/git")
    def gitClient = givenGitClient()

    when:
    def metrics = gitClient.computeBranchMetrics(["origin/master"], "feature")

    then:
    metrics == [new ShellGitClient.BaseBranchMetric("origin/master", 0, 1)]
  }

  def "test sort base branches candidates"() {
    def gitClient = givenGitClient()
    def sortedMetrics = gitClient.sortBaseBranchCandidates(metrics, "main", "origin")
    def sortedBranches = sortedMetrics.collect(m -> m.branch)

    expect:
    sortedBranches == expectedOrder

    where:
    metrics                                                       | expectedOrder
    [
        new ShellGitClient.BaseBranchMetric("main", 10, 2),
        new ShellGitClient.BaseBranchMetric("master", 15, 1),
        new ShellGitClient.BaseBranchMetric("origin/main", 5, 2)] | ["master", "main", "origin/main"]
    [
        new ShellGitClient.BaseBranchMetric("main", 10, 2),
        new ShellGitClient.BaseBranchMetric("master", 15, 2),
        new ShellGitClient.BaseBranchMetric("origin/main", 5, 2)] | ["main", "origin/main", "master"]
    []                                                            | []
  }

  def "test get base branch sha: #testcaseName"() {
    givenGitRepos(["ci/git/impacted/repo_origin", "ci/git/impacted/$repoName"])
    def gitClient = givenGitClient(repoName)

    expect:
    gitClient.getBaseCommitSha(baseBranch, null) == expected

    where:
    testcaseName                                 | repoName             | baseBranch | expected
    "base branch provided"                       | "source_repo"        | "master"   | "15567afb8426f72157c523d49dd49c24d6fe855e"
    "base branch not provided"                   | "source_repo"        | null       | "15567afb8426f72157c523d49dd49c24d6fe855e"
    "fresh clone with remote cloned into master" | "new_clone"          | null       | "15567afb8426f72157c523d49dd49c24d6fe855e"
    "no remote clone"                            | "no_remote"          | null       | null
    "Github Actions style clone"                 | "ghub_actions_clone" | null       | "15567afb8426f72157c523d49dd49c24d6fe855e"
  }

  private void givenGitRepo() {
    givenGitRepo("ci/git/with_pack/git")
  }

  private void givenGitRepo(String resourceName) {
    def gitFolder = Paths.get(getClass().getClassLoader().getResource(resourceName).toURI())
    def tempGitFolder = tempDir.resolve(GIT_FOLDER)
    copyFolder(gitFolder, tempGitFolder)
  }

  private void givenGitRepos(List<String> resourceDirs) {
    def resources = resourceDirs.stream().map(dir -> Paths.get(getClass().getClassLoader().getResource(dir).toURI())).collect(Collectors.toList())
    for (def resource : resources) {
      def gitFolder = resource.resolve("git")
      def destFolder = tempDir.resolve(resource.getFileName())
      if (Files.isDirectory(gitFolder)) {
        // repos with git/ folder
        def tempGitFolder = destFolder.resolve(GIT_FOLDER)
        copyFolder(gitFolder, tempGitFolder)
      } else {
        // dirs with no git/ folder, i.e. a remote
        copyFolder(resource, destFolder)
      }
    }
  }

  private static void copyFolder(Path src, Path dest) {
    Files.createDirectories(dest)
    IOUtils.copyFolder(src, dest)
  }

  private givenGitClient(String tempRelPath) {
    def metricCollector = Stub(CiVisibilityMetricCollectorImpl)
    new ShellGitClient(metricCollector, tempDir.resolve(tempRelPath).toString(), "25 years ago", 10, GIT_COMMAND_TIMEOUT_MILLIS)
  }

  private givenGitClient() {
    givenGitClient("")
  }
}
