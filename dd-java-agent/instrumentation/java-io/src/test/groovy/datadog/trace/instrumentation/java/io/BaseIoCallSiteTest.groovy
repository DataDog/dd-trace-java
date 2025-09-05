package datadog.trace.instrumentation.java.io

import datadog.trace.agent.test.InstrumentationSpecification
import org.junit.Rule
import org.junit.rules.TemporaryFolder

abstract class BaseIoCallSiteTest extends InstrumentationSpecification {

  @Rule
  TemporaryFolder temporaryFolder = new TemporaryFolder(parentFolder())

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  protected File newFile(final String name) {
    return temporaryFolder.newFile(name)
  }

  protected File getRootFolder() {
    return temporaryFolder.getRoot()
  }

  /**
   * We cannot use @TempDir from spock due to dependencies, this method tries to write to the build folder to prevent
   * permissions with /tmp
   */
  private static File parentFolder() {
    def folder = new File(BaseIoCallSiteTest.getResource('.').toURI())
    while (folder.name != 'build') {
      folder = folder.parentFile
    }
    folder = new File(folder, 'tmp')
    if (!folder.exists()) {
      if (!folder.mkdirs()) {
        throw new RuntimeException('Cannot create folder ' + folder)
      }
    }
    return folder
  }
}
