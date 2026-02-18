package datadog.trace.agent.tooling.csi

import datadog.instrument.classinject.ClassInjector
import datadog.trace.agent.tooling.bytebuddy.csi.Advices
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteTransformer
import datadog.trace.agent.tooling.csi.CallSiteAdvice.MethodHandler
import datadog.trace.api.function.TriFunction
import groovy.transform.CompileDynamic
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type

import java.util.function.BiConsumer
import java.util.function.BiFunction
import java.util.function.Consumer

import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.COPY

@CompileDynamic
class CallSiteTransformerTest extends BaseCallSiteTest {

  void 'test call site transformer'() {
    setup:
    final source = Type.getType(StringConcatExample)
    final target = renameType(source, 'Test')
    final pointcut = stringConcatPointcut()
    final advice = Mock(InvokeAdvice)
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([mockCallSites(advice, pointcut)]))

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final instance = loadType(target, transformedClass) as BiFunction<String, String, String>
    final result = instance.apply('Hello ', 'World!')

    then:
    1 * advice.apply(_ as MethodHandler, Opcodes.INVOKEVIRTUAL, pointcut.type, pointcut.method, pointcut.descriptor, false) >> { params ->
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

  void 'test modifying stack advices with category II items in the stack'() {
    setup:
    final source = Type.getType(StringConcatCategory2Example)
    final target = renameType(source, 'Test')
    final helperType = Type.getType(InstrumentationHelper)
    final helperMethod = Type.getType(InstrumentationHelper.getDeclaredMethod('onConcat', String, String))
    final pointcut = stringConcatPointcut()
    final advice = Mock(InvokeAdvice)
    final advices = mockAdvices([mockCallSites(advice, pointcut, helperType.className)])
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
    1 * advice.apply(_ as MethodHandler, Opcodes.INVOKEVIRTUAL, pointcut.type, pointcut.method, pointcut.descriptor, false) >> { params ->
      final args = params as Object[]
      final handler = args[0] as MethodHandler
      handler.dupInvoke(pointcut.type, pointcut.descriptor, COPY)
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
    final advice = Mock(InvokeAdvice)
    final advices = mockAdvices([mockCallSites(advice, pointcut, helperType.className)])
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
    1 * advice.apply(_ as MethodHandler, Opcodes.INVOKEVIRTUAL, pointcut.type, pointcut.method, pointcut.descriptor, false) >> { params ->
      final args = params as Object[]
      final handler = args[0] as MethodHandler
      handler.dupInvoke(pointcut.type, pointcut.descriptor, COPY)
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
    InvokeAdvice advice = Mock(InvokeAdvice)
    Advices advices = mockAdvices([mockCallSites(advice, pointcut, helperType.className)])
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
    1 * advice.apply(_ as MethodHandler, Opcodes.INVOKEVIRTUAL, pointcut.type, pointcut.method, pointcut.descriptor, false) >> { params ->
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

  void 'dupInvoke with owner argument'() {
    // case where there is a @This annotation and we're instrumenting an instance method
    // (that is, the advice is interested in the object whose method is being called)
    setup:
    Type source = Type.getType(CallSiteWithArraysExample)
    Type target = renameType(source, 'Test')
    Type helperType = Type.getType(InstrumentationHelper)
    Type helperMethod = Type.getType(InstrumentationHelper.getDeclaredMethod('onInsertSelfAndPartialArgs', StringBuilder, int, char[]))
    Pointcut pointcut = stringBuilderInsertPointcut()
    InvokeAdvice advice = Mock(InvokeAdvice)
    Advices advices = mockAdvices([mockCallSites(advice, pointcut, helperType.className)])
    CallSiteTransformer callSiteTransformer = new CallSiteTransformer(advices)
    def callbackArg
    InstrumentationHelper.callback = { arg -> callbackArg = arg }

    when:
    byte[] transformedClass = transformType(source, target, callSiteTransformer)
    def insert = loadType(target, transformedClass) as TriFunction<String, Integer, Integer, String>
    insert.apply('Hello World!', 6, 5)

    then:
    callbackArg.size() == 3
    callbackArg[0] instanceof StringBuilder
    callbackArg[1] == 0
    callbackArg[2] == 'Hello World!'.toCharArray()
    1 * advice.apply(_ as MethodHandler, Opcodes.INVOKEVIRTUAL, pointcut.type, pointcut.method, pointcut.descriptor, false) >> { params ->
      MethodHandler handler = params[0]
      int opcode = params[1]
      String owner = params[2]
      String name = params[3]
      String descriptor = params[4]
      boolean isInterface = params[5]

      int[] parameterIndices = [0, 1] as int[]
      handler.dupInvoke(owner, descriptor, parameterIndices)
      handler.method(Opcodes.INVOKESTATIC, helperType.internalName, 'onInsertSelfAndPartialArgs', helperMethod.descriptor, false)
      handler.method(opcode, owner, name, descriptor, isInterface)
    }
  }

  @SuppressWarnings(['GroovyAccessibility', 'GroovyAssignabilityCheck'])
  void 'test call site transformer with helpers'() {
    setup:
    ClassInjector.enableClassInjection(ByteBuddyAgent.getInstrumentation())
    final source = StringConcatExample
    final helper = InstrumentationHelper
    final customClassLoader = new ClassLoader() { }
    final builder = Mock(DynamicType.Builder)
    final pointcut = stringConcatPointcut()
    final advice = Mock(InvokeAdvice)
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([mockCallSites(advice, pointcut, helper.name)]))

    when:
    def helperCLass = customClassLoader.findLoadedClass(helper.name)

    then:
    helperCLass == null

    when:
    callSiteTransformer.transform(builder, new TypeDescription.ForLoadedType(source), customClassLoader, null, null)
    helperCLass = customClassLoader.findLoadedClass(helper.name)

    then:
    helperCLass != null
  }

  void 'test after advice with void return'() {
    setup:
    final source = Type.getType(StringBuilderSetLengthExample)
    final target = renameType(source, 'Test')
    final pointcut = stringBuilderSetLengthPointcut()
    final advice = Mock(InvokeAdvice)
    final callSite = Type.getType(StringBuilderSetLengthCallSite)
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([mockCallSites(advice, pointcut)]))
    final sb = new StringBuilder("Hello World!")
    final int length = 5
    StringBuilderSetLengthCallSite.LAST_CALL = null

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final instance = loadType(target, transformedClass) as BiConsumer<StringBuilder, Integer>
    instance.accept(sb, length)

    then:
    1 * advice.apply(_ as MethodHandler, Opcodes.INVOKEVIRTUAL, pointcut.type, pointcut.method, pointcut.descriptor, false) >> { params ->
      final args = params as Object[]
      final handler = args[0] as MethodHandler
      final owner = args[2] as String
      final descriptor = args[4] as String
      handler.dupInvoke(owner, descriptor, COPY)
      handler.method(args[1] as int, owner, args[3] as String, descriptor, args[5] as Boolean)
      handler.advice(callSite.getInternalName(), "after", "(Ljava/lang/StringBuilder;I)V")
    }
    sb.toString() == 'Hello'
    StringBuilderSetLengthCallSite.LAST_CALL[0] == sb
    StringBuilderSetLengthCallSite.LAST_CALL[1] == length
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

    static void onInsertSelfAndPartialArgs(final StringBuilder self, int index, char[] str) {
      callback?.accept([self, index, str])
    }
  }
}
