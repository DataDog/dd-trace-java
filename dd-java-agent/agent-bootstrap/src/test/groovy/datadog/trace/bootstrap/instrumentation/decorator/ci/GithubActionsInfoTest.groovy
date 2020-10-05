package datadog.trace.bootstrap.instrumentation.decorator.ci


import static datadog.trace.bootstrap.instrumentation.decorator.ci.GithubActionsInfo.GHACTIONS
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GithubActionsInfo.GHACTIONS_HEAD_REF
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GithubActionsInfo.GHACTIONS_PIPELINE_ID
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GithubActionsInfo.GHACTIONS_PIPELINE_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GithubActionsInfo.GHACTIONS_PIPELINE_NUMBER
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GithubActionsInfo.GHACTIONS_PROVIDER_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GithubActionsInfo.GHACTIONS_REF
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GithubActionsInfo.GHACTIONS_REPOSITORY
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GithubActionsInfo.GHACTIONS_SHA
import static datadog.trace.bootstrap.instrumentation.decorator.ci.GithubActionsInfo.GHACTIONS_WORKSPACE_PATH

class GithubActionsInfoTest extends CIProviderInfoTest {

  def "Github Actions info is set properly"() {
    setup:
    environmentVariables.set(GHACTIONS, "run")
    environmentVariables.set(GHACTIONS_PIPELINE_ID, "ghactions-pipeline-id")
    environmentVariables.set(GHACTIONS_PIPELINE_NAME, "ghactions-pipeline-name")
    environmentVariables.set(GHACTIONS_PIPELINE_NUMBER, "ghactions-pipeline-number")
    environmentVariables.set(GHACTIONS_WORKSPACE_PATH, ghactionsWorkspace)
    environmentVariables.set(GHACTIONS_REPOSITORY, "ghactions-repo")
    environmentVariables.set(GHACTIONS_SHA, "ghactions-commit")
    environmentVariables.set(GHACTIONS_HEAD_REF, ghactionsPRBranch)
    environmentVariables.set(GHACTIONS_REF, ghactionsBranch)

    when:
    def ciInfo = new GithubActionsInfo()

    then:
    ciInfo.ciProviderName == GHACTIONS_PROVIDER_NAME
    ciInfo.ciPipelineId == "ghactions-pipeline-id"
    ciInfo.ciPipelineName == "ghactions-pipeline-name"
    ciInfo.ciPipelineNumber == "ghactions-pipeline-number"
    ciInfo.ciPipelineUrl == "https://github.com/ghactions-repo/commit/ghactions-commit/checks"
    ciInfo.ciJobUrl == "https://github.com/ghactions-repo/commit/ghactions-commit/checks"
    ciInfo.ciWorkspacePath == ciInfoWorkspace
    ciInfo.gitRepositoryUrl == "https://github.com/ghactions-repo.git"
    ciInfo.gitCommit == "ghactions-commit"
    ciInfo.gitBranch == ciInfoBranch
    ciInfo.gitTag == ciInfoTag

    where:
    ghactionsWorkspace | ciInfoWorkspace       | ghactionsBranch          | ghactionsPRBranch          | ciInfoBranch    | ciInfoTag
    "/foo/bar"         | "/foo/bar"            | "master"                 | null                       | "master"        | null
    "foo/bar"          | "foo/bar"             | "master"                 | null                       | "master"        | null
    "/foo/bar~"        | "/foo/bar~"           | "master"                 | null                       | "master"        | null
    "/foo/~/bar"       | "/foo/~/bar"          | "master"                 | null                       | "master"        | null
    "~/foo/bar"        | userHome + "/foo/bar" | "master"                 | null                       | "master"        | null
    "~foo/bar"         | "~foo/bar"            | "master"                 | null                       | "master"        | null
    "~"                | userHome              | "master"                 | null                       | "master"        | null
    "/foo/bar"         | "/foo/bar"            | "origin/master"          | null                       | "master"        | null
    "/foo/bar"         | "/foo/bar"            | "refs/heads/master"      | null                       | "master"        | null
    "/foo/bar"         | "/foo/bar"            | "refs/heads/feature/one" | null                       | "feature/one"   | null
    "/foo/bar"         | "/foo/bar"            | "origin/tags/0.1.0"      | null                       | null            | "0.1.0"
    "/foo/bar"         | "/foo/bar"            | "refs/heads/tags/0.1.0"  | null                       | null            | "0.1.0"
    "/foo/bar"         | "/foo/bar"            | "origin/master"          | "origin/other"             | "other"         | null
    "/foo/bar"         | "/foo/bar"            | "refs/heads/master"      | "refs/heads/other"         | "other"         | null
    "/foo/bar"         | "/foo/bar"            | "refs/heads/feature/one" | "refs/heads/feature/other" | "feature/other" | null

  }
}
