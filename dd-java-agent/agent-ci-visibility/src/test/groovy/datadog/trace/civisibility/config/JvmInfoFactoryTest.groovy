package datadog.trace.civisibility.config


import datadog.trace.util.ProcessUtils
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class JvmInfoFactoryTest extends Specification {

  def "test JVM info is retrieved"() {
    given:
    def currentJvmExecutable = getCurrentJvmExecutable()

    when:
    def jvmInfo = JvmInfoFactory.doGetJvmInfo(currentJvmExecutable)

    then:
    jvmInfo == JvmInfo.CURRENT_JVM
  }

  private Path getCurrentJvmExecutable() {
    def currentJvmPath = Paths.get(ProcessUtils.currentJvmPath)
    if (Files.isExecutable(currentJvmPath)) {
      return currentJvmPath
    }

    if (!currentJvmPath.endsWith("bin")) {
      currentJvmPath = currentJvmPath.resolve("bin")
    }
    return currentJvmPath.resolve("java")
  }
}
