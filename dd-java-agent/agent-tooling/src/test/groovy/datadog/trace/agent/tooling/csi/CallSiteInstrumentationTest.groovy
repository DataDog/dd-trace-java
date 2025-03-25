package datadog.trace.agent.tooling.csi

import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteTransformer
import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.jar.asm.Type

import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.PREPEND_ARRAY_CTOR
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.AdviceType.AFTER
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.AdviceType.BEFORE

class CallSiteInstrumentationTest extends BaseCallSiteTest {

  def 'test instrumentation adds type advice'() {
    setup:
    final advice = mockCallSites(Mock(InvokeAdvice), stringConcatPointcut())
    final instrumentation = buildInstrumentation([advice])
    final builder = Mock(DynamicType.Builder)
    final type = Mock(TypeDescription) {
      getName() >> StringConcatExample.name
    }

    when:
    def result = null
    instrumentation.typeAdvice {
      result = it.transform(builder, type, getClass().getClassLoader(), null, null)
    }

    then:
    result == builder
    1 * builder.visit(_ as AsmVisitorWrapper) >> builder
  }

  def 'test fetch advices from spi with custom class'() {
    setup:
    final instrumentation = buildInstrumentation(TestCallSites)
    final builder = Mock(DynamicType.Builder)
    final type = Mock(TypeDescription) {
      getName() >> StringConcatExample.name
    }

    when:
    instrumentation.typeAdvice {
      it.transform(builder, type, getClass().getClassLoader(), null, null)
    }

    then:
    1 * builder.visit(_ as AsmVisitorWrapper) >> builder
  }

  def 'test fetch advices from spi with no implementations'() {
    setup:
    final instrumentation = buildInstrumentation(CallSiteAdvice)
    final builder = Mock(DynamicType.Builder)
    final type = Mock(TypeDescription) {
      getName() >> StringConcatExample.name
    }

    when:
    instrumentation.typeAdvice {
      it.transform(builder, type, getClass().getClassLoader(), null, null)
    }

    then:
    0 * builder.visit(_ as AsmVisitorWrapper) >> builder
  }

  void 'test call site transformer with super call in ctor'() {
    setup:
    SuperInCtorExampleAdvice.CALLS.set(0)
    final source = Type.getType(SuperInCtorExample)
    final target = renameType(source, 'Test')
    final pointcut = stringReaderPointcut()
    final InvokeAdvice advice = new InvokeAdvice() {
        @Override
        void apply(CallSiteAdvice.MethodHandler handler, int opcode, String owner, String name, String descriptor, boolean isInterface) {
          handler.dupParameters(descriptor, PREPEND_ARRAY_CTOR)
          handler.method(opcode, owner, name, descriptor, isInterface)
          handler.advice(
            Type.getType(SuperInCtorExampleAdvice).internalName,
            'onInvoke',
            Type.getMethodType(Type.getType(StringReader), Type.getType(Object[]), Type.getType(StringReader)).getDescriptor(),
            )
        }
      }
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([mockCallSites(AFTER, advice, pointcut)]))

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final transformed = loadClass(target, transformedClass)
    final reader = transformed.newInstance("test")

    then:
    reader != null
    SuperInCtorExampleAdvice.CALLS.get() > 0
  }

  static class StringCallSites implements CallSites, TestCallSites {

    @Override
    void accept(final Container container) {
      final pointcut = buildPointcut(String.getDeclaredMethod('concat', String))
      container.addAdvice(BEFORE, pointcut.type, pointcut.method, pointcut.descriptor, new StringConcatAdvice())
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

  static class SuperInCtorExampleAdvice {

    private static final AtomicInteger CALLS = new AtomicInteger(0)

    static StringReader onInvoke(Object[] args, StringReader result) {
      CALLS.incrementAndGet()
      return result
    }
  }
}
