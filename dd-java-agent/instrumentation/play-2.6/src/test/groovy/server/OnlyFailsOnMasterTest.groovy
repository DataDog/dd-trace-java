package server

import datadog.trace.instrumentation.play26.PlayHttpServerDecorator
import datadog.trace.util.test.DDSpecification

class OnlyFailsOnMasterTest extends DDSpecification {
  def "typedKeyGetUnderlying field doesn't exist"() {
    when:
    PlayHttpServerDecorator.getDeclaredField("typedKeyGetUnderlying")

    then:
    thrown(NoSuchFieldException)
  }
}
