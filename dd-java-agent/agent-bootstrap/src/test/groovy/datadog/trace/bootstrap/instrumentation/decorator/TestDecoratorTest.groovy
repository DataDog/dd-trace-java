package datadog.trace.bootstrap.instrumentation.decorator


import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables
import spock.lang.Shared

import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.APPVEYOR
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.APPVEYOR_BUILD_ID
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.APPVEYOR_PIPELINE_NUMBER
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.APPVEYOR_PROVIDER_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.APPVEYOR_PULL_REQUEST_HEAD_REPO_BRANCH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.APPVEYOR_REPO_BRANCH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.APPVEYOR_REPO_COMMIT
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.APPVEYOR_REPO_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.APPVEYOR_REPO_PROVIDER
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.APPVEYOR_REPO_TAG_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.APPVEYOR_WORKSPACE_PATH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.AZURE
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.AZURE_BUILD_BUILDID
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.AZURE_BUILD_REPOSITORY_URI
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.AZURE_BUILD_SOURCEBRANCH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.AZURE_BUILD_SOURCEVERSION
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.AZURE_PIPELINE_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.AZURE_PIPELINE_NUMBER
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.AZURE_PROVIDER_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.AZURE_SYSTEM_JOBID
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.AZURE_SYSTEM_PULLREQUEST_SOURCEBRANCH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.AZURE_SYSTEM_PULLREQUEST_SOURCECOMMITID
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.AZURE_SYSTEM_PULLREQUEST_SOURCEREPOSITORYURI
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.AZURE_SYSTEM_TASKINSTANCEID
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.AZURE_SYSTEM_TEAMFOUNDATIONSERVERURI
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.AZURE_SYSTEM_TEAMPROJECT
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.AZURE_WORKSPACE_PATH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BITBUCKET
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BITBUCKET_BUILD_NUMBER
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BITBUCKET_GIT_BRANCH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BITBUCKET_GIT_COMMIT
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BITBUCKET_GIT_REPOSITORY_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BITBUCKET_GIT_TAG
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BITBUCKET_PIPELINE_ID
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BITBUCKET_PROVIDER_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BITBUCKET_REPO_FULL_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BITBUCKET_WORKSPACE_PATH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BUILDKITE
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BUILDKITE_BUILD_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BUILDKITE_GIT_BRANCH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BUILDKITE_GIT_COMMIT
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BUILDKITE_GIT_REPOSITORY_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BUILDKITE_GIT_TAG
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BUILDKITE_JOB_ID
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BUILDKITE_PIPELINE_ID
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BUILDKITE_PIPELINE_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BUILDKITE_PIPELINE_NUMBER
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BUILDKITE_PROVIDER_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.BUILDKITE_WORKSPACE_PATH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.CIRCLECI
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.CIRCLECI_BUILD_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.CIRCLECI_GIT_BRANCH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.CIRCLECI_GIT_COMMIT
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.CIRCLECI_GIT_REPOSITORY_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.CIRCLECI_GIT_TAG
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.CIRCLECI_PIPELINE_ID
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.CIRCLECI_PIPELINE_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.CIRCLECI_PIPELINE_NUMBER
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.CIRCLECI_PROVIDER_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.CIRCLECI_WORKSPACE_PATH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GHACTIONS
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GHACTIONS_HEAD_REF
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GHACTIONS_PIPELINE_ID
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GHACTIONS_PIPELINE_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GHACTIONS_PIPELINE_NUMBER
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GHACTIONS_PROVIDER_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GHACTIONS_REF
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GHACTIONS_REPOSITORY
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GHACTIONS_SHA
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GHACTIONS_WORKSPACE_PATH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_GIT_BRANCH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_GIT_COMMIT
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_GIT_REPOSITORY_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_GIT_TAG
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_JOB_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_PIPELINE_ID
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_PIPELINE_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_PIPELINE_NUMBER
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_PIPELINE_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_PROVIDER_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.GITLAB_WORKSPACE_PATH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_GIT_BRANCH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_GIT_COMMIT
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_GIT_REPOSITORY_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_JOB_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_PIPELINE_ID
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_PIPELINE_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_PIPELINE_NUMBER
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_PIPELINE_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_PROVIDER_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.JENKINS_WORKSPACE_PATH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.TRAVIS
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.TRAVIS_GIT_BRANCH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.TRAVIS_GIT_COMMIT
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.TRAVIS_GIT_PR_BRANCH
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.TRAVIS_GIT_TAG
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.TRAVIS_JOB_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.TRAVIS_PIPELINE_ID
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.TRAVIS_PIPELINE_NUMBER
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.TRAVIS_PIPELINE_URL
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.TRAVIS_PROVIDER_NAME
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.TRAVIS_PR_REPOSITORY_SLUG
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.TRAVIS_REPOSITORY_SLUG
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.TRAVIS_WORKSPACE_PATH

