package datadog.trace.api

import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.GeneralConfig.ENV
import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED
import static datadog.trace.api.config.GeneralConfig.PRIMARY_TAG
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME

class BaseHashTest extends DDSpecification {

  def "Service used in hash calculation"() {
    when:
    def firstBaseHash = BaseHash.calc(null)

    injectSysConfig(SERVICE_NAME, "service-1")
    def secondBaseHash = BaseHash.calc(null)

    then:
    firstBaseHash != secondBaseHash
  }

  def "Env used in hash calculation"() {
    when:
    def firstBaseHash = BaseHash.calc(null)

    injectSysConfig(ENV, "env-1")
    def secondBaseHash = BaseHash.calc(null)

    then:
    firstBaseHash != secondBaseHash
  }

  def "Primary tag used in hash calculation"() {
    when:
    def firstBaseHash = BaseHash.calc(null)

    injectSysConfig(PRIMARY_TAG, "region-2")
    def secondBaseHash = BaseHash.calc(null)

    then:
    firstBaseHash != secondBaseHash
  }

  def "Process Tags used in hash calculation"() {
    when:
    def firstBaseHash = BaseHash.calc(null)

    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "true")
    ProcessTags.reset()
    ProcessTags.addTag("000", "first")
    def secondBaseHash = BaseHash.calc(null)

    then:
    firstBaseHash != secondBaseHash
    assert ProcessTags.getTagsForSerialization().startsWithAny("000:first,")
    cleanup:
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false")
    ProcessTags.reset()
  }

  def "ContainerTagsHash used in hash calculation when provided"() {
    when:
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, propagateTagsEnabled.toString())
    ProcessTags.reset()
    ProcessTags.addTag("000", "first")
    def firstBaseHash = BaseHash.calc(null)

    then:
    def secondBaseHash = BaseHash.calc("<test-container-tags-hash>")

    expect:
    if (propagateTagsEnabled) {
      assert secondBaseHash != firstBaseHash
    } else {
      assert secondBaseHash == firstBaseHash
    }

    cleanup:
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false")
    ProcessTags.reset()

    where:
    propagateTagsEnabled << [true, false]
  }
}
