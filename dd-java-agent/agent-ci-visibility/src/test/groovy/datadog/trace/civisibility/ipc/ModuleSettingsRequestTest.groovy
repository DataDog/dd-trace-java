package datadog.trace.civisibility.ipc

import datadog.trace.civisibility.config.JvmInfo
import spock.lang.Specification

import java.nio.file.Paths

class ModuleSettingsRequestTest extends Specification {

  def "test serialization and deserialization: #signal"() {
    when:
    def bytes = signal.serialize()
    def deserialized = ModuleSettingsRequest.deserialize(bytes)

    then:
    deserialized == signal

    where:
    signal << [
      new ModuleSettingsRequest(Paths.get("").toString(), JvmInfo.CURRENT_JVM),
      new ModuleSettingsRequest(null, JvmInfo.CURRENT_JVM),
      new ModuleSettingsRequest("abc", new JvmInfo("abc", "def", "ghi")),
      new ModuleSettingsRequest("abc", new JvmInfo("", "def", "ghi")),
      new ModuleSettingsRequest("abc", new JvmInfo("abc", "", "ghi")),
      new ModuleSettingsRequest("abc", new JvmInfo("abc", "def", "")),
      new ModuleSettingsRequest("abc", new JvmInfo("", "", "")),
      new ModuleSettingsRequest("abc", new JvmInfo(null, "def", "ghi")),
      new ModuleSettingsRequest("abc", new JvmInfo("abc", null, "ghi")),
      new ModuleSettingsRequest("abc", new JvmInfo("abc", "def", null)),
      new ModuleSettingsRequest("abc", new JvmInfo(null, null, null)),
    ]
  }
}
