package datadog.trace.civisibility.coverage.instrumentation.store

import datadog.trace.api.Platform
import datadog.trace.civisibility.config.JvmInfo
import org.apache.commons.io.IOUtils
import spock.lang.IgnoreIf
import spock.lang.Specification

@IgnoreIf(reason = "IBM Java 8 dist has a different structure, the thread class does not live in rt.jar", value = {
  Platform.isIbm8()
})
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
