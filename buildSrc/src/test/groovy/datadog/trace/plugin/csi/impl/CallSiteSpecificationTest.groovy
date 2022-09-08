package datadog.trace.plugin.csi.impl

import datadog.trace.agent.tooling.csi.CallSiteAdvice
import datadog.trace.plugin.csi.ValidationContext
import datadog.trace.plugin.csi.util.ErrorCode
import org.objectweb.asm.Type
import spock.lang.Specification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AdviceSpecification

import static datadog.trace.plugin.csi.impl.CallSiteFactory.typeResolver
import static datadog.trace.plugin.csi.util.CallSiteConstants.TYPE_RESOLVER

class CallSiteSpecificationTest extends Specification {

  def 'test call site spi should be an interface'() {
    setup:
    final context = mockValidationContext()
    final spec = new CallSiteSpecification(Type.getType(String), [Mock(AdviceSpecification)], Type.getType(String), [] as Set<Type>)

    when:
    spec.validate(context)

    then:
    1 * context.addError(ErrorCode.CALL_SITE_SPI_SHOULD_BE_AN_INTERFACE, _)
  }

  def 'test call site spi should not define any methods'() {
    setup:
    final context = mockValidationContext()
    final spec = new CallSiteSpecification(Type.getType(String), [Mock(AdviceSpecification)], Type.getType(Comparable), [] as Set<Type>)

    when:
    spec.validate(context)

    then:
    1 * context.addError(ErrorCode.CALL_SITE_SPI_SHOULD_BE_EMPTY, _)
  }

  def 'test call site should have advices'() {
    setup:
    final context = mockValidationContext()
    final spec = new CallSiteSpecification(Type.getType(String), [], Type.getType(CallSiteAdvice), [] as Set<Type>)

    when:
    spec.validate(context)

    then:
    1 * context.addError(ErrorCode.CALL_SITE_SHOULD_HAVE_ADVICE_METHODS, _)
  }

  private ValidationContext mockValidationContext() {
    return Mock(ValidationContext) {
      getContextProperty(TYPE_RESOLVER) >> typeResolver()
    }
  }
}
