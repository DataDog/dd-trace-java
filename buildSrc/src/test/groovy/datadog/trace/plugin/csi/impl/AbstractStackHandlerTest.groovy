package datadog.trace.plugin.csi.impl

import datadog.trace.plugin.csi.HasErrors
import datadog.trace.plugin.csi.StackHandler.AbstractStackHandler
import datadog.trace.plugin.csi.StackHandler.StackEntry
import datadog.trace.plugin.csi.ValidationContext
import datadog.trace.plugin.csi.samples.StacksSampleAdvice
import spock.lang.Specification

import javax.annotation.Nonnull

import static datadog.trace.plugin.csi.impl.CallSiteFactory.pointcutParser
import static datadog.trace.plugin.csi.impl.CallSiteFactory.specificationBuilder
import static datadog.trace.plugin.csi.impl.CallSiteFactory.typeResolver
import static datadog.trace.plugin.csi.util.CallSiteConstants.TYPE_RESOLVER

class AbstractStackHandlerTest extends Specification {

  private static CallSiteSpecification STACKS_SAMPLE_SPEC

  def setupSpec() {
    STACKS_SAMPLE_SPEC = buildSpecification(StacksSampleAdvice)
  }

  def 'test validation based with advices'() {
    setup:
    final spec = findAdvice(STACKS_SAMPLE_SPEC, advice)
    final mock = Mock(CalculateMock)
    final handler = new AbstractStackHandler() {
      @Override
      @Nonnull
      Optional<int[]> calculateInstructions(@Nonnull final StackEntry currentStack, @Nonnull final StackEntry targetStack) {
        return mock.calculateInstructions(currentStack, targetStack)
      }
    }

    when:
    handler.calculateInstructions(spec)

    then:
    if (current == null && target == null) {
      0 * mock.calculateInstructions(_ as StackEntry, _ as StackEntry)
    } else {
      1 * mock.calculateInstructions({ it.toString() == current }, { it.toString() == target })
    }

    where:
    advice                   || current     | target
    'around'                 || null        | null
    'beforeConstructor'      || "0|1|2|3|4" | "0|1|2|3|4|1|2|3|4"
    'afterConstructor'       || "0|1|2|3|4" | "1|2|3|4|0|1|2|3|4"
    'beforeConstructorEmpty' || "0|1|2|3|4" | "0|1|2|3|4"
    'afterConstructorEmpty'  || "0|1|2|3|4" | "0|1|2|3|4"
    'beforeLong'             || "0|1|2a|2b" | "0|1|2a|2b|0|1|2a|2b"
    'afterLong'              || "0|1|2a|2b" | "1|2a|2b|0|1|2a|2b"
  }

  private static File fetchClass(final Class<?> clazz) {
    final resource = clazz.getResource("${clazz.simpleName}.class")
    return new File(resource.path)
  }

  private static CallSiteSpecification buildSpecification(final Class<?> clazz) {
    final classFile = fetchClass(clazz)
    final callSite = specificationBuilder().build(classFile).get()
    final context = new ValidationContext.BaseValidationContext()
    final pointcutParser = pointcutParser()
    context.addContextProperty(TYPE_RESOLVER, typeResolver())
    callSite.validate(context)
    callSite.advices.each {
      it.parseSignature(pointcutParser)
      it.validate(context)
    }
    if (!context.success) {
      throw new HasErrors.HasErrorsException(context)
    }
    return callSite
  }

  private static CallSiteSpecification.AdviceSpecification findAdvice(final CallSiteSpecification spec, final String name) {
    return spec
      .advices
      .filter(it -> it.advice.methodName == name)
      .findFirst()
      .get()
  }

  static interface CalculateMock {
    Optional<int[]> calculateInstructions(final StackEntry current, final StackEntry target)
  }
}
