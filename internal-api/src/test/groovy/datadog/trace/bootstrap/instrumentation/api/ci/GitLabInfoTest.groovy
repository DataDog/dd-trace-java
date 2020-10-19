package datadog.trace.bootstrap.instrumentation.api.ci


import static datadog.trace.bootstrap.instrumentation.api.ci.GitLabInfo.GITLAB
import static datadog.trace.bootstrap.instrumentation.api.ci.GitLabInfo.GITLAB_GIT_BRANCH
import static datadog.trace.bootstrap.instrumentation.api.ci.GitLabInfo.GITLAB_GIT_COMMIT
import static datadog.trace.bootstrap.instrumentation.api.ci.GitLabInfo.GITLAB_GIT_REPOSITORY_URL
import static datadog.trace.bootstrap.instrumentation.api.ci.GitLabInfo.GITLAB_GIT_TAG
import static datadog.trace.bootstrap.instrumentation.api.ci.GitLabInfo.GITLAB_JOB_URL
import static datadog.trace.bootstrap.instrumentation.api.ci.GitLabInfo.GITLAB_PIPELINE_ID
import static datadog.trace.bootstrap.instrumentation.api.ci.GitLabInfo.GITLAB_PIPELINE_NAME
import static datadog.trace.bootstrap.instrumentation.api.ci.GitLabInfo.GITLAB_PIPELINE_NUMBER
import static datadog.trace.bootstrap.instrumentation.api.ci.GitLabInfo.GITLAB_PIPELINE_URL
import static datadog.trace.bootstrap.instrumentation.api.ci.GitLabInfo.GITLAB_PROVIDER_NAME
import static datadog.trace.bootstrap.instrumentation.api.ci.GitLabInfo.GITLAB_WORKSPACE_PATH

class GitLabInfoTest extends CIProviderInfoTest {

  def "GitLab info is set properly"() {
    setup:
    environmentVariables.set(GITLAB, "gitlab")
    environmentVariables.set(GITLAB_PIPELINE_ID, "gitlab-pipeline-id")
    environmentVariables.set(GITLAB_PIPELINE_NAME, "gitlab-pipeline-name")
    environmentVariables.set(GITLAB_PIPELINE_NUMBER, "gitlab-pipeline-number")
    environmentVariables.set(GITLAB_PIPELINE_URL, gitlabPipelineUrl)
    environmentVariables.set(GITLAB_JOB_URL, "gitlab-job-url")
    environmentVariables.set(GITLAB_WORKSPACE_PATH, gitlabWorkspace)
    environmentVariables.set(GITLAB_GIT_REPOSITORY_URL, gitlabRepo)
    environmentVariables.set(GITLAB_GIT_COMMIT, "gitlab-git-commit")
    environmentVariables.set(GITLAB_GIT_BRANCH, gitlabBranch)
    environmentVariables.set(GITLAB_GIT_TAG, gitlabTag)

    when:
    def ciInfo = new GitLabInfo()

    then:
    ciInfo.ciProviderName == GITLAB_PROVIDER_NAME
    ciInfo.ciPipelineId == "gitlab-pipeline-id"
    ciInfo.ciPipelineName == "gitlab-pipeline-name"
    ciInfo.ciPipelineNumber == "gitlab-pipeline-number"
    ciInfo.ciPipelineUrl == ciPipelineUrl
    ciInfo.ciJobUrl == "gitlab-job-url"
    ciInfo.ciWorkspacePath == ciInfoWorkspace
    ciInfo.gitRepositoryUrl == ciInfoRepository
    ciInfo.gitCommit == "gitlab-git-commit"
    ciInfo.gitBranch == ciInfoBranch
    ciInfo.gitTag == ciInfoTag

    where:
    gitlabPipelineUrl                   | ciPipelineUrl                     | gitlabWorkspace | ciInfoWorkspace       | gitlabRepo                                   | ciInfoRepository                | gitlabBranch             | gitlabTag               | ciInfoBranch  | ciInfoTag
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | null            | null                  | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | ""              | ""                    | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | "foo/bar"       | "foo/bar"             | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | "/foo/bar~"     | "/foo/bar~"           | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | "/foo/~/bar"    | "/foo/~/bar"          | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | "~/foo/bar"     | userHome + "/foo/bar" | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | "~foo/bar"      | "~foo/bar"            | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | "~"             | userHome              | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | "/foo/bar"      | "/foo/bar"            | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    null                                | null                              | "/foo/bar"      | "/foo/bar"            | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    ""                                  | null                              | "/foo/bar"      | "/foo/bar"            | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | "/foo/bar"      | "/foo/bar"            | "sample"                                     | "sample"                        | "refs/heads/master"      | null                    | "master"      | null
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | "/foo/bar"      | "/foo/bar"            | "sample"                                     | "sample"                        | "refs/heads/feature/one" | null                    | "feature/one" | null
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | "/foo/bar"      | "/foo/bar"            | "sample"                                     | "sample"                        | null                     | "origin/tags/0.1.0"     | null          | "0.1.0"
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | "/foo/bar"      | "/foo/bar"            | "sample"                                     | "sample"                        | null                     | "refs/heads/tags/0.1.0" | null          | "0.1.0"
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | "/foo/bar"      | "/foo/bar"            | "sample"                                     | "sample"                        | null                     | "0.1.0"                 | null          | "0.1.0"
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | "/foo/bar"      | "/foo/bar"            | "http://hostname.com/repo.git"               | "http://hostname.com/repo.git"  | "origin/master"          | null                    | "master"      | null
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | "/foo/bar"      | "/foo/bar"            | "http://user@hostname.com/repo.git"          | "http://hostname.com/repo.git"  | "origin/master"          | null                    | "master"      | null
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | "/foo/bar"      | "/foo/bar"            | "http://user%E2%82%AC@hostname.com/repo.git" | "http://hostname.com/repo.git"  | "origin/master"          | null                    | "master"      | null
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | "/foo/bar"      | "/foo/bar"            | "http://user:pwd@hostname.com/repo.git"      | "http://hostname.com/repo.git"  | "origin/master"          | null                    | "master"      | null
    "https://foo/repo/-/pipelines/1234" | "https://foo/repo/pipelines/1234" | "/foo/bar"      | "/foo/bar"            | "git@hostname.com:org/repo.git"              | "git@hostname.com:org/repo.git" | "origin/master"          | null                    | "master"      | null
  }
}
