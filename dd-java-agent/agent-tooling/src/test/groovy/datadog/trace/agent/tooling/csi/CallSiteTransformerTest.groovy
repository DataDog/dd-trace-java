package datadog.trace.agent.tooling.csi

import datadog.trace.agent.tooling.bytebuddy.csi.Advices
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteTransformer
import datadog.trace.agent.tooling.csi.CallSiteAdvice.MethodHandler
import datadog.trace.api.function.TriFunction
import groovy.transform.CompileDynamic
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type
import org.spockframework.runtime.ConditionNotSatisfiedError

import java.util.function.BiFunction
import java.util.function.Consumer

import static datadog.trace.agent.tooling.csi.CallSiteAdvice.HasFlags.COMPUTE_MAX_STACK
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.COPY

@CompileDynamic
class CallSiteTransformerTest extends BaseCallSiteTest {

  void 'test call site transformer'() {
    setup:
    final source = Type.getType(StringConcatExample)
    final target = renameType(source, 'Test')
    final pointcut = stringConcatPointcut()
    final callSite = mockInvokeAdvice(pointcut)
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([callSite]))

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final instance = loadType(target, transformedClass) as BiFunction<String, String, String>
    final result = instance.apply('Hello ', 'World!')

    then:
    1 * callSite.apply(_ as MethodHandler, Opcodes.INVOKEVIRTUAL, pointcut.type(), pointcut.method(), pointcut.descriptor(), false) >> { params ->
      final args = params as Object[]
      final handler = args[0] as MethodHandler
      handler.instruction(Opcodes.SWAP)
      handler.instruction(Opcodes.POP)
      handler.loadConstant('Goodbye ')
      handler.instruction(Opcodes.SWAP)
      handler.method(args[1] as int, args[2] as String, args[3] as String, args[4] as String, args[5] as Boolean)
    }
    result == 'Goodbye World!'
  }

  void 'test call site with non matching advice'() {
    setup:
    final source = Type.getType(StringConcatExample)
    final target = renameType(source, 'TestNoAdvices')
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([]))

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final instance = loadType(target, transformedClass) as BiFunction<String, String, String>
    final result = instance.apply('Hello ', 'World!')

    then:
    result == 'Hello World!'
  }

  void 'test modifying stack advices with compute max stack? #computeMax'(final boolean computeMax,
    final Class<? extends Exception> expectedThrown) {
    setup:
    final source = Type.getType(StringConcatExample)
    final target = renameType(source, 'Test')
    final helperType = Type.getType(InstrumentationHelper)
    final helperMethod = Type.getType(InstrumentationHelper.getDeclaredMethod('onConcat', String, String))
    final pointcut = stringConcatPointcut()
    final callSite = mockInvokeAdvice(pointcut, COMPUTE_MAX_STACK, helperType.className)
    final advices = mockAdvices([callSite])
    final callSiteTransformer = new CallSiteTransformer(advices)
    final callbackArguments = new Object[2]
    InstrumentationHelper.callback = { args ->  System.arraycopy(args, 0, callbackArguments, 0, 2) }

    when:
    // spock exception handling should be toplevel so we do a custom try/catch check
    try {
      final transformedClass = transformType(source, target, callSiteTransformer)
      final instance = loadType(target, transformedClass) as BiFunction<String, String, String>
      instance.apply('Hello ', 'World!')
      assert expectedThrown == null: 'Method should throw an exception'
      assert callbackArguments[0] == 'Hello '
      assert callbackArguments[1] == 'World!'
    } catch (ConditionNotSatisfiedError e) {
      throw e
    } catch (Throwable e) {
      assert e.getClass() == expectedThrown
    }

    then:
    1 * advices.computeMaxStack() >> computeMax
    1 * callSite.apply(_ as MethodHandler, Opcodes.INVOKEVIRTUAL, pointcut.type(), pointcut.method(), pointcut.descriptor(), false) >> { params ->
      final args = params as Object[]
      final handler = args[0] as MethodHandler
      handler.dupInvoke(pointcut.type(), pointcut.descriptor(), COPY)
      handler.method(Opcodes.INVOKESTATIC, helperType.internalName, 'onConcat', helperMethod.descriptor, false)
      handler.method(args[1] as int, args[2] as String, args[3] as String, args[4] as String, args[5] as Boolean)
    }

    where:
    computeMax | expectedThrown
    true       | null
    false      | VerifyError
  }

  void 'test modifying stack advices with category II items in the stack'() {
    setup:
    final source = Type.getType(StringConcatCategory2Example)
    final target = renameType(source, 'Test')
    final helperType = Type.getType(InstrumentationHelper)
    final helperMethod = Type.getType(InstrumentationHelper.getDeclaredMethod('onConcat', String, String))
    final pointcut = stringConcatPointcut()
    final callSite = mockInvokeAdvice(pointcut, COMPUTE_MAX_STACK, helperType.className)
    final advices = mockAdvices([callSite])
    final callSiteTransformer = new CallSiteTransformer(advices)
    final callbackArguments = new Object[2]
    InstrumentationHelper.callback = { args ->  System.arraycopy(args, 0, callbackArguments, 0, 2) }

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final instance = loadType(target, transformedClass) as BiFunction<String, String, String>
    instance.apply('Hello ', 'World!')

    then:
    callbackArguments[0] == 'Hello '
    callbackArguments[1] == 'World!'
    1 * callSite.apply(_ as MethodHandler, Opcodes.INVOKEVIRTUAL, pointcut.type(), pointcut.method(), pointcut.descriptor(), false) >> { params ->
      final args = params as Object[]
      final handler = args[0] as MethodHandler
      handler.dupInvoke(pointcut.type(), pointcut.descriptor(), COPY)
      handler.method(Opcodes.INVOKESTATIC, helperType.internalName, 'onConcat', helperMethod.descriptor, false)
      handler.method(args[1] as int, args[2] as String, args[3] as String, args[4] as String, args[5] as Boolean)
    }
  }

  void 'test stack based duplication with arrays'() {
    setup:
    final source = Type.getType(CallSiteWithArraysExample)
    final target = renameType(source, 'Test')
    final helperType = Type.getType(InstrumentationHelper)
    final helperMethod = Type.getType(InstrumentationHelper.getDeclaredMethod('onInsert', StringBuilder, int, char[], int, int))
    final pointcut = stringBuilderInsertPointcut()
    final callSite = mockInvokeAdvice(pointcut, COMPUTE_MAX_STACK, helperType.className)
    final advices = mockAdvices([callSite])
    final callSiteTransformer = new CallSiteTransformer(advices)
    final callbackArguments = new Object[4]
    InstrumentationHelper.callback = { args -> System.arraycopy(args, 0, callbackArguments, 0, callbackArguments.length) }

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final insert = loadType(target, transformedClass) as TriFunction<String, Integer, Integer, String>
    final inserted = insert.apply('Hello World!', 6, 5)

    then:
    inserted == 'World'
    callbackArguments[0] == 0
    callbackArguments[1] == 'Hello World!'.toCharArray()
    callbackArguments[2] == 6
    callbackArguments[3] == 5
    1 * callSite.apply(_ as MethodHandler, Opcodes.INVOKEVIRTUAL, pointcut.type(), pointcut.method(), pointcut.descriptor(), false) >> { params ->
      final args = params as Object[]
      final handler = args[0] as MethodHandler
      handler.dupInvoke(pointcut.type(), pointcut.descriptor(), COPY)
      handler.method(Opcodes.INVOKESTATIC, helperType.internalName, 'onInsert', helperMethod.descriptor, false)
      handler.method(args[1] as int, args[2] as String, args[3] as String, args[4] as String, args[5] as Boolean)
    }
  }

  void 'dupParameters with owner argument'() {
    // case where there is no annotation with @This but we're instrumenting an instance method
    // (that is, the advice is not interested in the object whose method is being called)
    setup:
    Type source = Type.getType(CallSiteWithArraysExample)
    Type target = renameType(source, 'Test')
    Type helperType = Type.getType(InstrumentationHelper)
    Type helperMethod = Type.getType(InstrumentationHelper.getDeclaredMethod('onInsertPartialArgs', int, char[], int))
    Pointcut pointcut = stringBuilderInsertPointcut()
    InvokeAdvice callSite = mockInvokeAdvice(pointcut, COMPUTE_MAX_STACK, helperType.className)
    Advices advices = mockAdvices([callSite])
    CallSiteTransformer callSiteTransformer = new CallSiteTransformer(advices)
    def callbackArg
    InstrumentationHelper.callback = { arg -> callbackArg = arg }

    when:
    byte[] transformedClass = transformType(source, target, callSiteTransformer)
    def insert = loadType(target, transformedClass) as TriFunction<String, Integer, Integer, String>
    insert.apply('Hello World!', 6, 5)

    then:
    callbackArg[0] == 0
    callbackArg[1] == 'Hello World!'.toCharArray()
    callbackArg[2] == 6
    callbackArg.size() == 3
    1 * callSite.apply(_ as MethodHandler, Opcodes.INVOKEVIRTUAL, pointcut.type(), pointcut.method(), pointcut.descriptor(), false) >> { params ->
      MethodHandler handler = params[0]
      int opcode = params[1]
      String owner = params[2]
      String name = params[3]
      String descriptor = params[4]
      boolean isInterface = params[5]

      int[] parameterIndices = [0, 1, 2,] as int[]
      handler.dupParameters(descriptor, parameterIndices, owner)
      handler.method(Opcodes.INVOKESTATIC, helperType.internalName, 'onInsertPartialArgs', helperMethod.descriptor, false)
      handler.method(opcode, owner, name, descriptor, isInterface)
    }
  }

  static class InstrumentationHelper {

    private static Consumer<Object[]> callback = null // codenarc forces the lowercase name

    static void onConcat(final String first, final String second) {
      if (callback != null) {
        callback.accept([first, second] as Object[])
      }
    }

    static void onInsert(final StringBuilder self, final int index, final char[] str, final int offset, final int length) {
      if (callback != null) {
        callback.accept([index, str, offset, length] as Object[])
      }
    }

    static void onInsertPartialArgs(int index, char[] str, int offset) {
      callback?.accept([index, str, offset])
    }
  }
}
