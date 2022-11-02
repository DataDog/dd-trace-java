package datadog.trace.api.config

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.ConfigTest.PREFIX
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_AGENTLESS_URL
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_ENABLED
import static datadog.trace.api.config.CiVisibilityConfig.DEFAULT_CIVISIBILITY_AGENTLESS_ENABLED
import static datadog.trace.api.config.CiVisibilityConfig.DEFAULT_CIVISIBILITY_ENABLED

class CiVisibilityConfigTest extends DDSpecification {
  def "check default config values"() {
    when:
    def config = new Config()

    then:
    config.ciVisibilityEnabled == DEFAULT_CIVISIBILITY_ENABLED
    config.ciVisibilityAgentlessEnabled == DEFAULT_CIVISIBILITY_AGENTLESS_ENABLED
    config.ciVisibilityAgentlessUrl == null
  }

  def "check overridden config values"() {
    setup:
    def url = "http://localhost/route"
    System.setProperty(PREFIX + CIVISIBILITY_ENABLED, "true")
    System.setProperty(PREFIX + CIVISIBILITY_AGENTLESS_ENABLED, "true")
    System.setProperty(PREFIX + CIVISIBILITY_AGENTLESS_URL, url)

    when:
    def config = new Config()

    then:
    config.ciVisibilityEnabled
    config.ciVisibilityAgentlessEnabled
    config.ciVisibilityAgentlessUrl == url
  }

  def "check invalid URL parsing"() {
    setup:
    def url = "not-an-url"
    System.setProperty(PREFIX + CIVISIBILITY_AGENTLESS_URL, url)

    when:
    def config = new Config()

    then:
    config.ciVisibilityAgentlessUrl == null
  }
}
