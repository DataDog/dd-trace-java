package datadog.trace.civisibility.domain

import static datadog.trace.civisibility.domain.SpanTagsPropagator.TagMergeSpec

import datadog.trace.api.civisibility.execution.TestStatus
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.ipc.TestFramework
import datadog.trace.core.DDSpan
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import spock.lang.Specification

class SpanTagsPropagatorTest extends Specification {
  def "test getFrameworks"() {
    when:
    def span = Stub(DDSpan)
    span.getTag(Tags.TEST_FRAMEWORK) >> frameworkTag
    span.getTag(Tags.TEST_FRAMEWORK_VERSION) >> frameworkVersionTag

    def frameworks = SpanTagsPropagator.getFrameworks(span)

    then:
    frameworks == expected

    where:
    frameworkTag       | frameworkVersionTag      | expected
    "name"             | "version"                | [new TestFramework("name", "version")]
    "name"             | null                     | [new TestFramework("name", null)]
    null               | "version"                | []
    ["nameA", "nameB"] | ["versionA", "versionB"] | [new TestFramework("nameA", "versionA"), new TestFramework("nameB", "versionB")]
    ["nameA", "nameB"] | null                     | [new TestFramework("nameA", null), new TestFramework("nameB", null)]
    ["nameA", "nameB"] | ["versionA", null]       | [new TestFramework("nameA", "versionA"), new TestFramework("nameB", null)]
  }

  def "test status propagation: #childStatus to #parentStatus"() {
    given:
    def parentSpan = Mock(DDSpan)
    parentSpan.getTag(Tags.TEST_STATUS) >> parentStatus

    def childSpan = Mock(DDSpan)
    childSpan.getTag(Tags.TEST_STATUS) >> childStatus

    def propagator = new SpanTagsPropagator(parentSpan)

    when:
    propagator.propagateStatus(childSpan)

    then:
    if (expectedStatus != null) {
      1 * parentSpan.setTag(Tags.TEST_STATUS, expectedStatus)
    } else {
      0 * parentSpan.setTag(Tags.TEST_STATUS, _)
    }

    where:
    childStatus     | parentStatus    | expectedStatus
    TestStatus.pass | null            | TestStatus.pass
    TestStatus.pass | TestStatus.skip | TestStatus.pass
    TestStatus.pass | TestStatus.pass | null // no change
    TestStatus.pass | TestStatus.fail | null // no change
    TestStatus.fail | null            | TestStatus.fail
    TestStatus.fail | TestStatus.pass | TestStatus.fail
    TestStatus.fail | TestStatus.skip | TestStatus.fail
    TestStatus.fail | TestStatus.fail | TestStatus.fail
    TestStatus.skip | null            | TestStatus.skip
    TestStatus.skip | TestStatus.pass | null // no change
    TestStatus.skip | TestStatus.fail | null // no change
    TestStatus.skip | TestStatus.skip | null // no change
    null            | TestStatus.pass | null // no change
  }

  def "test framework merging: #childFrameworks and #parentFrameworks"() {
    given:
    def parentSpan = Mock(DDSpan)
    parentSpan.getTag(Tags.TEST_FRAMEWORK) >> parentFrameworks.collect(it -> it.getName())
    parentSpan.getTag(Tags.TEST_FRAMEWORK_VERSION) >> parentFrameworks.collect(it -> it.getVersion())

    def propagator = new SpanTagsPropagator(parentSpan)

    def expectedNames = expectedFrameworks.collect(it -> it.getName())
    def expectedVersions = expectedFrameworks.collect(it -> it.getVersion())

    when:
    propagator.mergeTestFrameworks(childFrameworks)

    then:
    1 * parentSpan.setAllTags([
      (Tags.TEST_FRAMEWORK)        : expectedNames,
      (Tags.TEST_FRAMEWORK_VERSION): expectedVersions
    ])

    where:
    childFrameworks                                                             | parentFrameworks                                                            | expectedFrameworks
    []                                                                          | [new TestFramework("JUnit", "5.8.0"), new TestFramework("TestNG", "7.4.0")] | [new TestFramework("JUnit", "5.8.0"), new TestFramework("TestNG", "7.4.0")]
    [new TestFramework("JUnit", "5.8.0"), new TestFramework("TestNG", "7.4.0")] | []                                                                          | [new TestFramework("JUnit", "5.8.0"), new TestFramework("TestNG", "7.4.0")]
    [new TestFramework("JUnit", "5.8.0"), new TestFramework("TestNG", "7.4.0")] | [new TestFramework("Spock", "2.3")]                                         | [new TestFramework("JUnit", "5.8.0"), new TestFramework("Spock", "2.3"), new TestFramework("TestNG", "7.4.0")]
  }

