package datadog.trace.api.ci

import static datadog.trace.api.ci.AppVeyorInfo.APPVEYOR
import static datadog.trace.api.ci.AppVeyorInfo.APPVEYOR_BUILD_ID
import static datadog.trace.api.ci.AppVeyorInfo.APPVEYOR_PIPELINE_NUMBER
import static datadog.trace.api.ci.AppVeyorInfo.APPVEYOR_PROVIDER_NAME
import static datadog.trace.api.ci.AppVeyorInfo.APPVEYOR_PULL_REQUEST_HEAD_REPO_BRANCH
import static datadog.trace.api.ci.AppVeyorInfo.APPVEYOR_REPO_BRANCH
import static datadog.trace.api.ci.AppVeyorInfo.APPVEYOR_REPO_COMMIT
import static datadog.trace.api.ci.AppVeyorInfo.APPVEYOR_REPO_NAME
import static datadog.trace.api.ci.AppVeyorInfo.APPVEYOR_REPO_PROVIDER
import static datadog.trace.api.ci.AppVeyorInfo.APPVEYOR_REPO_TAG_NAME
import static datadog.trace.api.ci.AppVeyorInfo.APPVEYOR_WORKSPACE_PATH

class AppVeyorInfoTest extends CIProviderInfoTest {

  def "AppVeyor info is set properly"() {
    setup:
    environmentVariables.set(APPVEYOR, "true")
    environmentVariables.set(APPVEYOR_BUILD_ID, "appveyor-build-id")
    environmentVariables.set(APPVEYOR_REPO_NAME, "appveyor-repo-name")
    environmentVariables.set(APPVEYOR_PIPELINE_NUMBER, "appveyor-pipeline-number")
    environmentVariables.set(APPVEYOR_WORKSPACE_PATH, appveyorWorkspace)
    environmentVariables.set(APPVEYOR_REPO_PROVIDER, appveyorProvider)
    environmentVariables.set(APPVEYOR_REPO_COMMIT, "appveyor-repo-commit")
    environmentVariables.set(APPVEYOR_REPO_BRANCH, appveyorBranch)
    environmentVariables.set(APPVEYOR_PULL_REQUEST_HEAD_REPO_BRANCH, appveyorPRBranch)
    environmentVariables.set(APPVEYOR_REPO_TAG_NAME, appveyorTag)

    when:
    def ciInfo = new AppVeyorInfo()

    then:
    ciInfo.ciProviderName == APPVEYOR_PROVIDER_NAME
    ciInfo.ciPipelineId == "appveyor-build-id"
    ciInfo.ciPipelineName == "appveyor-repo-name"
    ciInfo.ciPipelineNumber == "appveyor-pipeline-number"
    ciInfo.ciPipelineUrl == "https://ci.appveyor.com/project/appveyor-repo-name/builds/appveyor-build-id"
    ciInfo.ciJobUrl == "https://ci.appveyor.com/project/appveyor-repo-name/builds/appveyor-build-id"
    ciInfo.ciWorkspacePath == ciInfoWorkspace
    ciInfo.gitRepositoryUrl == ciInfoRepository
    ciInfo.gitCommit == ciInfoCommit
    ciInfo.gitBranch == ciInfoBranch
    ciInfo.gitTag == ciInfoTag

    where:
    appveyorWorkspace | ciInfoWorkspace       | appveyorProvider | appveyorBranch           | appveyorPRBranch | appveyorTag             | ciInfoRepository                            | ciInfoCommit           | ciInfoBranch  | ciInfoTag
    "/foo/bar"        | "/foo/bar"            | "github"         | "master"                 | null             | null                    | "https://github.com/appveyor-repo-name.git" | "appveyor-repo-commit" | "master"      | null
    "foo/bar"         | "foo/bar"             | "github"         | "master"                 | null             | null                    | "https://github.com/appveyor-repo-name.git" | "appveyor-repo-commit" | "master"      | null
    "/foo/bar~"       | "/foo/bar~"           | "github"         | "master"                 | null             | null                    | "https://github.com/appveyor-repo-name.git" | "appveyor-repo-commit" | "master"      | null
    "/foo/~/bar"      | "/foo/~/bar"          | "github"         | "master"                 | null             | null                    | "https://github.com/appveyor-repo-name.git" | "appveyor-repo-commit" | "master"      | null
    "~/foo/bar"       | userHome + "/foo/bar" | "github"         | "master"                 | null             | null                    | "https://github.com/appveyor-repo-name.git" | "appveyor-repo-commit" | "master"      | null
    "~foo/bar"        | "~foo/bar"            | "github"         | "master"                 | null             | null                    | "https://github.com/appveyor-repo-name.git" | "appveyor-repo-commit" | "master"      | null
    "~"               | userHome              | "github"         | "master"                 | null             | null                    | "https://github.com/appveyor-repo-name.git" | "appveyor-repo-commit" | "master"      | null
    "/foo/bar"        | "/foo/bar"            | null             | "master"                 | null             | null                    | null                                        | null                   | null          | null
    "/foo/bar"        | "/foo/bar"            | "github"         | "origin/master"          | null             | null                    | "https://github.com/appveyor-repo-name.git" | "appveyor-repo-commit" | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "github"         | "refs/heads/master"      | null             | null                    | "https://github.com/appveyor-repo-name.git" | "appveyor-repo-commit" | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "github"         | "refs/heads/feature/one" | null             | null                    | "https://github.com/appveyor-repo-name.git" | "appveyor-repo-commit" | "feature/one" | null
    "/foo/bar"        | "/foo/bar"            | "github"         | "origin/master"          | "origin/pr"      | null                    | "https://github.com/appveyor-repo-name.git" | "appveyor-repo-commit" | "pr"          | null
    "/foo/bar"        | "/foo/bar"            | "github"         | "refs/heads/master"      | "refs/heads/pr"  | null                    | "https://github.com/appveyor-repo-name.git" | "appveyor-repo-commit" | "pr"          | null
    "/foo/bar"        | "/foo/bar"            | "github"         | "origin/master"          | null             | "origin/tags/0.1.0"     | "https://github.com/appveyor-repo-name.git" | "appveyor-repo-commit" | null          | "0.1.0"
    "/foo/bar"        | "/foo/bar"            | "github"         | "refs/heads/master"      | null             | "refs/heads/tags/0.1.0" | "https://github.com/appveyor-repo-name.git" | "appveyor-repo-commit" | null          | "0.1.0"
  }

}
