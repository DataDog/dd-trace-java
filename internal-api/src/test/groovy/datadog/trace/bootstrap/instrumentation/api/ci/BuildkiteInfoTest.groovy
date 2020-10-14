package datadog.trace.bootstrap.instrumentation.api.ci


import static datadog.trace.bootstrap.instrumentation.api.ci.BuildkiteInfo.BUILDKITE
import static datadog.trace.bootstrap.instrumentation.api.ci.BuildkiteInfo.BUILDKITE_BUILD_URL
import static datadog.trace.bootstrap.instrumentation.api.ci.BuildkiteInfo.BUILDKITE_GIT_BRANCH
import static datadog.trace.bootstrap.instrumentation.api.ci.BuildkiteInfo.BUILDKITE_GIT_COMMIT
import static datadog.trace.bootstrap.instrumentation.api.ci.BuildkiteInfo.BUILDKITE_GIT_REPOSITORY_URL
import static datadog.trace.bootstrap.instrumentation.api.ci.BuildkiteInfo.BUILDKITE_GIT_TAG
import static datadog.trace.bootstrap.instrumentation.api.ci.BuildkiteInfo.BUILDKITE_JOB_ID
import static datadog.trace.bootstrap.instrumentation.api.ci.BuildkiteInfo.BUILDKITE_PIPELINE_ID
import static datadog.trace.bootstrap.instrumentation.api.ci.BuildkiteInfo.BUILDKITE_PIPELINE_NAME
import static datadog.trace.bootstrap.instrumentation.api.ci.BuildkiteInfo.BUILDKITE_PIPELINE_NUMBER
import static datadog.trace.bootstrap.instrumentation.api.ci.BuildkiteInfo.BUILDKITE_PROVIDER_NAME
import static datadog.trace.bootstrap.instrumentation.api.ci.BuildkiteInfo.BUILDKITE_WORKSPACE_PATH

class BuildkiteInfoTest extends CIProviderInfoTest {

  def "Buildkite info is set properly"() {
    setup:
    environmentVariables.set(BUILDKITE, "true")
    environmentVariables.set(BUILDKITE_PIPELINE_ID, "buildkite-pipeline-id")
    environmentVariables.set(BUILDKITE_PIPELINE_NAME, "buildkite-pipeline-name")
    environmentVariables.set(BUILDKITE_PIPELINE_NUMBER, "buildkite-pipeline-number")
    environmentVariables.set(BUILDKITE_BUILD_URL, "buildkite-build-url")
    environmentVariables.set(BUILDKITE_JOB_ID, "buildkite-job-id")
    environmentVariables.set(BUILDKITE_WORKSPACE_PATH, buildkiteWorkspace)
    environmentVariables.set(BUILDKITE_GIT_REPOSITORY_URL, buildkiteRepoURL)
    environmentVariables.set(BUILDKITE_GIT_COMMIT, "buildkite-git-commit")
    environmentVariables.set(BUILDKITE_GIT_BRANCH, buildkiteBranch)
    environmentVariables.set(BUILDKITE_GIT_TAG, buildkiteTag)

    when:
    def ciInfo = new BuildkiteInfo()

    then:
    ciInfo.ciProviderName == BUILDKITE_PROVIDER_NAME
    ciInfo.ciPipelineId == "buildkite-pipeline-id"
    ciInfo.ciPipelineName == "buildkite-pipeline-name"
    ciInfo.ciPipelineNumber == "buildkite-pipeline-number"
    ciInfo.ciPipelineUrl == "buildkite-build-url"
    ciInfo.ciJobUrl == "buildkite-build-url#buildkite-job-id"
    ciInfo.ciWorkspacePath == ciInfoWorkspace
    ciInfo.gitRepositoryUrl == ciInfoRepository
    ciInfo.gitCommit == "buildkite-git-commit"
    ciInfo.gitBranch == ciInfoBranch
    ciInfo.gitTag == ciInfoTag

    where:
    buildkiteWorkspace | ciInfoWorkspace       | buildkiteRepoURL                             | ciInfoRepository                | buildkiteBranch          | buildkiteTag            | ciInfoBranch  | ciInfoTag
    "/foo/bar"         | "/foo/bar"            | "http://hostname.com/repo.git"               | "http://hostname.com/repo.git"  | "master"                 | null                    | "master"      | null
    "foo/bar"          | "foo/bar"             | "http://hostname.com/repo.git"               | "http://hostname.com/repo.git"  | "master"                 | null                    | "master"      | null
    "/foo/bar~"        | "/foo/bar~"           | "http://hostname.com/repo.git"               | "http://hostname.com/repo.git"  | "master"                 | null                    | "master"      | null
    "/foo/~/bar"       | "/foo/~/bar"          | "http://hostname.com/repo.git"               | "http://hostname.com/repo.git"  | "master"                 | null                    | "master"      | null
    "~/foo/bar"        | userHome + "/foo/bar" | "http://hostname.com/repo.git"               | "http://hostname.com/repo.git"  | "master"                 | null                    | "master"      | null
    "~foo/bar"         | "~foo/bar"            | "http://hostname.com/repo.git"               | "http://hostname.com/repo.git"  | "master"                 | null                    | "master"      | null
    "~"                | userHome              | "http://hostname.com/repo.git"               | "http://hostname.com/repo.git"  | "master"                 | null                    | "master"      | null
    "/foo/bar"         | "/foo/bar"            | "http://user@hostname.com/repo.git"          | "http://hostname.com/repo.git"  | "master"                 | null                    | "master"      | null
    "/foo/bar"         | "/foo/bar"            | "http://user%E2%82%AC@hostname.com/repo.git" | "http://hostname.com/repo.git"  | "master"                 | null                    | "master"      | null
    "/foo/bar"         | "/foo/bar"            | "http://user:pwd@hostname.com/repo.git"      | "http://hostname.com/repo.git"  | "master"                 | null                    | "master"      | null
    "/foo/bar"         | "/foo/bar"            | "git@hostname.com:org/repo.git"              | "git@hostname.com:org/repo.git" | "master"                 | null                    | "master"      | null
    "/foo/bar"         | "/foo/bar"            | "http://hostname.com/repo.git"               | "http://hostname.com/repo.git"  | "origin/master"          | null                    | "master"      | null
    "/foo/bar"         | "/foo/bar"            | "http://hostname.com/repo.git"               | "http://hostname.com/repo.git"  | "refs/heads/master"      | null                    | "master"      | null
    "/foo/bar"         | "/foo/bar"            | "http://hostname.com/repo.git"               | "http://hostname.com/repo.git"  | "refs/heads/feature/one" | null                    | "feature/one" | null
    "/foo/bar"         | "/foo/bar"            | "http://hostname.com/repo.git"               | "http://hostname.com/repo.git"  | null                     | "0.1.0"                 | null          | "0.1.0"
    "/foo/bar"         | "/foo/bar"            | "http://hostname.com/repo.git"               | "http://hostname.com/repo.git"  | null                     | "origin/tags/0.1.0"     | null          | "0.1.0"
    "/foo/bar"         | "/foo/bar"            | "http://hostname.com/repo.git"               | "http://hostname.com/repo.git"  | null                     | "refs/heads/tags/0.1.0" | null          | "0.1.0"
  }
}
