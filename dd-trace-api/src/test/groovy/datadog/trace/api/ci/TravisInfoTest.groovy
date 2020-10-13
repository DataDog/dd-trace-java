package datadog.trace.api.ci


import static datadog.trace.api.ci.TravisInfo.TRAVIS
import static datadog.trace.api.ci.TravisInfo.TRAVIS_GIT_BRANCH
import static datadog.trace.api.ci.TravisInfo.TRAVIS_GIT_COMMIT
import static datadog.trace.api.ci.TravisInfo.TRAVIS_GIT_PR_BRANCH
import static datadog.trace.api.ci.TravisInfo.TRAVIS_GIT_TAG
import static datadog.trace.api.ci.TravisInfo.TRAVIS_JOB_URL
import static datadog.trace.api.ci.TravisInfo.TRAVIS_PIPELINE_ID
import static datadog.trace.api.ci.TravisInfo.TRAVIS_PIPELINE_NUMBER
import static datadog.trace.api.ci.TravisInfo.TRAVIS_PIPELINE_URL
import static datadog.trace.api.ci.TravisInfo.TRAVIS_PROVIDER_NAME
import static datadog.trace.api.ci.TravisInfo.TRAVIS_PR_REPOSITORY_SLUG
import static datadog.trace.api.ci.TravisInfo.TRAVIS_REPOSITORY_SLUG
import static datadog.trace.api.ci.TravisInfo.TRAVIS_WORKSPACE_PATH

class TravisInfoTest extends CIProviderInfoTest {

  def "Travis info is set properly"() {
    setup:
    environmentVariables.set(TRAVIS, "travisCI")
    environmentVariables.set(TRAVIS_PIPELINE_ID, "travis-pipeline-id")
    environmentVariables.set(TRAVIS_REPOSITORY_SLUG, travisRepoSlug)
    environmentVariables.set(TRAVIS_PR_REPOSITORY_SLUG, travisPRRepoSlug)
    environmentVariables.set(TRAVIS_PIPELINE_NUMBER, "travis-pipeline-number")
    environmentVariables.set(TRAVIS_PIPELINE_URL, "travis-pipeline-url")
    environmentVariables.set(TRAVIS_JOB_URL, "travis-job-url")
    environmentVariables.set(TRAVIS_WORKSPACE_PATH, travisWorkspace)
    environmentVariables.set(TRAVIS_GIT_COMMIT, "travis-git-commit")
    environmentVariables.set(TRAVIS_GIT_BRANCH, travisBranch)
    environmentVariables.set(TRAVIS_GIT_PR_BRANCH, travisPRBranch)
    environmentVariables.set(TRAVIS_GIT_TAG, travisTag)

    when:
    def ciInfo = new TravisInfo()

    then:
    ciInfo.ciProviderName == TRAVIS_PROVIDER_NAME
    ciInfo.ciPipelineId == "travis-pipeline-id"
    ciInfo.ciPipelineName == ciInfoName
    ciInfo.ciPipelineNumber == "travis-pipeline-number"
    ciInfo.ciPipelineUrl == "travis-pipeline-url"
    ciInfo.ciJobUrl == "travis-job-url"
    ciInfo.ciWorkspacePath == ciInfoWorkspace
    ciInfo.gitRepositoryUrl == ciInfoRepository
    ciInfo.gitCommit == "travis-git-commit"
    ciInfo.gitBranch == ciInfoBranch
    ciInfo.gitTag == ciInfoTag

    where:
    travisWorkspace | ciInfoWorkspace       | travisRepoSlug | travisPRRepoSlug | ciInfoName  | ciInfoRepository                   | travisBranch             | travisPRBranch           | ciInfoBranch  | travisTag               | ciInfoTag
    "/foo/bar"      | "/foo/bar"            | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "origin/tags/0.1.0"      | null                     | null          | "origin/tags/0.1.0"     | "0.1.0"
    "/foo/bar"      | "/foo/bar"            | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "refs/heads/tags/0.1.0"  | null                     | null          | "refs/heads/tags/0.1.0" | "0.1.0"
    null            | null                  | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "origin/master"          | null                     | "master"      | null                    | null
    ""              | ""                    | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "origin/master"          | null                     | "master"      | null                    | null
    "foo/bar"       | "foo/bar"             | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "origin/master"          | ""                       | "master"      | null                    | null
    "foo/bar"       | "foo/bar"             | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "origin/master"          | null                     | "master"      | null                    | null
    "foo/bar"       | "foo/bar"             | "user/repo"    | ""               | "user/repo" | "https://github.com/user/repo.git" | "origin/master"          | null                     | "master"      | null                    | null
    "/foo/bar~"     | "/foo/bar~"           | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "origin/master"          | null                     | "master"      | null                    | null
    "/foo/~/bar"    | "/foo/~/bar"          | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "origin/master"          | null                     | "master"      | null                    | null
    "~/foo/bar"     | userHome + "/foo/bar" | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "origin/master"          | null                     | "master"      | null                    | null
    "~foo/bar"      | "~foo/bar"            | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "origin/master"          | null                     | "master"      | null                    | null
    "~"             | userHome              | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "origin/master"          | null                     | "master"      | null                    | null
    "/foo/bar"      | "/foo/bar"            | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "origin/master"          | null                     | "master"      | null                    | null
    "/foo/bar"      | "/foo/bar"            | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "refs/heads/master"      | null                     | "master"      | null                    | null
    "/foo/bar"      | "/foo/bar"            | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "refs/heads/feature/one" | null                     | "feature/one" | null                    | null
    "/foo/bar"      | "/foo/bar"            | "other/repo"   | "user/repo"      | "user/repo" | "https://github.com/user/repo.git" | "origin/other"           | "origin/master"          | "master"      | null                    | null
    "/foo/bar"      | "/foo/bar"            | "other/repo"   | "user/repo"      | "user/repo" | "https://github.com/user/repo.git" | "origin/other"           | "refs/heads/master"      | "master"      | null                    | null
    "/foo/bar"      | "/foo/bar"            | "other/repo"   | "user/repo"      | "user/repo" | "https://github.com/user/repo.git" | "origin/other"           | "refs/heads/feature/one" | "feature/one" | null                    | null
  }
}
