package datadog.trace.bootstrap.instrumentation.api.ci


import static datadog.trace.bootstrap.instrumentation.api.ci.JenkinsInfo.JENKINS
import static datadog.trace.bootstrap.instrumentation.api.ci.JenkinsInfo.JENKINS_GIT_BRANCH
import static datadog.trace.bootstrap.instrumentation.api.ci.JenkinsInfo.JENKINS_GIT_COMMIT
import static datadog.trace.bootstrap.instrumentation.api.ci.JenkinsInfo.JENKINS_GIT_REPOSITORY_URL
import static datadog.trace.bootstrap.instrumentation.api.ci.JenkinsInfo.JENKINS_JOB_URL
import static datadog.trace.bootstrap.instrumentation.api.ci.JenkinsInfo.JENKINS_PIPELINE_ID
import static datadog.trace.bootstrap.instrumentation.api.ci.JenkinsInfo.JENKINS_PIPELINE_NAME
import static datadog.trace.bootstrap.instrumentation.api.ci.JenkinsInfo.JENKINS_PIPELINE_NUMBER
import static datadog.trace.bootstrap.instrumentation.api.ci.JenkinsInfo.JENKINS_PIPELINE_URL
import static datadog.trace.bootstrap.instrumentation.api.ci.JenkinsInfo.JENKINS_PROVIDER_NAME
import static datadog.trace.bootstrap.instrumentation.api.ci.JenkinsInfo.JENKINS_WORKSPACE_PATH

class JenkinsInfoTest extends CIProviderInfoTest {

