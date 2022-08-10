package datadog.trace.agent.tooling.csi

import datadog.trace.agent.tooling.Instrumenter
import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.jar.asm.MethodVisitor

class CallSiteInstrumenterTest extends BaseCallSiteTest {

  def 'test instrumenter creates transformer'() {
    setup:
    final advice = mockAdvice(buildPointcut(String.getDeclaredMethod('concat', String)))
    final instrumenter = buildInstrumenter([advice])
    final builder = Mock(DynamicType.Builder)
    final type = Mock(TypeDescription) {
      getName() >> StringConcatExample.name
    }

    when:
    final transformer = instrumenter.transformer()
    final result = transformer.transform(builder, type, getClass().getClassLoader(), null)

    then:
    result == builder
    1 * builder.visit(_ as AsmVisitorWrapper) >> builder
  }

  def 'test instrumenter adds no transformations'() {
    setup:
    final advice = mockAdvice(buildPointcut(String.getDeclaredMethod('concat', String)))
    final instrumenter = buildInstrumenter([advice])
    final mock = Mock(Instrumenter.AdviceTransformation)

    when:
    instrumenter.adviceTransformations(mock)

    then:
    0 * mock._
  }

  def 'test helper class names'() {
    setup:
    final advice1 = mockAdvice(buildPointcut(String.getDeclaredMethod('concat', String)), 'foo.bar.Helper1')
    final advice2 = mockAdvice(buildPointcut(StringBuilder.getDeclaredMethod('append', String)), 'foo.bar.Helper1', 'foo.bar.Helper2', 'foo.bar.Helper3')
    final instrumenter = buildInstrumenter([advice1, advice2])

    when:
    final helpers = instrumenter.helperClassNames()

    then:
    helpers.length == 3
    helpers.toList().containsAll('foo.bar.Helper1', 'foo.bar.Helper2', 'foo.bar.Helper3')
  }

  def 'test fetch advices from spi with custom class'() {
    setup:
    final builder = Mock(DynamicType.Builder)
    final type = Mock(TypeDescription) {
      getName() >> StringConcatExample.name
    }

    when:
    final instrumenter = buildInstrumenter(TestCallSiteAdvice)
    final transformer = instrumenter.transformer()
    transformer.transform(builder, type, getClass().getClassLoader(), null)

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
    final instrumenter = buildInstrumenter(CallSiteAdvice)
    final transformer = instrumenter.transformer()
    transformer.transform(builder, type, getClass().getClassLoader(), null)

    then:
    0 * builder.visit(_ as AsmVisitorWrapper) >> builder
  }

  static class StringConcatAdvice implements TestCallSiteAdvice {

    @Override
    Pointcut pointcut() {
      return buildPointcut(String.getDeclaredMethod('concat', String))
    }

    @Override
    void apply(
      final MethodVisitor mv,
      final int opcode,
      final String owner,
      final String name,
      final String descriptor,
      final boolean isInterface) {
      mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }
  }
}
