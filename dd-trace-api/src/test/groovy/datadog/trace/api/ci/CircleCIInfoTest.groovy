package datadog.trace.api.ci


import static datadog.trace.api.ci.CircleCIInfo.CIRCLECI
import static datadog.trace.api.ci.CircleCIInfo.CIRCLECI_BUILD_URL
import static datadog.trace.api.ci.CircleCIInfo.CIRCLECI_GIT_BRANCH
import static datadog.trace.api.ci.CircleCIInfo.CIRCLECI_GIT_COMMIT
import static datadog.trace.api.ci.CircleCIInfo.CIRCLECI_GIT_REPOSITORY_URL
import static datadog.trace.api.ci.CircleCIInfo.CIRCLECI_GIT_TAG
import static datadog.trace.api.ci.CircleCIInfo.CIRCLECI_PIPELINE_ID
import static datadog.trace.api.ci.CircleCIInfo.CIRCLECI_PIPELINE_NAME
import static datadog.trace.api.ci.CircleCIInfo.CIRCLECI_PIPELINE_NUMBER
import static datadog.trace.api.ci.CircleCIInfo.CIRCLECI_PROVIDER_NAME
import static datadog.trace.api.ci.CircleCIInfo.CIRCLECI_WORKSPACE_PATH

class CircleCIInfoTest extends CIProviderInfoTest {

  def "CircleCI info is set properly"() {
    setup:
    environmentVariables.set(CIRCLECI, "circleCI")
    environmentVariables.set(CIRCLECI_PIPELINE_ID, "circleci-pipeline-id")
    environmentVariables.set(CIRCLECI_PIPELINE_NAME, "circleci-pipeline-name")
    environmentVariables.set(CIRCLECI_PIPELINE_NUMBER, "circleci-pipeline-number")
    environmentVariables.set(CIRCLECI_BUILD_URL, "circleci-build-url")
    environmentVariables.set(CIRCLECI_WORKSPACE_PATH, circleciWorkspace)
    environmentVariables.set(CIRCLECI_GIT_REPOSITORY_URL, circleciRepo)
    environmentVariables.set(CIRCLECI_GIT_COMMIT, "circleci-git-commit")
    environmentVariables.set(CIRCLECI_GIT_BRANCH, circleciBranch)
    environmentVariables.set(CIRCLECI_GIT_TAG, circleciTag)

    when:
    def ciInfo = new CircleCIInfo()

    then:
    ciInfo.ciProviderName == CIRCLECI_PROVIDER_NAME
    ciInfo.ciPipelineId == "circleci-pipeline-id"
    ciInfo.ciPipelineName == "circleci-pipeline-name"
    ciInfo.ciPipelineNumber == "circleci-pipeline-number"
    ciInfo.ciPipelineUrl == "circleci-build-url"
    ciInfo.ciJobUrl == "circleci-build-url"
    ciInfo.ciWorkspacePath == ciInfoWorkspace
    ciInfo.gitRepositoryUrl == ciInfoRepository
    ciInfo.gitCommit == "circleci-git-commit"
    ciInfo.gitBranch == ciInfoBranch
    ciInfo.gitTag == ciInfoTag

    where:
    circleciWorkspace | ciInfoWorkspace       | circleciRepo                                 | ciInfoRepository                | circleciBranch           | circleciTag             | ciInfoBranch  | ciInfoTag
    null              | null                  | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    ""                | ""                    | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "sample"                                     | "sample"                        | "origin/master"          | ""                      | "master"      | null
    "foo/bar"         | "foo/bar"             | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "/foo/bar~"       | "/foo/bar~"           | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "/foo/~/bar"      | "/foo/~/bar"          | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "~/foo/bar"       | userHome + "/foo/bar" | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "~foo/bar"        | "~foo/bar"            | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "~"               | userHome              | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "sample"                                     | "sample"                        | "refs/heads/master"      | null                    | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "sample"                                     | "sample"                        | "refs/heads/feature/one" | null                    | "feature/one" | null
    "/foo/bar"        | "/foo/bar"            | "sample"                                     | "sample"                        | "origin/tags/0.1.0"      | "origin/tags/0.1.0"     | null          | "0.1.0"
    "/foo/bar"        | "/foo/bar"            | "sample"                                     | "sample"                        | "refs/heads/tags/0.1.0"  | "refs/heads/tags/0.1.0" | null          | "0.1.0"
    "/foo/bar"        | "/foo/bar"            | null                                         | null                            | "origin/master"          | null                    | "master"      | null
    "/foo/bar"        | "/foo/bar"            | ""                                           | null                            | "origin/master"          | null                    | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "http://hostname.com/repo.git"               | "http://hostname.com/repo.git"  | "origin/master"          | null                    | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "http://user@hostname.com/repo.git"          | "http://hostname.com/repo.git"  | "origin/master"          | null                    | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "http://user%E2%82%AC@hostname.com/repo.git" | "http://hostname.com/repo.git"  | "origin/master"          | null                    | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "http://user:pwd@hostname.com/repo.git"      | "http://hostname.com/repo.git"  | "origin/master"          | null                    | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "git@hostname.com:org/repo.git"              | "git@hostname.com:org/repo.git" | "origin/master"          | null                    | "master"      | null
  }
}