class TestDecoratorTest extends BaseDecoratorTest {

  @Rule
  public final EnvironmentVariables environmentVariables = new EnvironmentVariables()

  @Shared
  def userHome = System.getProperty("user.home")

  def span = Mock(AgentSpan)

  def "test afterStart"() {
    setup:
    def decorator = newDecorator()

    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.setTag(Tags.SPAN_KIND, decorator.spanKind())
    1 * span.setSpanType(decorator.spanType())
    1 * span.setTag(Tags.TEST_FRAMEWORK, decorator.testFramework())
    1 * span.setTag(Tags.TEST_TYPE, decorator.testType())
    1 * span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP)
    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    0 * _

    where:
    serviceName << ["test-service", "other-service", null]
  }

  def "test afterStart in Jenkins"() {
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
    def decorator = newDecorator()

    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.setTag(Tags.SPAN_KIND, decorator.spanKind())
    1 * span.setSpanType(decorator.spanType())
    1 * span.setTag(Tags.TEST_FRAMEWORK, decorator.testFramework())
    1 * span.setTag(Tags.TEST_TYPE, decorator.testType())
    1 * span.setTag(Tags.CI_PROVIDER_NAME, JENKINS_PROVIDER_NAME)
    1 * span.setTag(Tags.CI_PIPELINE_ID, "jenkins-pipeline-id")
    1 * span.setTag(Tags.CI_PIPELINE_NAME, spanTagName)
    1 * span.setTag(Tags.CI_PIPELINE_NUMBER, "jenkins-pipeline-number")
    1 * span.setTag(Tags.CI_PIPELINE_URL, "jenkins-pipeline-url")
    1 * span.setTag(Tags.CI_JOB_URL, "jenkins-job-url")
    1 * span.setTag(Tags.CI_WORKSPACE_PATH, spanTagWorkspace)
    1 * span.setTag(Tags.BUILD_SOURCE_ROOT, spanTagWorkspace)
    1 * span.setTag(Tags.GIT_REPOSITORY_URL, spanTagRepo)
    1 * span.setTag(Tags.GIT_COMMIT_SHA, "jenkins-git-commit")
    1 * span.setTag(Tags.GIT_BRANCH, spanTagBranch)
    1 * span.setTag(Tags.GIT_TAG, spanTagTag)
    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    _ * span.setSamplingPriority(_)
    0 * _

    where:
    jenkinsWorkspace | spanTagWorkspace      | jenkinsJobName                                   | spanTagName       | jenkinsRepo                                  | spanTagRepo                     | jenkinsBranch            | spanTagBranch | spanTagTag
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

  def "test afterStart in GitLab"() {
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
    def decorator = newDecorator()

    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.setTag(Tags.SPAN_KIND, decorator.spanKind())
    1 * span.setSpanType(decorator.spanType())
    1 * span.setTag(Tags.TEST_FRAMEWORK, decorator.testFramework())
    1 * span.setTag(Tags.TEST_TYPE, decorator.testType())
    1 * span.setTag(Tags.CI_PROVIDER_NAME, GITLAB_PROVIDER_NAME)
    1 * span.setTag(Tags.CI_PIPELINE_ID, "gitlab-pipeline-id")
    1 * span.setTag(Tags.CI_PIPELINE_NAME, "gitlab-pipeline-name")
    1 * span.setTag(Tags.CI_PIPELINE_NUMBER, "gitlab-pipeline-number")
    1 * span.setTag(Tags.CI_PIPELINE_URL, "gitlab-pipeline-url")
    1 * span.setTag(Tags.CI_JOB_URL, "gitlab-job-url")
    1 * span.setTag(Tags.CI_WORKSPACE_PATH, spanTagWorkspace)
    1 * span.setTag(Tags.BUILD_SOURCE_ROOT, spanTagWorkspace)
    1 * span.setTag(Tags.GIT_REPOSITORY_URL, spanTagRepo)
    1 * span.setTag(Tags.GIT_COMMIT_SHA, "gitlab-git-commit")
    1 * span.setTag(Tags.GIT_BRANCH, spanTagBranch)
    1 * span.setTag(Tags.GIT_TAG, spanTagTag)
    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    _ * span.setSamplingPriority(_)
    0 * _

    where:
    gitlabWorkspace | spanTagWorkspace      | gitlabRepo                                   | spanTagRepo                     | gitlabBranch             | gitlabTag               | spanTagBranch | spanTagTag
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

  def "test afterStart in TravisCI"() {
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
    def decorator = newDecorator()

    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.setTag(Tags.SPAN_KIND, decorator.spanKind())
    1 * span.setSpanType(decorator.spanType())
    1 * span.setTag(Tags.TEST_FRAMEWORK, decorator.testFramework())
    1 * span.setTag(Tags.TEST_TYPE, decorator.testType())
    1 * span.setTag(Tags.CI_PROVIDER_NAME, TRAVIS_PROVIDER_NAME)
    1 * span.setTag(Tags.CI_PIPELINE_ID, "travis-pipeline-id")
    1 * span.setTag(Tags.CI_PIPELINE_NAME, spanTagName)
    1 * span.setTag(Tags.CI_PIPELINE_NUMBER, "travis-pipeline-number")
    1 * span.setTag(Tags.CI_PIPELINE_URL, "travis-pipeline-url")
    1 * span.setTag(Tags.CI_JOB_URL, "travis-job-url")
    1 * span.setTag(Tags.CI_WORKSPACE_PATH, spanTagWorkspace)
    1 * span.setTag(Tags.BUILD_SOURCE_ROOT, spanTagWorkspace)
    1 * span.setTag(Tags.GIT_REPOSITORY_URL, spanTagRepo)
    1 * span.setTag(Tags.GIT_COMMIT_SHA, "travis-git-commit")
    1 * span.setTag(Tags.GIT_BRANCH, spanTagBranch)
    1 * span.setTag(Tags.GIT_TAG, spanTagTag)
    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    _ * span.setSamplingPriority(_)
    0 * _

    where:
    travisWorkspace | spanTagWorkspace      | travisRepoSlug | travisPRRepoSlug | spanTagName | spanTagRepo                        | travisBranch             | travisPRBranch           | spanTagBranch | travisTag               | spanTagTag
    "/foo/bar"      | "/foo/bar"            | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "origin/tags/0.1.0"      | null                     | null          | "origin/tags/0.1.0"     | "0.1.0"
    "/foo/bar"      | "/foo/bar"            | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "refs/heads/tags/0.1.0"  | null                     | null          | "refs/heads/tags/0.1.0" | "0.1.0"
    null            | null                  | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "origin/master"          | null                     | "master"      | null                    | null
    ""              | ""                    | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "origin/master"          | null                     | "master"      | null                    | null
    "foo/bar"       | "foo/bar"             | "user/repo"    | null             | "user/repo" | "https://github.com/user/repo.git" | "origin/master"          | null                     | "master"      | null                    | null
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

  def "test afterStart in CircleCI"() {
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
    def decorator = newDecorator()

    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.setTag(Tags.SPAN_KIND, decorator.spanKind())
    1 * span.setSpanType(decorator.spanType())
    1 * span.setTag(Tags.TEST_FRAMEWORK, decorator.testFramework())
    1 * span.setTag(Tags.TEST_TYPE, decorator.testType())
    1 * span.setTag(Tags.CI_PROVIDER_NAME, CIRCLECI_PROVIDER_NAME)
    1 * span.setTag(Tags.CI_PIPELINE_ID, "circleci-pipeline-id")
    1 * span.setTag(Tags.CI_PIPELINE_NAME, "circleci-pipeline-name")
    1 * span.setTag(Tags.CI_PIPELINE_NUMBER, "circleci-pipeline-number")
    1 * span.setTag(Tags.CI_PIPELINE_URL, "circleci-build-url")
    1 * span.setTag(Tags.CI_JOB_URL, "circleci-build-url")
    1 * span.setTag(Tags.CI_WORKSPACE_PATH, spanTagWorkspace)
    1 * span.setTag(Tags.BUILD_SOURCE_ROOT, spanTagWorkspace)
    1 * span.setTag(Tags.GIT_REPOSITORY_URL, spanTagRepo)
    1 * span.setTag(Tags.GIT_COMMIT_SHA, "circleci-git-commit")
    1 * span.setTag(Tags.GIT_BRANCH, spanTagBranch)
    1 * span.setTag(Tags.GIT_TAG, spanTagTag)
    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    _ * span.setSamplingPriority(_)
    0 * _

    where:
    circleciWorkspace | spanTagWorkspace      | circleciRepo                                 | spanTagRepo                     | circleciBranch           | circleciTag             | spanTagBranch | spanTagTag
    null              | null                  | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    ""                | ""                    | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "sample"                                     | "sample"                        | "origin/master"          | null                    | "master"      | null
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
    "/foo/bar"        | "/foo/bar"            | "http://hostname.com/repo.git"               | "http://hostname.com/repo.git"  | "origin/master"          | null                    | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "http://user@hostname.com/repo.git"          | "http://hostname.com/repo.git"  | "origin/master"          | null                    | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "http://user%E2%82%AC@hostname.com/repo.git" | "http://hostname.com/repo.git"  | "origin/master"          | null                    | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "http://user:pwd@hostname.com/repo.git"      | "http://hostname.com/repo.git"  | "origin/master"          | null                    | "master"      | null
    "/foo/bar"        | "/foo/bar"            | "git@hostname.com:org/repo.git"              | "git@hostname.com:org/repo.git" | "origin/master"          | null                    | "master"      | null
  }

  def "test afterStart in Azure Pipelines"() {
    setup:
    environmentVariables.set(AZURE, "True")
    environmentVariables.set(AZURE_PIPELINE_NAME, "azure-pipelines-name")
    environmentVariables.set(AZURE_PIPELINE_NUMBER, "azure-pipelines-number")
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
    def decorator = newDecorator()

    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.setTag(Tags.SPAN_KIND, decorator.spanKind())
    1 * span.setSpanType(decorator.spanType())
    1 * span.setTag(Tags.TEST_FRAMEWORK, decorator.testFramework())
    1 * span.setTag(Tags.TEST_TYPE, decorator.testType())
    1 * span.setTag(Tags.CI_PROVIDER_NAME, AZURE_PROVIDER_NAME)
    1 * span.setTag(Tags.CI_PIPELINE_ID, "azure-pipelines-build-id")
    1 * span.setTag(Tags.CI_PIPELINE_NAME, "azure-pipelines-name")
    1 * span.setTag(Tags.CI_PIPELINE_NUMBER, "azure-pipelines-number")
    1 * span.setTag(Tags.CI_PIPELINE_URL, "azure-pipelines-server-uri/azure-pipelines-project/_build/results?buildId=azure-pipelines-build-id&_a=summary")
    1 * span.setTag(Tags.CI_JOB_URL, "azure-pipelines-server-uri/azure-pipelines-project/_build/results?buildId=azure-pipelines-build-id&view=logs&j=azure-pipelines-job-id&t=azure-pipelines-task-id")
    1 * span.setTag(Tags.CI_WORKSPACE_PATH, spanTagWorkspace)
    1 * span.setTag(Tags.BUILD_SOURCE_ROOT, spanTagWorkspace)
    1 * span.setTag(Tags.GIT_REPOSITORY_URL, spanTagRepo)
    1 * span.setTag(Tags.GIT_COMMIT_SHA, spanTagCommit)
    1 * span.setTag(Tags.GIT_BRANCH, spanTagBranch)
    1 * span.setTag(Tags.GIT_TAG, spanTagTag)
    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    _ * span.setSamplingPriority(_)
    0 * _

    where:
    azureWorkspace | spanTagWorkspace      | azureRepo | azurePRRepo | spanTagRepo | azureBranch              | azurePRBranch   | spanTagBranch | spanTagTag | azureCommit | azurePRCommit | spanTagCommit
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"    | "master"                 | null            | "master"      | null       | "commit"    | null          | "commit"
    "foo/bar"      | "foo/bar"             | "sample"  | null        | "sample"    | "origin/master"          | null            | "master"      | null       | "commit"    | null          | "commit"
    "/foo/bar~"    | "/foo/bar~"           | "sample"  | null        | "sample"    | "origin/master"          | null            | "master"      | null       | "commit"    | null          | "commit"
    "/foo/~/bar"   | "/foo/~/bar"          | "sample"  | null        | "sample"    | "origin/master"          | null            | "master"      | null       | "commit"    | null          | "commit"
    "~/foo/bar"    | userHome + "/foo/bar" | "sample"  | null        | "sample"    | "origin/master"          | null            | "master"      | null       | "commit"    | null          | "commit"
    "~foo/bar"     | "~foo/bar"            | "sample"  | null        | "sample"    | "origin/master"          | null            | "master"      | null       | "commit"    | null          | "commit"
    "~"            | userHome              | "sample"  | null        | "sample"    | "origin/master"          | null            | "master"      | null       | "commit"    | null          | "commit"
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"    | "origin/master"          | null            | "master"      | null       | "commit"    | null          | "commit"
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"    | "refs/heads/master"      | null            | "master"      | null       | "commit"    | null          | "commit"
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"    | "refs/heads/feature/one" | null            | "feature/one" | null       | "commit"    | null          | "commit"
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"    | "origin/tags/0.1.0"      | null            | null          | "0.1.0"    | "commit"    | null          | "commit"
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"    | "refs/heads/tags/0.1.0"  | null            | null          | "0.1.0"    | "commit"    | null          | "commit"
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"    | "origin/master"          | "origin/pr"     | "pr"          | null       | "commit"    | "commitPR"    | "commitPR"
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"    | "refs/heads/master"      | "refs/heads/pr" | "pr"          | null       | "commit"    | "commitPR"    | "commitPR"
    "/foo/bar"     | "/foo/bar"            | "sample"  | null        | "sample"    | "refs/heads/feature/one" | "refs/heads/pr" | "pr"          | null       | "commit"    | "commitPR"    | "commitPR"
  }

  def "test afterStart in GitHub Actions"() {
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
    def decorator = newDecorator()

    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.setTag(Tags.SPAN_KIND, decorator.spanKind())
    1 * span.setSpanType(decorator.spanType())
    1 * span.setTag(Tags.TEST_FRAMEWORK, decorator.testFramework())
    1 * span.setTag(Tags.TEST_TYPE, decorator.testType())
    1 * span.setTag(Tags.CI_PROVIDER_NAME, GHACTIONS_PROVIDER_NAME)
    1 * span.setTag(Tags.CI_PIPELINE_ID, "ghactions-pipeline-id")
    1 * span.setTag(Tags.CI_PIPELINE_NAME, "ghactions-pipeline-name")
    1 * span.setTag(Tags.CI_PIPELINE_NUMBER, "ghactions-pipeline-number")
    1 * span.setTag(Tags.CI_PIPELINE_URL, "https://github.com/ghactions-repo/commit/ghactions-commit/checks")
    1 * span.setTag(Tags.CI_JOB_URL, "https://github.com/ghactions-repo/commit/ghactions-commit/checks")
    1 * span.setTag(Tags.CI_WORKSPACE_PATH, spanTagWorkspace)
    1 * span.setTag(Tags.BUILD_SOURCE_ROOT, spanTagWorkspace)
    1 * span.setTag(Tags.GIT_REPOSITORY_URL, "https://github.com/ghactions-repo.git")
    1 * span.setTag(Tags.GIT_COMMIT_SHA, "ghactions-commit")
    1 * span.setTag(Tags.GIT_BRANCH, spanTagBranch)
    1 * span.setTag(Tags.GIT_TAG, spanTagTag)
    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    _ * span.setSamplingPriority(_)
    0 * _

    where:
    ghactionsWorkspace | spanTagWorkspace      | ghactionsBranch          | ghactionsPRBranch          | spanTagBranch   | spanTagTag
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

  def "test afterStart in BitBucket"() {
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
    def decorator = newDecorator()

    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.setTag(Tags.SPAN_KIND, decorator.spanKind())
    1 * span.setSpanType(decorator.spanType())
    1 * span.setTag(Tags.TEST_FRAMEWORK, decorator.testFramework())
    1 * span.setTag(Tags.TEST_TYPE, decorator.testType())
    1 * span.setTag(Tags.CI_PROVIDER_NAME, BITBUCKET_PROVIDER_NAME)
    1 * span.setTag(Tags.CI_PIPELINE_ID, "bitbucket-uuid")
    1 * span.setTag(Tags.CI_PIPELINE_NAME, "bitbucket-repo")
    1 * span.setTag(Tags.CI_PIPELINE_NUMBER, "bitbucket-build-num")
    1 * span.setTag(Tags.CI_PIPELINE_URL, "https://bitbucket.org/bitbucket-repo/addon/pipelines/home#!/results/bitbucket-build-num")
    1 * span.setTag(Tags.CI_JOB_URL, "https://bitbucket.org/bitbucket-repo/addon/pipelines/home#!/results/bitbucket-build-num")
    1 * span.setTag(Tags.CI_WORKSPACE_PATH, spanTagWorkspace)
    1 * span.setTag(Tags.BUILD_SOURCE_ROOT, spanTagWorkspace)
    1 * span.setTag(Tags.GIT_REPOSITORY_URL, "bitbucket-repo-url")
    1 * span.setTag(Tags.GIT_COMMIT_SHA, "bitbucket-commit")
    1 * span.setTag(Tags.GIT_BRANCH, spanTagBranch)
    1 * span.setTag(Tags.GIT_TAG, spanTagTag)
    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    _ * span.setSamplingPriority(_)
    0 * _

    where:
    bibucketWorkspace | spanTagWorkspace      | bitbucketBranch          | bitbucketTag            | spanTagBranch | spanTagTag
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

  def "test afterStart in Buildkite"() {
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
    def decorator = newDecorator()

    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.setTag(Tags.SPAN_KIND, decorator.spanKind())
    1 * span.setSpanType(decorator.spanType())
    1 * span.setTag(Tags.TEST_FRAMEWORK, decorator.testFramework())
    1 * span.setTag(Tags.TEST_TYPE, decorator.testType())
    1 * span.setTag(Tags.CI_PROVIDER_NAME, BUILDKITE_PROVIDER_NAME)
    1 * span.setTag(Tags.CI_PIPELINE_ID, "buildkite-pipeline-id")
    1 * span.setTag(Tags.CI_PIPELINE_NAME, "buildkite-pipeline-name")
    1 * span.setTag(Tags.CI_PIPELINE_NUMBER, "buildkite-pipeline-number")
    1 * span.setTag(Tags.CI_PIPELINE_URL, "buildkite-build-url")
    1 * span.setTag(Tags.CI_JOB_URL, "buildkite-build-url#buildkite-job-id")
    1 * span.setTag(Tags.CI_WORKSPACE_PATH, spanTagWorkspace)
    1 * span.setTag(Tags.BUILD_SOURCE_ROOT, spanTagWorkspace)
    1 * span.setTag(Tags.GIT_REPOSITORY_URL, spanTagRepository)
    1 * span.setTag(Tags.GIT_COMMIT_SHA, "buildkite-git-commit")
    1 * span.setTag(Tags.GIT_BRANCH, spanTagBranch)
    1 * span.setTag(Tags.GIT_TAG, spanTagTag)
    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    _ * span.setSamplingPriority(_)
    0 * _

    where:
    buildkiteWorkspace | spanTagWorkspace      | buildkiteRepoURL                             | spanTagRepository               | buildkiteBranch          | buildkiteTag            | spanTagBranch | spanTagTag
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

  def "test afterStart in Appveyor"() {
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

    def decorator = newDecorator()

    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.setTag(Tags.SPAN_KIND, decorator.spanKind())
    1 * span.setSpanType(decorator.spanType())
    1 * span.setTag(Tags.TEST_FRAMEWORK, decorator.testFramework())
    1 * span.setTag(Tags.TEST_TYPE, decorator.testType())
    1 * span.setTag(Tags.CI_PROVIDER_NAME, APPVEYOR_PROVIDER_NAME)
    1 * span.setTag(Tags.CI_PIPELINE_ID, "appveyor-build-id")
    1 * span.setTag(Tags.CI_PIPELINE_NAME, "appveyor-repo-name")
    1 * span.setTag(Tags.CI_PIPELINE_NUMBER, "appveyor-pipeline-number")
    1 * span.setTag(Tags.CI_PIPELINE_URL, "https://ci.appveyor.com/project/appveyor-repo-name/builds/appveyor-build-id")
    1 * span.setTag(Tags.CI_JOB_URL, "https://ci.appveyor.com/project/appveyor-repo-name/builds/appveyor-build-id")
    1 * span.setTag(Tags.CI_WORKSPACE_PATH, spanTagWorkspace)
    1 * span.setTag(Tags.BUILD_SOURCE_ROOT, spanTagWorkspace)
    1 * span.setTag(Tags.GIT_REPOSITORY_URL, spanTagRepository)
    1 * span.setTag(Tags.GIT_COMMIT_SHA, spanTagCommit)
    1 * span.setTag(Tags.GIT_BRANCH, spanTagBranch)
    1 * span.setTag(Tags.GIT_TAG, spanTagTag)
    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    _ * span.setSamplingPriority(_)
    0 * _

    where:
    appveyorWorkspace | spanTagWorkspace      | appveyorProvider | appveyorBranch           | appveyorPRBranch | appveyorTag             | spanTagRepository                           | spanTagCommit          | spanTagBranch | spanTagTag
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

  def "test beforeFinish"() {
    when:
    newDecorator().beforeFinish(span)

    then:
    0 * _
  }

  @Override
  def newDecorator() {
    return new TestDecorator() {
      @Override
      protected String testFramework() {
        return "test-framework"
      }

      @Override
      protected String[] instrumentationNames() {
        return ["test1", "test2"]
      }

      @Override
      protected CharSequence spanType() {
        return "test-type"
      }

      @Override
      protected String spanKind() {
        return "test-type"
      }

      @Override
      protected CharSequence component() {
        return "test-component"
      }
    }
  }
}
