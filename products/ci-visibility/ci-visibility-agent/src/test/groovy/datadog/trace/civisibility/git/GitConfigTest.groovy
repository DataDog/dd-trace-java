package datadog.trace.civisibility.git


import datadog.trace.test.util.DDSpecification

import java.nio.file.Paths

class GitConfigTest extends DDSpecification {

  def "test parsing the git config file"() {
    setup:
    def gitConfig = new GitConfig(gitConfigPath)

    when:
    def value = gitConfig.getString(section, key)

    then:
    value == expectedValue

    where:
    gitConfigPath                      | section             | key   | expectedValue
    null                               | null                | null  | null
    ""                                 | null                | null  | null
    resolve("ci/git/empty_config")     | null                | null  | null
    resolve("ci/git/initial_config")   | "core"              | null  | null
    resolve("ci/git/with_repo_config") | "remote \"origin\"" | "url" | "https://some-host/user/repository.git"
  }

  def "resolve"(workspace) {
    def resolvedWS = Paths.get(getClass().getClassLoader().getResource(workspace + "/config").toURI()).toFile().getAbsolutePath()
    println(resolvedWS)
    return resolvedWS
  }
}