  def "Jenkins info is set properly"() {
    setup:
    environmentVariables.set(JENKINS, "jenkins")
    environmentVariables.set(JENKINS_PIPELINE_ID, "jenkins-pipeline-id")
    environmentVariables.set(JENKINS_PIPELINE_NAME, jenkinsJobName)
    environmentVariables.set(JENKINS_PIPELINE_NUMBER, "jenkins-pipeline-number")
    environmentVariables.set(JENKINS_PIPELINE_URL, "jenkins-pipeline-url")
    environmentVariables.set(JENKINS_JOB_URL, "jenkins-job-url")
    environmentVariables.set(JENKINS_WORKSPACE_PATH, jenkinsWorkspace)
    environmentVariables.set(JENKINS_GIT_REPOSITORY_URL, jenkinsRepo)
    environmentVariables.set(JENKINS_GIT_COMMIT, "jenkins-git-commit")
    environmentVariables.set(JENKINS_GIT_BRANCH, jenkinsBranch)

    when:
    def ciInfo = new JenkinsInfo()

    then:
    ciInfo.ciProviderName == JENKINS_PROVIDER_NAME
    ciInfo.ciPipelineId == "jenkins-pipeline-id"
    ciInfo.ciPipelineName == ciInfoName
    ciInfo.ciPipelineNumber == "jenkins-pipeline-number"
    ciInfo.ciPipelineUrl == "jenkins-pipeline-url"
    ciInfo.ciJobUrl == "jenkins-job-url"
    ciInfo.ciWorkspacePath == ciInfoWorkspace
    ciInfo.gitRepositoryUrl == ciInfoRepository
    ciInfo.gitCommit == "jenkins-git-commit"
    ciInfo.gitBranch == ciInfoBranch
    ciInfo.gitTag == ciInfoTag

    where:
    jenkinsWorkspace | ciInfoWorkspace       | jenkinsJobName                                   | ciInfoName        | jenkinsRepo                                  | ciInfoRepository                | jenkinsBranch            | ciInfoBranch  | ciInfoTag
    null             | null                  | "jobName"                                        | "jobName"         | "sample"                                     | "sample"                        | "origin/master"          | "master"      | null
    ""               | ""                    | "jobName"                                        | "jobName"         | "sample"                                     | "sample"                        | "origin/master"          | "master"      | null
    "foo/bar"        | "foo/bar"             | "jobName"                                        | "jobName"         | "sample"                                     | "sample"                        | "origin/master"          | "master"      | null
    "/foo/bar~"      | "/foo/bar~"           | "jobName"                                        | "jobName"         | "sample"                                     | "sample"                        | "origin/master"          | "master"      | null
    "/foo/~/bar"     | "/foo/~/bar"          | "jobName"                                        | "jobName"         | "sample"                                     | "sample"                        | "origin/master"          | "master"      | null
    "~/foo/bar"      | userHome + "/foo/bar" | "jobName"                                        | "jobName"         | "sample"                                     | "sample"                        | "origin/master"          | "master"      | null
    "~foo/bar"       | "~foo/bar"            | "jobName"                                        | "jobName"         | "sample"                                     | "sample"                        | "origin/master"          | "master"      | null
    "~"              | userHome              | "jobName"                                        | "jobName"         | "sample"                                     | "sample"                        | "origin/master"          | "master"      | null
    "/foo/bar"       | "/foo/bar"            | "jobName"                                        | "jobName"         | "sample"                                     | "sample"                        | "origin/master"          | "master"      | null
    "/foo/bar"       | "/foo/bar"            | "jobName/master"                                 | "jobName"         | "sample"                                     | "sample"                        | "refs/heads/master"      | "master"      | null
    "/foo/bar"       | "/foo/bar"            | "jobName/another"                                | "jobName/another" | "sample"                                     | "sample"                        | "refs/heads/master"      | "master"      | null
    "/foo/bar"       | "/foo/bar"            | "jobName/feature/one"                            | "jobName"         | "sample"                                     | "sample"                        | "refs/heads/feature/one" | "feature/one" | null
    "/foo/bar"       | "/foo/bar"            | "jobName/KEY1=VALUE1,KEY2=VALUE2"                | "jobName"         | "sample"                                     | "sample"                        | "refs/heads/master"      | "master"      | null
    "/foo/bar"       | "/foo/bar"            | "jobName/KEY1=VALUE1,KEY2=VALUE2/master"         | "jobName"         | "sample"                                     | "sample"                        | "refs/heads/master"      | "master"      | null
    "/foo/bar"       | "/foo/bar"            | "jobName/KEY1=VALUE1,KEY2=VALUE2/another-branch" | "jobName"         | "sample"                                     | "sample"                        | "refs/heads/master"      | "master"      | null
    "/foo/bar"       | "/foo/bar"            | null                                             | null              | "sample"                                     | "sample"                        | "origin/tags/0.1.0"      | null          | "0.1.0"
    "/foo/bar"       | "/foo/bar"            | ""                                               | ""                | "sample"                                     | "sample"                        | "refs/heads/tags/0.1.0"  | null          | "0.1.0"
    "/foo/bar"       | "/foo/bar"            | "jobName"                                        | "jobName"         | "http://hostname.com/repo.git"               | "http://hostname.com/repo.git"  | "origin/master"          | "master"      | null
    "/foo/bar"       | "/foo/bar"            | "jobName"                                        | "jobName"         | "http://user@hostname.com/repo.git"          | "http://hostname.com/repo.git"  | "origin/master"          | "master"      | null
    "/foo/bar"       | "/foo/bar"            | "jobName"                                        | "jobName"         | "http://user%E2%82%AC@hostname.com/repo.git" | "http://hostname.com/repo.git"  | "origin/master"          | "master"      | null
    "/foo/bar"       | "/foo/bar"            | "jobName"                                        | "jobName"         | "http://user:pwd@hostname.com/repo.git"      | "http://hostname.com/repo.git"  | "origin/master"          | "master"      | null
    "/foo/bar"       | "/foo/bar"            | "jobName"                                        | "jobName"         | "git@hostname.com:org/repo.git"              | "git@hostname.com:org/repo.git" | "origin/master"          | "master"      | null
  }
}
