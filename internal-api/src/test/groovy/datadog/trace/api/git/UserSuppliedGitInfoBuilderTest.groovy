package datadog.trace.api.git

import datadog.trace.api.config.GeneralConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.ConfigStrings

class UserSuppliedGitInfoBuilderTest extends DDSpecification {
  def "test no user supplied git info"() {
    when:
    def gitInfo = new UserSuppliedGitInfoBuilder().build(null)

    then:
    gitInfo.isEmpty()
  }

  def "user supplied git info: env var #envVariable"() {
    setup:
    environmentVariables.set(ConfigStrings.propertyNameToEnvironmentVariableName(envVariable), value)

    when:
    def gitInfo = new UserSuppliedGitInfoBuilder().build(null)

    then:
    !gitInfo.isEmpty()
    gitInfoValueProvider.call(gitInfo) == value

    where:
    envVariable                                              | value                      | gitInfoValueProvider
    UserSuppliedGitInfoBuilder.DD_GIT_REPOSITORY_URL         | "git repo URL"             | { it.repositoryURL }
    UserSuppliedGitInfoBuilder.DD_GIT_BRANCH                 | "git branch"               | { it.branch }
    UserSuppliedGitInfoBuilder.DD_GIT_TAG                    | "git tag"                  | { it.tag }
    UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_SHA             | "commit SHA"               | { it.commit.sha }
    UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_MESSAGE         | "commit message"           | { it.commit.fullMessage }
    UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_AUTHOR_NAME     | "commit author"            | { it.commit.author.name }
    UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_AUTHOR_EMAIL    | "commit author mail"       | { it.commit.author.email }
    UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_AUTHOR_DATE     | "2022-12-29T11:38:44.254Z" | { it.commit.author.iso8601Date }
    UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_COMMITTER_NAME  | "committer"                | { it.commit.committer.name }
    UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_COMMITTER_EMAIL | "committer mail"           | { it.commit.committer.email }
    UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_COMMITTER_DATE  | "2022-12-29T10:38:44.254Z" | { it.commit.committer.iso8601Date }
  }

  def "user supplied git info: system property #systemProperty"() {
    setup:
    System.setProperty(ConfigStrings.propertyNameToSystemPropertyName(systemProperty), value)

    when:
    def gitInfo = new UserSuppliedGitInfoBuilder().build(null)

    then:
    !gitInfo.isEmpty()
    gitInfoValueProvider.call(gitInfo) == value

    where:
    systemProperty                                           | value                      | gitInfoValueProvider
    UserSuppliedGitInfoBuilder.DD_GIT_REPOSITORY_URL         | "git repo URL"             | { it.repositoryURL }
    UserSuppliedGitInfoBuilder.DD_GIT_BRANCH                 | "git branch"               | { it.branch }
    UserSuppliedGitInfoBuilder.DD_GIT_TAG                    | "git tag"                  | { it.tag }
    UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_SHA             | "commit SHA"               | { it.commit.sha }
    UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_MESSAGE         | "commit message"           | { it.commit.fullMessage }
    UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_AUTHOR_NAME     | "commit author"            | { it.commit.author.name }
    UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_AUTHOR_EMAIL    | "commit author mail"       | { it.commit.author.email }
    UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_AUTHOR_DATE     | "2022-12-29T11:38:44.254Z" | { it.commit.author.iso8601Date }
    UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_COMMITTER_NAME  | "committer"                | { it.commit.committer.name }
    UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_COMMITTER_EMAIL | "committer mail"           | { it.commit.committer.email }
    UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_COMMITTER_DATE  | "2022-12-29T10:38:44.254Z" | { it.commit.committer.iso8601Date }
  }

  def "branch name is normalized"() {
    setup:
    environmentVariables.set(ConfigStrings.propertyNameToEnvironmentVariableName(UserSuppliedGitInfoBuilder.DD_GIT_BRANCH), "origin/myBranch")

    when:
    def gitInfo = new UserSuppliedGitInfoBuilder().build(null)

    then:
    !gitInfo.isEmpty()
    gitInfo.branch == "myBranch"
  }

  def "tag can be supplied in branch var"() {
    setup:
    environmentVariables.set(ConfigStrings.propertyNameToEnvironmentVariableName(UserSuppliedGitInfoBuilder.DD_GIT_BRANCH), "refs/tags/myTag")

    when:
    def gitInfo = new UserSuppliedGitInfoBuilder().build(null)

    then:
    !gitInfo.isEmpty()
    gitInfo.branch == null
    gitInfo.tag == "myTag"
  }

  def "dedicated tag var has preference over tag supplied inside branch var"() {
    setup:
    environmentVariables.set(ConfigStrings.propertyNameToEnvironmentVariableName(UserSuppliedGitInfoBuilder.DD_GIT_TAG), "myProvidedTag")
    environmentVariables.set(ConfigStrings.propertyNameToEnvironmentVariableName(UserSuppliedGitInfoBuilder.DD_GIT_BRANCH), "refs/tags/myTag")

    when:
    def gitInfo = new UserSuppliedGitInfoBuilder().build(null)

    then:
    !gitInfo.isEmpty()
    gitInfo.branch == null
    gitInfo.tag == "myProvidedTag"
  }

  def "git info is extracted from global tags"() {
    setup:
    injectEnvConfig(ConfigStrings.toEnvVar(GeneralConfig.TAGS), Tags.GIT_REPOSITORY_URL + ":repo_url," + Tags.GIT_COMMIT_SHA + ":commit_sha")

    when:
    def gitInfo = new UserSuppliedGitInfoBuilder().build(null)

    then:
    !gitInfo.isEmpty()
    gitInfo.repositoryURL == "repo_url"
    gitInfo.commit.sha == "commit_sha"
  }

  def "global tags have lower priority than dedicated environment variables"() {
    setup:
    injectEnvConfig(ConfigStrings.toEnvVar(GeneralConfig.TAGS), Tags.GIT_REPOSITORY_URL + ":repo_url," + Tags.GIT_COMMIT_SHA + ":commit_sha")
    injectEnvConfig(ConfigStrings.propertyNameToEnvironmentVariableName(UserSuppliedGitInfoBuilder.DD_GIT_REPOSITORY_URL), "overridden_repo_url")
    injectEnvConfig(ConfigStrings.propertyNameToEnvironmentVariableName(UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_SHA), "overridden_commit_sha")

    when:
    def gitInfo = new UserSuppliedGitInfoBuilder().build(null)

    then:
    !gitInfo.isEmpty()
    gitInfo.repositoryURL == "overridden_repo_url"
    gitInfo.commit.sha == "overridden_commit_sha"
  }
}
