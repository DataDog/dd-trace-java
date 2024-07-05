package datadog.trace.civisibility.coverage.instrumentation.store

import datadog.trace.civisibility.config.JvmInfo
import datadog.trace.civisibility.utils.FileUtils
import spock.lang.Specification

import java.nio.file.Files

class JvmPatcherTest extends Specification {

  def "test patches current JVM"() {
    setup:
    def patcher = new JvmPatcher(JvmInfo.CURRENT_JVM)

    when:
    // a basic check to ensure that patching does not fail and that the patched class file exists
    def patchFolder = patcher.createPatch()

    then:
    Files.exists(patchFolder.resolve("java/lang/Thread.class"))

    cleanup:
    FileUtils.deleteSafely(patchFolder)
  }
}

