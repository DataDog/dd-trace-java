package datadog.trace.agent.tooling.csi

import datadog.trace.agent.tooling.Instrumenter
import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType

class CallSiteInstrumentationTest extends BaseCallSiteTest {

  def 'test instrumentation creates transformer'() {
    setup:
    final advice = mockCallSites(Mock(InvokeAdvice), stringConcatPointcut())
    final instrumentation = buildInstrumentation([advice])
    final builder = Mock(DynamicType.Builder)
    final type = Mock(TypeDescription) {
      getName() >> StringConcatExample.name
    }

    when:
    final transformer = instrumentation.transformer()
    final result = transformer.transform(builder, type, getClass().getClassLoader(), null, null)

    then:
    result == builder
    1 * builder.visit(_ as AsmVisitorWrapper) >> builder
  }

  def 'test instrumentation adds no transformations'() {
    setup:
    final advice = mockCallSites(Mock(InvokeAdvice), stringConcatPointcut())
    final instrumentation = buildInstrumentation([advice])
    final mock = Mock(Instrumenter.AdviceTransformation)

    when:
    instrumentation.adviceTransformations(mock)

    then:
    0 * mock._
  }

  def 'test fetch advices from spi with custom class'() {
    setup:
    final builder = Mock(DynamicType.Builder)
    final type = Mock(TypeDescription) {
      getName() >> StringConcatExample.name
    }

    when:
    final instrumentation = buildInstrumentation(TestCallSites)
    final transformer = instrumentation.transformer()
    transformer.transform(builder, type, getClass().getClassLoader(), null, null)

    then:
    transformer != null
    1 * builder.visit(_ as AsmVisitorWrapper) >> builder
  }

  def 'test fetch advices from spi with no implementations'() {
    setup:
    final builder = Mock(DynamicType.Builder)
    final type = Mock(TypeDescription) {
      getName() >> StringConcatExample.name
    }

    when:
    final instrumentation = buildInstrumentation(CallSiteAdvice)
    final transformer = instrumentation.transformer()
    transformer.transform(builder, type, getClass().getClassLoader(), null, null)

    then:
    0 * builder.visit(_ as AsmVisitorWrapper) >> builder
  }

  static class StringCallSites implements CallSites, TestCallSites {

    @Override
    void accept(final Container container) {
      final pointcut = buildPointcut(String.getDeclaredMethod('concat', String))
      container.addAdvice(pointcut.type, pointcut.method, pointcut.descriptor, new StringConcatAdvice())
    }
  }

  static class StringConcatAdvice implements InvokeAdvice {

    @Override
    void apply(
      final MethodHandler handler,
      final int opcode,
      final String owner,
      final String name,
      final String descriptor,
      final boolean isInterface) {
      handler.method(opcode, owner, name, descriptor, isInterface)
    }
  }
}
