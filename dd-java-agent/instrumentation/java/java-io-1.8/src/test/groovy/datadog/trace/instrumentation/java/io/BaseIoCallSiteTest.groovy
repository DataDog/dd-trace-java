package datadog.trace.instrumentation.java.io

import datadog.trace.agent.test.InstrumentationSpecification
import spock.lang.Shared
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

abstract class BaseIoCallSiteTest extends InstrumentationSpecification {
  @Shared
  @TempDir
  Path temporaryFolder

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  protected File newFile(final String name) {
    Path p = temporaryFolder.resolve(name)
    Files.createDirectories(p.getParent())
    return Files.createFile(p).toFile()
  }

  protected Path getRootFolder() {
    return temporaryFolder.getRoot()
  }
}
