package datadog.trace.api

import static datadog.trace.api.config.GeneralConfig.ENV
import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED
import static datadog.trace.api.config.GeneralConfig.PRIMARY_TAG
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME

import datadog.trace.test.util.DDSpecification

class BaseHashTest extends DDSpecification {


  def setup() {
    // start with fresh process tags
    ProcessTags.reset()
  }

  def cleanup() {
    // restore the default enablement
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "true")
    ProcessTags.reset()
  }

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

    ProcessTags.addTag("000", "first")
    def secondBaseHash = BaseHash.calc(null)

    then:
    firstBaseHash != secondBaseHash
    assert ProcessTags.getTagsForSerialization().startsWithAny("000:first,")
  }

  def "addTag triggers BaseHash recalculation"() {
    given:
    BaseHash.recalcBaseHash("container-hash-1")
    def hashBefore = BaseHash.getBaseHash()

    when:
    ProcessTags.addTag("cluster.name", "new-cluster")

    then:
    BaseHash.getBaseHash() != hashBefore
  }

  def "recalcBaseHash preserves last containerTagsHash across ProcessTags changes"() {
    given:
    def containerHash = "my-container-hash"
    BaseHash.recalcBaseHash(containerHash)
    def hashWithContainerTag = BaseHash.getBaseHash()

    when: "a process tag is added"
    ProcessTags.addTag("cluster.name", "new-cluster")

    then: "hash differs from before the tag was added"
    BaseHash.getBaseHash() != hashWithContainerTag

    and: "hash equals a fresh calc with the same container hash"
    BaseHash.getBaseHash() == BaseHash.calc(containerHash)
  }

  def "addTag does not recalculate BaseHash when ProcessTags disabled"() {
    setup:
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false")
    ProcessTags.reset()
    BaseHash.recalcBaseHash(null)
    def hashBefore = BaseHash.getBaseHash()

    when:
    ProcessTags.addTag("cluster.name", "ignored")

    then:
    BaseHash.getBaseHash() == hashBefore
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

    where:
    propagateTagsEnabled << [true, false]
  }
}
