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
      new ModuleSettingsRequest("abc", new JvmInfo("abc", "def", "52.0", "123", "456")),
      new ModuleSettingsRequest("abc", new JvmInfo("", "def", "61.0", "", "")),
      new ModuleSettingsRequest("abc", new JvmInfo("abc", "", "ghi", null, null)),
      new ModuleSettingsRequest("abc", new JvmInfo("abc", "def", "", "123", "456")),
      new ModuleSettingsRequest("abc", new JvmInfo("", "", "", "", "")),
      new ModuleSettingsRequest("abc", new JvmInfo(null, "def", "52.0", "123", "456")),
      new ModuleSettingsRequest("abc", new JvmInfo("abc", null, "52.0", "123", "456")),
      new ModuleSettingsRequest("abc", new JvmInfo("abc", "def", null, "123", "456")),
      new ModuleSettingsRequest("abc", new JvmInfo("abc", "def", "52.0", null, "456")),
      new ModuleSettingsRequest("abc", new JvmInfo("abc", "def", "52.0", null, null)),
      new ModuleSettingsRequest("abc", new JvmInfo(null, null, null, null, null)),
    ]
  }
}
