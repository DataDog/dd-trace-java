package datadog.trace.agent.tooling.csi

import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteTransformer
import datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode
import datadog.trace.api.function.Consumer
import datadog.trace.api.function.Supplier
import datadog.trace.api.function.TriFunction
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type
import spock.lang.Requires
import datadog.trace.agent.tooling.csi.CallSiteAdvice.MethodHandler

import static datadog.trace.agent.tooling.csi.CallSiteAdvice.HasFlags.COMPUTE_MAX_STACK
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.APPEND_ARRAY
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.PREPEND_ARRAY
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.COPY

@Requires({
  jvm.java9Compatible
})
class CallSiteTransformerInvokeDynamicTest extends BaseCallSiteTest {

  def 'test call site transformer with invoke dynamic'() {
    setup:
    final source = Type.getType(StringPlusExample)
    final target = renameType(source, 'Test')
    final pointcut = stringConcatFactoryPointcut()
    final callSite = mockInvokeDynamicAdvice(pointcut)
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([callSite]))

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final instance = loadType(target, transformedClass) as TriFunction<String, String, String, String>
    final result = instance.apply("Hello ", "World", "!")

    then:
    1 * callSite.apply(_ as MethodHandler, 'makeConcatWithConstants', '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;', _ as Handle, _ as Object[]) >> { params ->
      final args = params as Object[]
      final handler = args[0] as MethodHandler
      handler.instruction(Opcodes.SWAP)
      handler.instruction(Opcodes.POP)
      handler.loadConstant("Friend")
      handler.instruction(Opcodes.SWAP)
      handler.invokeDynamic(args[1] as String, args[2] as String, args[3] as Handle, args[4] as Object[])
    }
    result == "Hello Friend!"
  }

  def 'test call site with invoke dynamic with non matching advices'() {
    setup:
    final source = Type.getType(StringPlusExample)
    final target = renameType(source, 'TestNoAdvices')
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([]))

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final instance = loadType(target, transformedClass) as TriFunction<String, String, String, String>
    final result = instance.apply("Hello ", "World", "!")

    then:
    result == "Hello World!"
  }

  def 'test call site transformer with invoke dynamic and constants'() {
    setup:
    final source = Type.getType(StringPlusConstantsExample)
    final target = renameType(source, 'Test')
    final pointcut = stringConcatFactoryPointcut()
    final callSite = mockInvokeDynamicAdvice(pointcut)
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([callSite]))

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final instance = loadType(target, transformedClass) as TriFunction<String, String, String, String>
    final result = instance.apply("Hello", "World", "!")

    then:
    1 * callSite.apply(_ as MethodHandler, 'makeConcatWithConstants', '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;', _ as Handle, _ as Object[]) >> { params ->
      final args = params as Object[]
      final handler = args[0] as MethodHandler
      handler.instruction(Opcodes.SWAP)
      handler.instruction(Opcodes.POP)
      handler.loadConstant("Friend")
      handler.instruction(Opcodes.SWAP)
      final dynamicArguments = ['\u0001_\u0001-\u0001'] as Object[]
      handler.invokeDynamic(args[1] as String, args[2] as String, args[3] as Handle, dynamicArguments)
    }
    result == "Hello_Friend-!"
  }

  def 'test call site with invoke dynamic and constants and non matching advices'() {
    setup:
    final source = Type.getType(StringPlusConstantsExample)
    final target = renameType(source, 'TestNoAdvices')
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([]))

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final instance = loadType(target, transformedClass) as TriFunction<String, String, String, String>
    final result = instance.apply("Hello", "World", "!")

    then:
    result == "Hello World !"
  }

  def 'test modifying stack advices with compute max stack? #computeMax'(final boolean computeMax,
    final Class<? extends Exception> expectedThrown) {
    setup:
    final source = Type.getType(StringPlusConstantsExample)
    final target = renameType(source, 'Test')
    final helperType = Type.getType(StringPlusHelper)
    final helperMethod = Type.getType(StringPlusHelper.getDeclaredMethod("onConcat", String, String, String))
    final pointcut = stringConcatFactoryPointcut()
    final callSite = mockInvokeDynamicAdvice(pointcut, COMPUTE_MAX_STACK, helperType.className)
    final advices = mockAdvices([callSite])
    final callSiteTransformer = new CallSiteTransformer(advices)

    when:
    // spock exception handling should be toplevel so we do a custom try/catch check
    try {
      final transformedClass = transformType(source, target, callSiteTransformer)
      final instance = loadType(target, transformedClass) as TriFunction<String, String, String, String>
      instance.apply("Hello", "World", "!")
      assert expectedThrown == null: "Method should not throw an exception"
    } catch (Throwable e) {
      assert e.getClass() == expectedThrown
    }

    then:
    1 * advices.computeMaxStack() >> computeMax
    1 * callSite.apply(_ as MethodHandler, 'makeConcatWithConstants', '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;', _ as Handle, _ as Object[]) >> { params ->
      final args = params as Object[]
      final handler = args[0] as MethodHandler
      final String descriptor = args[2] as String
      handler.dupParameters(descriptor, COPY)
      handler.method(Opcodes.INVOKESTATIC, helperType.internalName, "onConcat", helperMethod.descriptor, false)
      handler.invokeDynamic(args[1] as String, descriptor, args[3] as Handle, args[4] as Object[])
    }

    where:
    computeMax | expectedThrown
    true       | null
    false      | VerifyError
  }

  def 'test unknown arity example with stack dup #mode'(final StackDupMode mode, final Class<?> helper) {
    setup:
    final source = Type.getType(UnknownArityExample)
    final target = renameType(source, 'Test')
    final helperType = Type.getType(helper)
    final helperMethod = Type.getType(helper.getDeclaredMethods().find { it.name == 'onConcat' })
    final pointcut = stringConcatFactoryPointcut()
    final callSite = mockInvokeDynamicAdvice(pointcut, COMPUTE_MAX_STACK, helperType.className)
    final advices = mockAdvices([callSite])
    final callSiteTransformer = new CallSiteTransformer(advices)
    final callbackArguments = new Object[4]
    final callback = { args ->  System.arraycopy(args, 0, callbackArguments, 0, 4) }
    if (helper == UnknownArityHelperBefore) {
      UnknownArityHelperBefore.callback = callback
    } else {
      UnknownArityHelperAfter.callback = callback
    }

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final instance = loadType(target, transformedClass) as Supplier<String>
    final result = instance.get()

    then:
    final matcher = result =~ /My name is (?<name>[\w\-]+), I'm (?<age>\d+) years old, (?<height>\d+) cm tall and I weight (?<weight>[\d\.]+) kg/
    assert matcher.matches()
    assert callbackArguments[0] == matcher.group('name')
    assert callbackArguments[1] == Integer.valueOf(matcher.group('age'))
    assert callbackArguments[2] == Long.valueOf(matcher.group('height'))
    assert callbackArguments[3] == Double.valueOf(matcher.group('weight'))

    1 * advices.computeMaxStack() >> true
    1 * callSite.apply(_ as MethodHandler, 'makeConcatWithConstants', '(Ljava/lang/String;IJD)Ljava/lang/String;', _ as Handle, _ as Object[]) >> { params ->
      final args = params as Object[]
      final handler = args[0] as MethodHandler
      final String descriptor = args[2] as String
      handler.dupParameters(descriptor, mode)
      if (mode == APPEND_ARRAY) {
        handler.method(Opcodes.INVOKESTATIC, helperType.internalName, "onConcat", helperMethod.descriptor, false)
        handler.invokeDynamic(args[1] as String, descriptor, args[3] as Handle, args[4] as Object[])
      } else {
        handler.invokeDynamic(args[1] as String, descriptor, args[3] as Handle, args[4] as Object[])
        handler.method(Opcodes.INVOKESTATIC, helperType.internalName, "onConcat", helperMethod.descriptor, false)
      }
    }

    where:
    mode          | helper
    APPEND_ARRAY  | UnknownArityHelperBefore
    PREPEND_ARRAY | UnknownArityHelperAfter
  }

  static class StringPlusHelper {
    static void onConcat(final String first, final String second, final String third) {
      LOG.debug("onConcat called")
    }
  }

  static class UnknownArityHelperBefore {

    private static Consumer<Object[]> callback = null // codenarc forces the lowercase name

    static void onConcat(final Object[] parameters) {
      if (callback != null) {
        callback.accept(parameters)
      }
    }
  }

  static class UnknownArityHelperAfter {

    private static Consumer<Object[]> callback = null // codenarc forces the lowercase name


    static String onConcat(final Object[] parameters, final String result) {
      if (callback != null) {
        callback.accept(parameters)
      }
      return result
    }
  }
}
