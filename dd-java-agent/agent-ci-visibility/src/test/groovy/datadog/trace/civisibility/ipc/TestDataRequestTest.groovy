package datadog.trace.civisibility.ipc

import datadog.trace.civisibility.config.JvmInfo
import spock.lang.Specification

import java.nio.file.Paths

class TestDataRequestTest extends Specification {

  def "test serialization and deserialization: #signal"() {
    when:
    def bytes = signal.serialize()
    def deserialized = TestDataRequest.deserialize(bytes)

    then:
    deserialized == signal

    where:
    signal << [
      new TestDataRequest(TestDataType.SKIPPABLE, Paths.get("").toString(), JvmInfo.CURRENT_JVM),
      new TestDataRequest(TestDataType.FLAKY, null, JvmInfo.CURRENT_JVM),
      new TestDataRequest(TestDataType.SKIPPABLE, "abc", new JvmInfo("abc", "def", "ghi")),
      new TestDataRequest(TestDataType.FLAKY, "abc", new JvmInfo("", "def", "ghi")),
      new TestDataRequest(TestDataType.SKIPPABLE, "abc", new JvmInfo("abc", "", "ghi")),
      new TestDataRequest(TestDataType.FLAKY, "abc", new JvmInfo("abc", "def", "")),
      new TestDataRequest(TestDataType.SKIPPABLE, "abc", new JvmInfo("", "", "")),
      new TestDataRequest(TestDataType.FLAKY, "abc", new JvmInfo(null, "def", "ghi")),
      new TestDataRequest(TestDataType.SKIPPABLE, "abc", new JvmInfo("abc", null, "ghi")),
      new TestDataRequest(TestDataType.FLAKY, "abc", new JvmInfo("abc", "def", null)),
      new TestDataRequest(TestDataType.SKIPPABLE, "abc", new JvmInfo(null, null, null)),
    ]
  }
}
