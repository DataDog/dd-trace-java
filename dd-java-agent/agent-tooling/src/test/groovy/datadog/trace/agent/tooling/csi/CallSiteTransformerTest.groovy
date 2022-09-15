package datadog.trace.agent.tooling.csi

import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteTransformer
import datadog.trace.api.function.BiFunction
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type
import datadog.trace.agent.tooling.csi.CallSiteAdvice.MethodHandler
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.HasFlags.COMPUTE_MAX_STACK
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.COPY

class CallSiteTransformerTest extends BaseCallSiteTest {

  def 'test call site transformer'() {
    setup:
    final source = Type.getType(StringConcatExample)
    final target = renameType(source, 'Test')
    final pointcut = stringConcatPointcut()
    final callSite = mockInvokeAdvice(pointcut)
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([callSite]))

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final instance = loadType(target, transformedClass) as BiFunction<String, String, String>
    final result = instance.apply("Hello ", "World!")

    then:
    1 * callSite.apply(_ as MethodHandler, Opcodes.INVOKEVIRTUAL, pointcut.type(), pointcut.method(), pointcut.descriptor(), false) >> { params ->
      final args = params as Object[]
      final handler = args[0] as MethodHandler
      handler.instruction(Opcodes.SWAP)
      handler.instruction(Opcodes.POP)
      handler.loadConstant("Goodbye ")
      handler.instruction(Opcodes.SWAP)
      handler.method(args[1] as int, args[2] as String, args[3] as String, args[4] as String, args[5] as Boolean)
    }
    result == "Goodbye World!"
  }

  def 'test call site with non matching advice'() {
    setup:
    final source = Type.getType(StringConcatExample)
    final target = renameType(source, 'TestNoAdvices')
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([]))

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final instance = loadType(target, transformedClass) as BiFunction<String, String, String>
    final result = instance.apply("Hello ", "World!")

    then:
    result == "Hello World!"
  }

  def 'test modifying stack advices with compute max stack? #computeMax'(final boolean computeMax,
    final Class<? extends Exception> expectedThrown) {
    setup:
    final source = Type.getType(StringConcatExample)
    final target = renameType(source, 'Test')
    final helperType = Type.getType(StringConcatHelper)
    final helperMethod = Type.getType(StringConcatHelper.getDeclaredMethod("onConcat", String, String))
    final pointcut = stringConcatPointcut()
    final callSite = mockInvokeAdvice(pointcut, COMPUTE_MAX_STACK, helperType.className)
    final advices = mockAdvices([callSite])
    final callSiteTransformer = new CallSiteTransformer(advices)

    when:
    // spock exception handling should be toplevel so we do a custom try/catch check
    try {
      final transformedClass = transformType(source, target, callSiteTransformer)
      final instance = loadType(target, transformedClass) as BiFunction<String, String, String>
      instance.apply("Hello ", "World!")
      assert expectedThrown == null: "Method should not throw an exception"
    } catch (Throwable e) {
      assert e.getClass() == expectedThrown
    }

    then:
    1 * advices.computeMaxStack() >> computeMax
    1 * callSite.apply(_ as MethodHandler, Opcodes.INVOKEVIRTUAL, pointcut.type(), pointcut.method(), pointcut.descriptor(), false) >> { params ->
      final args = params as Object[]
      final handler = args[0] as MethodHandler
      handler.dupInvoke(pointcut.type(), pointcut.descriptor(), COPY)
      handler.method(Opcodes.INVOKESTATIC, helperType.internalName, "onConcat", helperMethod.descriptor, false)
      handler.method(args[1] as int, args[2] as String, args[3] as String, args[4] as String, args[5] as Boolean)
    }

    where:
    computeMax | expectedThrown
    true       | null
    false      | VerifyError
  }

  static class StringConcatHelper {
    static void onConcat(final String first, final String second) {
      LOG.debug("onConcat called")
    }
  }
}