  def "test tag propagation: #childValue and #parentValue with spec #tagSpec"() {
    given:
    def parentSpan = Mock(DDSpan)
    parentSpan.getTag("tag") >> parentValue

    def childSpan = Mock(DDSpan)
    childSpan.getTag("tag") >> childValue

    def propagator = new SpanTagsPropagator(parentSpan)

    when:
    propagator.propagateTags(childSpan, tagSpec)

    then:
    if (expectedChange) {
      1 * parentSpan.setTag("tag", expectedValue)
    } else {
      0 * parentSpan.setTag("tag", _)
    }

    where:
    tagSpec                                    | childValue | parentValue | expectedChange | expectedValue
    TagMergeSpec.of("tag")                     | "a"        | "b"         | true           | "a"
    TagMergeSpec.of("tag")                     | null       | "b"         | false          | "b"
    TagMergeSpec.of("tag")                     | null       | null        | false          | null
    TagMergeSpec.of("tag", Boolean::logicalOr) | true       | false       | true           | true
    TagMergeSpec.of("tag", Boolean::logicalOr) | false      | false       | true           | false
  }

  // Mocks AgentSpan (interface) rather than DDSpan because propagateCustomTags writes through
  // the final DDSpan#setTag(String, String) overload, which Spock cannot intercept on a class mock.
  def "test custom tag propagation from span: child=#childValue, parent=#parentValue, key=#key, allowlist=#allowlist"() {
    given:
    def parentSpan = Mock(AgentSpan)
    parentSpan.getTag(key) >> parentValue

    def childSpan = Mock(AgentSpan)
    childSpan.getTag(key) >> childValue

    def propagator = new SpanTagsPropagator(parentSpan, allowlist)

    when:
    propagator.propagateCustomTags(childSpan)

    then:
    if (expectedValue != null) {
      1 * parentSpan.setTag(key, expectedValue)
    } else {
      0 * parentSpan.setTag(key, _)
    }

    where:
    allowlist                | key                  | childValue | parentValue | expectedValue
    ["bazel.shard_index"]    | "bazel.shard_index"  | "0"        | null        | "0"
    ["bazel.shard_index"]    | "bazel.shard_index"  | "1"        | "0"         | "1"  // child overrides parent
    ["bazel.shard_index"]    | "bazel.shard_index"  | null       | "0"         | null // missing on child, no-op
    ["bazel.shard_index"]    | "bazel.total_shards" | "2"        | null        | null // not in allowlist
    []                       | "bazel.shard_index"  | "0"        | null        | null // empty allowlist
    null                     | "bazel.shard_index"  | "0"        | null        | null // null allowlist
    ["bazel.shard_index"]    | "bazel.shard_index"  | 0L         | null        | "0"  // non-string child stringified
    ["bazel.shard_index"]    | "bazel.shard_index"  | true       | null        | "true" // boolean stringified
  }

  def "test custom tag propagation from map: allowlist=#allowlist, tags=#tags"() {
    given:
    def parentSpan = Mock(AgentSpan)
    def propagator = new SpanTagsPropagator(parentSpan, allowlist)

    when:
    propagator.propagateCustomTags(tags)

    then:
    expectedSets * parentSpan.setTag(_, _)

    where:
    allowlist                                       | tags                                                  | expectedSets
    ["bazel.shard_index", "bazel.total_shards"]     | ["bazel.shard_index": "0", "bazel.total_shards": "2"] | 2
    ["bazel.shard_index"]                           | ["bazel.shard_index": "0"]                            | 1
    ["bazel.shard_index"]                           | ["bazel.shard_index": "0", "bazel.total_shards": "2"] | 1   // only allowlisted keys are copied
    ["bazel.shard_index"]                           | [:]                                                   | 0   // empty tags
    []                                              | ["bazel.shard_index": "0"]                            | 0   // empty allowlist
    null                                            | ["bazel.shard_index": "0"]                            | 0   // null allowlist
  }

  def "test synchronized propagation"() {
    given:
    def parentSpan = Mock(DDSpan)
    def propagator = new SpanTagsPropagator(parentSpan)
    def numThreads = 9
    def latch = new CountDownLatch(numThreads)
    def executor = Executors.newFixedThreadPool(numThreads)
    def exceptions = Collections.synchronizedList([])

    when:
    numThreads.times { i ->
      executor.submit {
        try {
          switch (i % 3) {
            case 0:
            def childSpan = Mock(DDSpan)
            childSpan.getTag(Tags.TEST_STATUS) >> TestStatus.fail
            propagator.propagateStatus(childSpan)
            break
            case 1:
            def frameworks = [new TestFramework("JUnit${i}", "5.${i}")]
            propagator.mergeTestFrameworks(frameworks)
            break
            case 2:
            def childSpan = Mock(DDSpan)
            childSpan.getTag("custom.tag.${i}") >> "value${i}"
            propagator.propagateTags(childSpan, TagMergeSpec.of("custom.tag.${i}"))
            break
          }
        } catch (Exception e) {
          exceptions.add(e)
        } finally {
          latch.countDown()
        }
      }
    }

    latch.await(5, TimeUnit.SECONDS)
    executor.shutdown()

    then:
    exceptions.isEmpty()
    3 * parentSpan.setTag(Tags.TEST_STATUS, TestStatus.fail)
    3 * parentSpan.setAllTags(_)
    3 * parentSpan.setTag({ it.startsWith("custom.tag.") }, { it.startsWith("value") })
  }
}
