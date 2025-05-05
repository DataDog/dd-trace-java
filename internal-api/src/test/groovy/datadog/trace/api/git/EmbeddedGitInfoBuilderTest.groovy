package datadog.trace.api.git


import spock.lang.Specification

class EmbeddedGitInfoBuilderTest extends Specification {

  def "test no embedded git info"() {
    when:
    def gitInfo = new EmbeddedGitInfoBuilder(["non-existent-git.properties"]).build(null)

    then:
    gitInfo.isEmpty()
  }

  def "test maven-plugin-generated git info"() {
    when:
    def mavenGitProperties = "datadog/trace/bootstrap/git/maven-git.properties"
    def gitInfo = new EmbeddedGitInfoBuilder([mavenGitProperties]).build(null)

    then:
    gitInfo.repositoryURL == "git@github.com:DataDog/ciapp-test-resources.git"
    gitInfo.branch == "master"
    gitInfo.tag == "test_tag"
    gitInfo.commit.sha == "847416a0f14356609ef2a2e0bf19b8a7222c250a"
    gitInfo.commit.fullMessage == "Add integration test in Java Gradle JUnit 5 project"
    gitInfo.commit.author.name == "Nikita Tkachenko"
    gitInfo.commit.author.email == "nikita.tkachenko@datadoghq.com"
    gitInfo.commit.author.iso8601Date == "2023-03-22T14:43:22+0100"
    gitInfo.commit.committer.name == "Nikita Tkachenko"
    gitInfo.commit.committer.email == "nikita.tkachenko@datadoghq.com"
    gitInfo.commit.committer.iso8601Date == "2023-03-22T14:43:23+0100"
  }

  def "test gradle-plugin-generated git info"() {
    when:
    def gradleGitProperties = "datadog/trace/bootstrap/git/gradle-git.properties"
    def gitInfo = new EmbeddedGitInfoBuilder([gradleGitProperties]).build(null)

    then:
    gitInfo.repositoryURL == "git@github.com:DataDog/ciapp-test-resources.git"
    gitInfo.branch == "master"
    gitInfo.tag == "test_tag"
    gitInfo.commit.sha == "847416a0f14356609ef2a2e0bf19b8a7222c250a"
    gitInfo.commit.fullMessage == "Add integration test in Java Gradle JUnit 5 project\n"
    gitInfo.commit.author.name == "Nikita Tkachenko"
    gitInfo.commit.author.email == "nikita.tkachenko@datadoghq.com"
    gitInfo.commit.author.iso8601Date == "2023-03-22T14:43:21+0100"
    gitInfo.commit.committer.name == "Nikita Tkachenko"
    gitInfo.commit.committer.email == "nikita.tkachenko@datadoghq.com"
    gitInfo.commit.committer.iso8601Date == "2023-03-22T14:43:21+0100"
  }

  def "test embedded gitinfo has a lower priority than user supplied gitinfo"() {
    when:
    def embeddedGitInfoBuilder = new EmbeddedGitInfoBuilder()
    def userSuppliedGitInfoBuilder = new UserSuppliedGitInfoBuilder()

    then:
    embeddedGitInfoBuilder.order() > userSuppliedGitInfoBuilder.order()
  }
}
