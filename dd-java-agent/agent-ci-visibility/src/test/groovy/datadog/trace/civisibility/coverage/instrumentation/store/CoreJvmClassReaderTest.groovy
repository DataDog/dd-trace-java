package datadog.trace.civisibility.coverage.instrumentation.store

import datadog.trace.civisibility.config.JvmInfo
import org.apache.commons.io.IOUtils
import spock.lang.Specification

class CoreJvmClassReaderTest extends Specification {

  def "test reads Thread class"() {
    setup:
    def reader = new CoreJvmClassReader()

    when:
    def threadClassBytecode = reader.withClassStream(JvmInfo.CURRENT_JVM, Thread.class.getName(), is -> IOUtils.toByteArray(is))

    then:
    threadClassBytecode != null
    threadClassBytecode.length > 0
  }
}
