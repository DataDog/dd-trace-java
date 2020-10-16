package datadog.trace.bootstrap.instrumentation.api.ci


import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE
import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE_BUILD_BUILDID
import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE_BUILD_REPOSITORY_URI
import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE_BUILD_SOURCEBRANCH
import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE_BUILD_SOURCEVERSION
import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE_PIPELINE_NAME
import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE_PROVIDER_NAME
import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE_SYSTEM_JOBID
import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE_SYSTEM_PULLREQUEST_SOURCEBRANCH
import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE_SYSTEM_PULLREQUEST_SOURCECOMMITID
import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE_SYSTEM_PULLREQUEST_SOURCEREPOSITORYURI
import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE_SYSTEM_TASKINSTANCEID
import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE_SYSTEM_TEAMFOUNDATIONSERVERURI
import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE_SYSTEM_TEAMPROJECT
import static datadog.trace.bootstrap.instrumentation.api.ci.AzurePipelinesInfo.AZURE_WORKSPACE_PATH

class AzurePipelinesInfoTest extends CIProviderInfoTest {

  def "Azure Pipelines info is set properly"() {
    setup:
    environmentVariables.set(AZURE, "True")
    environmentVariables.set(AZURE_PIPELINE_NAME, "azure-pipelines-name")
    environmentVariables.set(AZURE_SYSTEM_TEAMFOUNDATIONSERVERURI, "azure-pipelines-server-uri/")
    environmentVariables.set(AZURE_SYSTEM_TEAMPROJECT, "azure-pipelines-project")
    environmentVariables.set(AZURE_BUILD_BUILDID, "azure-pipelines-build-id")
    environmentVariables.set(AZURE_SYSTEM_JOBID, "azure-pipelines-job-id")
    environmentVariables.set(AZURE_SYSTEM_TASKINSTANCEID, "azure-pipelines-task-id")
    environmentVariables.set(AZURE_WORKSPACE_PATH, azureWorkspace)
    environmentVariables.set(AZURE_BUILD_REPOSITORY_URI, azureRepo)
    environmentVariables.set(AZURE_SYSTEM_PULLREQUEST_SOURCEREPOSITORYURI, azurePRRepo)
    environmentVariables.set(AZURE_BUILD_SOURCEBRANCH, azureBranch)
    environmentVariables.set(AZURE_SYSTEM_PULLREQUEST_SOURCEBRANCH, azurePRBranch)
    environmentVariables.set(AZURE_BUILD_SOURCEVERSION, azureCommit)
    environmentVariables.set(AZURE_SYSTEM_PULLREQUEST_SOURCECOMMITID, azurePRCommit)

    when:
    def ciInfo = new AzurePipelinesInfo()

    then:
    ciInfo.ciProviderName == AZURE_PROVIDER_NAME
    ciInfo.ciPipelineId == "azure-pipelines-build-id"
    ciInfo.ciPipelineName == "azure-pipelines-name"
    ciInfo.ciPipelineNumber == "azure-pipelines-build-id"
    ciInfo.ciPipelineUrl == "azure-pipelines-server-uri/azure-pipelines-project/_build/results?buildId=azure-pipelines-build-id&_a=summary"
    ciInfo.ciJobUrl == "azure-pipelines-server-uri/azure-pipelines-project/_build/results?buildId=azure-pipelines-build-id&view=logs&j=azure-pipelines-job-id&t=azure-pipelines-task-id"
    ciInfo.ciWorkspacePath == ciInfoWorkspace
    ciInfo.gitRepositoryUrl == ciInfoRepo
    ciInfo.gitCommit == ciInfoCommit
    ciInfo.gitBranch == ciInfoBranch
    ciInfo.gitTag == ciInfoTag

    where:
    azureWorkspace | ciInfoWorkspace       | azureRepo | azurePRRepo | ciInfoRepo | azureBranch              | azurePRBranch   | ciInfoBranch  | ciInfoTag | azureCommit | azurePRCommit | ciInfoCommit
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"   | "master"                 | null            | "master"      | null      | "commit"    | null          | "commit"
    "/foo/bar"     | "/foo/bar"            | "sample"  | ""          | "sample"   | "master"                 | null            | "master"      | null      | "commit"    | null          | "commit"
    "/foo/bar"     | "/foo/bar"            | "sample"  | "sample2"   | "sample2"  | "master"                 | null            | "master"      | null      | "commit"    | null          | "commit"
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"   | "master"                 | ""              | "master"      | null      | "commit"    | null          | "commit"
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"   | "master"                 | null            | "master"      | null      | "commit"    | ""            | "commit"
    "foo/bar"      | "foo/bar"             | "sample"  | null        | "sample"   | "origin/master"          | null            | "master"      | null      | "commit"    | null          | "commit"
    "/foo/bar~"    | "/foo/bar~"           | "sample"  | null        | "sample"   | "origin/master"          | null            | "master"      | null      | "commit"    | null          | "commit"
    "/foo/~/bar"   | "/foo/~/bar"          | "sample"  | null        | "sample"   | "origin/master"          | null            | "master"      | null      | "commit"    | null          | "commit"
    "~/foo/bar"    | userHome + "/foo/bar" | "sample"  | null        | "sample"   | "origin/master"          | null            | "master"      | null      | "commit"    | null          | "commit"
    "~foo/bar"     | "~foo/bar"            | "sample"  | null        | "sample"   | "origin/master"          | null            | "master"      | null      | "commit"    | null          | "commit"
    "~"            | userHome              | "sample"  | null        | "sample"   | "origin/master"          | null            | "master"      | null      | "commit"    | null          | "commit"
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"   | "origin/master"          | null            | "master"      | null      | "commit"    | null          | "commit"
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"   | "refs/heads/master"      | null            | "master"      | null      | "commit"    | null          | "commit"
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"   | "refs/heads/feature/one" | null            | "feature/one" | null      | "commit"    | null          | "commit"
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"   | "origin/tags/0.1.0"      | null            | null          | "0.1.0"   | "commit"    | null          | "commit"
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"   | "refs/heads/tags/0.1.0"  | null            | null          | "0.1.0"   | "commit"    | null          | "commit"
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"   | "origin/master"          | "origin/pr"     | "pr"          | null      | "commit"    | "commitPR"    | "commitPR"
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"   | "refs/heads/master"      | "refs/heads/pr" | "pr"          | null      | "commit"    | "commitPR"    | "commitPR"
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"   | "refs/heads/feature/one" | "refs/heads/pr" | "pr"          | null      | "commit"    | "commitPR"    | "commitPR"
  }
}
