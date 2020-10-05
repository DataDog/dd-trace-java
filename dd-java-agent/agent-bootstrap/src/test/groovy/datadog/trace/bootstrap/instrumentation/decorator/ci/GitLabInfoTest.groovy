package datadog.trace.bootstrap.instrumentation.decorator.ci


import static datadog.trace.bootstrap.instrumentation.decorator.ci.GitLabInfo.GITLAB
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GitLabInfo.GITLAB_GIT_BRANCH
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GitLabInfo.GITLAB_GIT_COMMIT
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GitLabInfo.GITLAB_GIT_REPOSITORY_URL
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GitLabInfo.GITLAB_GIT_TAG
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GitLabInfo.GITLAB_JOB_URL
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GitLabInfo.GITLAB_PIPELINE_ID
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GitLabInfo.GITLAB_PIPELINE_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GitLabInfo.GITLAB_PIPELINE_NUMBER
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GitLabInfo.GITLAB_PIPELINE_URL
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GitLabInfo.GITLAB_PROVIDER_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GitLabInfo.GITLAB_WORKSPACE_PATH

class GitLabInfoTest extends CIProviderInfoTest {

  def "GitLab info is set properly"() {
    setup:
    environmentVariables.set(GITLAB, "gitlab")
    environmentVariables.set(GITLAB_PIPELINE_ID, "gitlab-pipeline-id")
    environmentVariables.set(GITLAB_PIPELINE_NAME, "gitlab-pipeline-name")
    environmentVariables.set(GITLAB_PIPELINE_NUMBER, "gitlab-pipeline-number")
    environmentVariables.set(GITLAB_PIPELINE_URL, "gitlab-pipeline-url")
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
    ciInfo.ciPipelineUrl == "gitlab-pipeline-url"
    ciInfo.ciJobUrl == "gitlab-job-url"
    ciInfo.ciWorkspacePath == ciInfoWorkspace
    ciInfo.gitRepositoryUrl == ciInfoRepository
    ciInfo.gitCommit == "gitlab-git-commit"
    ciInfo.gitBranch == ciInfoBranch
    ciInfo.gitTag == ciInfoTag

    where:
    gitlabWorkspace | ciInfoWorkspace       | gitlabRepo                                   | ciInfoRepository                | gitlabBranch             | gitlabTag               | ciInfoBranch  | ciInfoTag
    null            | null                  | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    ""              | ""                    | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "foo/bar"       | "foo/bar"             | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "/foo/bar~"     | "/foo/bar~"           | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "/foo/~/bar"    | "/foo/~/bar"          | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "~/foo/bar"     | userHome + "/foo/bar" | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "~foo/bar"      | "~foo/bar"            | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "~"             | userHome              | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "/foo/bar"      | "/foo/bar"            | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "/foo/bar"      | "/foo/bar"            | "sample"                                     | "sample"                        | "refs/heads/master"      | null                    | "master"      | null
    "/foo/bar"      | "/foo/bar"            | "sample"                                     | "sample"                        | "refs/heads/feature/one" | null                    | "feature/one" | null
    "/foo/bar"      | "/foo/bar"            | "sample"                                     | "sample"                        | null                     | "origin/tags/0.1.0"     | null          | "0.1.0"
    "/foo/bar"      | "/foo/bar"            | "sample"                                     | "sample"                        | null                     | "refs/heads/tags/0.1.0" | null          | "0.1.0"
    "/foo/bar"      | "/foo/bar"            | "sample"                                     | "sample"                        | null                     | "0.1.0"                 | null          | "0.1.0"
    "/foo/bar"      | "/foo/bar"            | "http://hostname.com/repo.git"               | "http://hostname.com/repo.git"  | "origin/master"          | null                    | "master"      | null
    "/foo/bar"      | "/foo/bar"            | "http://user@hostname.com/repo.git"          | "http://hostname.com/repo.git"  | "origin/master"          | null                    | "master"      | null
    "/foo/bar"      | "/foo/bar"            | "http://user%E2%82%AC@hostname.com/repo.git" | "http://hostname.com/repo.git"  | "origin/master"          | null                    | "master"      | null
    "/foo/bar"      | "/foo/bar"            | "http://user:pwd@hostname.com/repo.git"      | "http://hostname.com/repo.git"  | "origin/master"          | null                    | "master"      | null
    "/foo/bar"      | "/foo/bar"            | "git@hostname.com:org/repo.git"              | "git@hostname.com:org/repo.git" | "origin/master"          | null                    | "master"      | null
  }
}
