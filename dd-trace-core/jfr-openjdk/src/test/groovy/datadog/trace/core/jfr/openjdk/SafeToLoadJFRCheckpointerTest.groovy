package datadog.trace.core.jfr.openjdk

import datadog.trace.test.util.DDSpecification
import spock.lang.Requires

class SafeToLoadJFRCheckpointerTest extends DDSpecification {

  @Requires({ExcludedVersions.isVersionExcluded(System.getProperty("java.version", "unknown"))})
  def "test JFRCheckpointer cannot be loaded on excluded JVMs"() {
    when:
    new JFRCheckpointer()
    then:
    thrown IllegalStateException
  }
}
