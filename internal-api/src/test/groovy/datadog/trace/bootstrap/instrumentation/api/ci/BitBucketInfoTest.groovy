package datadog.trace.bootstrap.instrumentation.api.ci

import static datadog.trace.bootstrap.instrumentation.api.ci.BitBucketInfo.BITBUCKET
import static datadog.trace.bootstrap.instrumentation.api.ci.BitBucketInfo.BITBUCKET_BUILD_NUMBER
import static datadog.trace.bootstrap.instrumentation.api.ci.BitBucketInfo.BITBUCKET_GIT_BRANCH
import static datadog.trace.bootstrap.instrumentation.api.ci.BitBucketInfo.BITBUCKET_GIT_COMMIT
import static datadog.trace.bootstrap.instrumentation.api.ci.BitBucketInfo.BITBUCKET_GIT_REPOSITORY_URL
import static datadog.trace.bootstrap.instrumentation.api.ci.BitBucketInfo.BITBUCKET_GIT_TAG
import static datadog.trace.bootstrap.instrumentation.api.ci.BitBucketInfo.BITBUCKET_PIPELINE_ID
import static datadog.trace.bootstrap.instrumentation.api.ci.BitBucketInfo.BITBUCKET_PROVIDER_NAME
import static datadog.trace.bootstrap.instrumentation.api.ci.BitBucketInfo.BITBUCKET_REPO_FULL_NAME
import static datadog.trace.bootstrap.instrumentation.api.ci.BitBucketInfo.BITBUCKET_WORKSPACE_PATH

class BitBucketInfoTest extends CIProviderInfoTest {

  def "BitBucket info is set properly"() {
    setup:
    environmentVariables.set(BITBUCKET, "True")
    environmentVariables.set(BITBUCKET_PIPELINE_ID, "{bitbucket-uuid}")
    environmentVariables.set(BITBUCKET_REPO_FULL_NAME, "bitbucket-repo")
    environmentVariables.set(BITBUCKET_BUILD_NUMBER, "bitbucket-build-num")
    environmentVariables.set(BITBUCKET_WORKSPACE_PATH, bibucketWorkspace)
    environmentVariables.set(BITBUCKET_GIT_REPOSITORY_URL, "bitbucket-repo-url")
    environmentVariables.set(BITBUCKET_GIT_COMMIT, "bitbucket-commit")
    environmentVariables.set(BITBUCKET_GIT_BRANCH, bitbucketBranch)
    environmentVariables.set(BITBUCKET_GIT_TAG, bitbucketTag)

    when:
    def ciInfo = new BitBucketInfo()

    then:
    ciInfo.ciProviderName == BITBUCKET_PROVIDER_NAME
    ciInfo.ciPipelineId == "bitbucket-uuid"
    ciInfo.ciPipelineName == "bitbucket-repo"
    ciInfo.ciPipelineNumber == "bitbucket-build-num"
    ciInfo.ciPipelineUrl == "https://bitbucket.org/bitbucket-repo/addon/pipelines/home#!/results/bitbucket-build-num"
    ciInfo.ciJobUrl == "https://bitbucket.org/bitbucket-repo/addon/pipelines/home#!/results/bitbucket-build-num"
    ciInfo.ciWorkspacePath == ciInfoWorkspace
    ciInfo.gitRepositoryUrl == "bitbucket-repo-url"
    ciInfo.gitCommit == "bitbucket-commit"
    ciInfo.gitBranch == ciInfoBranch
    ciInfo.gitTag == ciInfoTag

    where:
    bibucketWorkspace | ciInfoWorkspace       | bitbucketBranch          | bitbucketTag            | ciInfoBranch  | ciInfoTag
    "/foo/bar"        | "/foo/bar"            | "master"                 | null                    | "master"      | null
    "foo/bar"         | "foo/bar"             | "master"                 | null                    | "master"      | null
    "/foo/bar~"       | "/foo/bar~"           | "master"                 | null                    | "master"      | null
    "/foo/~/bar"      | "/foo/~/bar"          | "master"                 | null                    | "master"      | null
    "~/foo/bar"       | userHome + "/foo/bar" | "master"                 | null                    | "master"      | null
    "~foo/bar"        | "~foo/bar"            | "master"                 | null                    | "master"      | null
    "~"               | userHome              | "master"                 | null                    | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "origin/master"          | null                    | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "refs/heads/master"      | null                    | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "refs/heads/feature/one" | null                    | "feature/one" | null
    "/foo/bar"        | "/foo/bar"            | null                     | "origin/tags/0.1.0"     | null          | "0.1.0"
    "/foo/bar"        | "/foo/bar"            | null                     | "refs/heads/tags/0.1.0" | null          | "0.1.0"
  }
}
