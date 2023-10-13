package datadog.trace.agent.tooling.csi

import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteTransformer
import datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode
import datadog.trace.api.function.TriConsumer
import datadog.trace.api.function.TriFunction
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type
import spock.lang.Requires
import datadog.trace.agent.tooling.csi.CallSiteAdvice.MethodHandler

import java.util.function.Consumer
import java.util.function.Supplier

import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.APPEND_ARRAY
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.PREPEND_ARRAY

@Requires({
  jvm.java9Compatible
})
class CallSiteTransformerInvokeDynamicTest extends BaseCallSiteTest {

  def 'test call site transformer with invoke dynamic'() {
    setup:
    final source = Type.getType(StringPlusExample)
    final target = renameType(source, 'Test')
    final pointcut = stringConcatFactoryPointcut()
    final advice = Mock(InvokeDynamicAdvice)
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([mockCallSites(advice, pointcut)]))

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final instance = loadType(target, transformedClass) as TriFunction<String, String, String, String>
    final result = instance.apply("Hello ", "World", "!")

    then:
    1 * advice.apply(_ as MethodHandler, 'makeConcatWithConstants', '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;', _ as Handle, _ as Object[]) >> { params ->
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
    final advice = Mock(InvokeDynamicAdvice)
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([mockCallSites(advice, pointcut)]))

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final instance = loadType(target, transformedClass) as TriFunction<String, String, String, String>
    final result = instance.apply('Hello', 'World', '!')

    then:
    1 * advice.apply(_ as MethodHandler, 'makeConcatWithConstants', '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;', _ as Handle, _ as Object[]) >> { params ->
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

  def 'test unknown arity example with stack dup #mode'(final StackDupMode mode, final Class<?> helper) {
    setup:
    final source = Type.getType(UnknownArityExample)
    final target = renameType(source, 'Test')
    final helperType = Type.getType(helper)
    final helperMethod = Type.getType(helper.getDeclaredMethods().find { it.name == 'onConcat' })
    final pointcut = stringConcatFactoryPointcut()
    final advice = Mock(InvokeDynamicAdvice)
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([mockCallSites(advice, pointcut)]))
    final callbackArguments = new Object[4]
    final callback = { Object[] args ->  System.arraycopy(args, 0, callbackArguments, 0, args.length) }
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
    matcher.matches()
    callbackArguments[0] == matcher.group('name')
    callbackArguments[1] == Integer.valueOf(matcher.group('age'))
    callbackArguments[2] == Long.valueOf(matcher.group('height'))
    callbackArguments[3] == Double.valueOf(matcher.group('weight'))
    1 * advice.apply(_ as MethodHandler, 'makeConcatWithConstants', '(Ljava/lang/String;IJD)Ljava/lang/String;', _ as Handle, _ as Object[]) >> { params ->
      final args = params as Object[]
      final handler = args[0] as MethodHandler
      final String descriptor = args[2] as String
      handler.dupParameters(descriptor, mode)
      if (mode == APPEND_ARRAY) {
        handler.method(Opcodes.INVOKESTATIC, helperType.internalName, 'onConcat', helperMethod.descriptor, false)
        handler.invokeDynamic(args[1] as String, descriptor, args[3] as Handle, args[4] as Object[])
      } else {
        handler.invokeDynamic(args[1] as String, descriptor, args[3] as Handle, args[4] as Object[])
        handler.method(Opcodes.INVOKESTATIC, helperType.internalName, 'onConcat', helperMethod.descriptor, false)
      }
    }

    where:
    mode          | helper
    APPEND_ARRAY  | UnknownArityHelperBefore
    PREPEND_ARRAY | UnknownArityHelperAfter
  }

  def 'test adding boostrap constants to the stack'() {
    setup:
    final source = Type.getType(StringPlusLoadWithTagsExample)
    final target = renameType(source, 'Test')
    final helperType = Type.getType(StringPlusHelperWithConstants)
    final helperMethod = Type.getType(StringPlusHelperWithConstants.getDeclaredMethods().find { it.name == 'onConcat' })
    final pointcut = stringConcatFactoryPointcut()
    final advice = Mock(InvokeDynamicAdvice)
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([mockCallSites(advice, pointcut)]))
    final callbackArguments = new Object[3]
    final callbackResult = new String[1]
    final callbackConstants = new Object[3]
    final callback = { Object[] arguments, String result, Object[] constants ->
      System.arraycopy(arguments, 0, callbackArguments, 0, arguments.length)
      callbackResult[0] = result
      System.arraycopy(constants, 0, callbackConstants, 0, constants.length)
    }
    StringPlusHelperWithConstants.callback = callback

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final instance = loadType(target, transformedClass) as TriFunction<String, String, String, String>
    final result = instance.apply('Hello \u0001', 'World \u0002', '!')

    then:
    result == 'Hello \u0001 \u0002 World \u0002 \u0001 !'
    callbackArguments[0] == 'Hello \u0001'
    callbackArguments[1] == 'World \u0002'
    callbackArguments[2] == '!'
    callbackResult[0] == result
    // \u0001 is the place holder for the string concat arguments
    // \u0002 is used as a place holder for internal arguments, when the JDK finds an \u0001 or \u0002 in the string
    // template, it replaces the current entry with a \u0002 and introduces a new boostrap argument with the pattern
    callbackConstants[0] == '\u0001\u0002\u0001\u0002\u0001'
    callbackConstants[1] == ' \u0002 '
    callbackConstants[2] == ' \u0001 '
    1 * advice.apply(_ as MethodHandler, 'makeConcatWithConstants', '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;', _ as Handle, _ as Object[]) >> { params ->
      final args = params as Object[]
      final handler = args[0] as MethodHandler
      final String descriptor = args[2] as String
      handler.dupParameters(descriptor, PREPEND_ARRAY)
      handler.invokeDynamic(args[1] as String, descriptor, args[3] as Handle, args[4] as Object[])
      final Object[] bootstrapMethodConstants = args[4] as Object[]
      handler.loadConstantArray(bootstrapMethodConstants)
      handler.method(Opcodes.INVOKESTATIC, helperType.internalName, 'onConcat', helperMethod.descriptor, false)
    }
  }

  static class StringPlusHelper {
    private static Consumer<Object[]> callback = null // codenarc forces the lowercase name

    static void onConcat(final String first, final String second, final String third) {
      if (callback != null) {
        callback.accept([first, second, third] as Object[])
      }
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

  static class StringPlusHelperWithConstants {

    private static TriConsumer<Object[], String, Object[]> callback = null // codenarc forces the lowercase name

    static String onConcat(final Object[] parameters, final String result, final Object[] constants) {
      if (callback != null) {
        callback.accept(parameters, result, constants)
      }
      return result
    }
  }
}
