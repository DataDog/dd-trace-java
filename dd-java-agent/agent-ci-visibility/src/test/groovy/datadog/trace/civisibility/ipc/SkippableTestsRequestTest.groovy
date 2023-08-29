package datadog.trace.civisibility.ipc

import datadog.trace.civisibility.config.JvmInfo
import spock.lang.Specification

import java.nio.file.Paths

class SkippableTestsRequestTest extends Specification {

  def "test serialization and deserialization: #signal"() {
    when:
    def bytes = signal.serialize()
    def deserialized = SkippableTestsRequest.deserialize(bytes)

    then:
    deserialized == signal

    where:
    signal << [
      new SkippableTestsRequest(Paths.get("").toString(), JvmInfo.CURRENT_JVM),
      new SkippableTestsRequest(null, JvmInfo.CURRENT_JVM),
      new SkippableTestsRequest("abc", new JvmInfo("abc", "def", "ghi")),
      new SkippableTestsRequest("abc", new JvmInfo("", "def", "ghi")),
      new SkippableTestsRequest("abc", new JvmInfo("abc", "", "ghi")),
      new SkippableTestsRequest("abc", new JvmInfo("abc", "def", "")),
      new SkippableTestsRequest("abc", new JvmInfo("", "", "")),
      new SkippableTestsRequest("abc", new JvmInfo(null, "def", "ghi")),
      new SkippableTestsRequest("abc", new JvmInfo("abc", null, "ghi")),
      new SkippableTestsRequest("abc", new JvmInfo("abc", "def", null)),
      new SkippableTestsRequest("abc", new JvmInfo(null, null, null)),
    ]
  }
}
