package datadog.trace.civisibility.ipc

import datadog.trace.civisibility.config.JvmInfo
import spock.lang.Specification

import java.nio.file.Paths

class ModuleSettingsRequestTest extends Specification {

  def "test serialization and deserialization: #signal"() {
    when:
    def bytes = signal.serialize()
    def deserialized = ExecutionSettingsRequest.deserialize(bytes)

    then:
    deserialized == signal

    where:
    signal << [
      new ExecutionSettingsRequest(Paths.get("").toString(), JvmInfo.CURRENT_JVM),
      new ExecutionSettingsRequest(null, JvmInfo.CURRENT_JVM),
      new ExecutionSettingsRequest("abc", new JvmInfo("abc", "def", "ghi")),
      new ExecutionSettingsRequest("abc", new JvmInfo("", "def", "ghi")),
      new ExecutionSettingsRequest("abc", new JvmInfo("abc", "", "ghi")),
      new ExecutionSettingsRequest("abc", new JvmInfo("abc", "def", "")),
      new ExecutionSettingsRequest("abc", new JvmInfo("", "", "")),
      new ExecutionSettingsRequest("abc", new JvmInfo(null, "def", "ghi")),
      new ExecutionSettingsRequest("abc", new JvmInfo("abc", null, "ghi")),
      new ExecutionSettingsRequest("abc", new JvmInfo("abc", "def", null)),
      new ExecutionSettingsRequest("abc", new JvmInfo(null, null, null)),
    ]
  }
}
