package datadog.trace.agent.tooling.csi;

import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.COPY;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteTransformer;
import datadog.trace.agent.tooling.csi.CallSiteAdvice.MethodHandler;
import datadog.trace.api.function.TriFunction;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import org.junit.jupiter.api.Test;

class CallSiteTransformerTest extends BaseCallSiteTest {

  @Test
  void testCallSiteTransformer() throws Exception {
    // setup
    Type source = Type.getType(StringConcatExample.class);
    Type target = renameType(source, "Test");
    Pointcut pointcut = stringConcatPointcut();
    InvokeAdvice advice =
        mockAdvice(
            pointcut,
            (handler, opcode, owner, name, descriptor, isInterface) -> {
              handler.instruction(Opcodes.SWAP);
              handler.instruction(Opcodes.POP);
              handler.loadConstant("Goodbye ");
              handler.instruction(Opcodes.SWAP);
              handler.method(opcode, owner, name, descriptor, isInterface);
            });
    CallSiteTransformer callSiteTransformer =
        new CallSiteTransformer(buildAdvices(singletonList(mockCallSites(advice, pointcut))));

    // when
    byte[] transformedClass = transformType(source, target, callSiteTransformer);
    BiFunction<String, String, String> instance = loadBiFunction(target, transformedClass);
    String result = instance.apply("Hello ", "World!");

    // then
    verifyAdviceApplied(advice, pointcut);
    assertEquals("Goodbye World!", result);
  }

  @Test
  void testCallSiteWithNonMatchingAdvice() throws Exception {
    // setup
    Type source = Type.getType(StringConcatExample.class);
    Type target = renameType(source, "TestNoAdvices");
    CallSiteTransformer callSiteTransformer = new CallSiteTransformer(buildAdvices(emptyList()));

    // when
    byte[] transformedClass = transformType(source, target, callSiteTransformer);
    BiFunction<String, String, String> instance = loadBiFunction(target, transformedClass);
    String result = instance.apply("Hello ", "World!");

    // then
    assertEquals("Hello World!", result);
  }

  @Test
  void testModifyingStackAdvicesWithCategoryIIItemsInTheStack() throws Exception {
    // setup
    Type source = Type.getType(StringConcatCategory2Example.class);
    Type target = renameType(source, "Test");
    Type helperType = Type.getType(InstrumentationHelper.class);
    Type helperMethod =
        Type.getType(
            InstrumentationHelper.class.getDeclaredMethod("onConcat", String.class, String.class));
    Pointcut pointcut = stringConcatPointcut();
    InvokeAdvice advice =
        mockAdvice(
            pointcut,
            (handler, opcode, owner, name, descriptor, isInterface) -> {
              handler.dupInvoke(pointcut.type, pointcut.descriptor, COPY);
              handler.method(
                  Opcodes.INVOKESTATIC,
                  helperType.getInternalName(),
                  "onConcat",
                  helperMethod.getDescriptor(),
                  false);
              handler.method(opcode, owner, name, descriptor, isInterface);
            });
    Advices advices =
        buildAdvices(singletonList(mockCallSites(advice, pointcut, helperType.getClassName())));
    CallSiteTransformer callSiteTransformer = new CallSiteTransformer(advices);
    Object[] callbackArguments = new Object[2];
    InstrumentationHelper.callback = args -> System.arraycopy(args, 0, callbackArguments, 0, 2);

    // when
    byte[] transformedClass = transformType(source, target, callSiteTransformer);
    BiFunction<String, String, String> instance = loadBiFunction(target, transformedClass);
    instance.apply("Hello ", "World!");

    // then
    assertEquals("Hello ", callbackArguments[0]);
    assertEquals("World!", callbackArguments[1]);
    verifyAdviceApplied(advice, pointcut);
  }

  @Test
  void testStackBasedDuplicationWithArrays() throws Exception {
    // setup
    Type source = Type.getType(CallSiteWithArraysExample.class);
    Type target = renameType(source, "Test");
    Type helperType = Type.getType(InstrumentationHelper.class);
    Type helperMethod =
        Type.getType(
            InstrumentationHelper.class.getDeclaredMethod(
                "onInsert", StringBuilder.class, int.class, char[].class, int.class, int.class));
    Pointcut pointcut = stringBuilderInsertPointcut();
    InvokeAdvice advice =
        mockAdvice(
            pointcut,
            (handler, opcode, owner, name, descriptor, isInterface) -> {
              handler.dupInvoke(pointcut.type, pointcut.descriptor, COPY);
              handler.method(
                  Opcodes.INVOKESTATIC,
                  helperType.getInternalName(),
                  "onInsert",
                  helperMethod.getDescriptor(),
                  false);
              handler.method(opcode, owner, name, descriptor, isInterface);
            });
    Advices advices =
        buildAdvices(singletonList(mockCallSites(advice, pointcut, helperType.getClassName())));
    CallSiteTransformer callSiteTransformer = new CallSiteTransformer(advices);
    Object[] callbackArguments = new Object[4];
    InstrumentationHelper.callback =
        args -> System.arraycopy(args, 0, callbackArguments, 0, callbackArguments.length);

    // when
    byte[] transformedClass = transformType(source, target, callSiteTransformer);
    TriFunction<String, Integer, Integer, String> insert =
        loadTriFunction(target, transformedClass);
    String inserted = insert.apply("Hello World!", 6, 5);

    // then
    assertEquals("World", inserted);
    assertEquals(0, callbackArguments[0]);
    assertArrayEquals("Hello World!".toCharArray(), (char[]) callbackArguments[1]);
    assertEquals(6, callbackArguments[2]);
    assertEquals(5, callbackArguments[3]);
    verifyAdviceApplied(advice, pointcut);
  }

  @Test
  void dupParametersWithOwnerArgument() throws Exception {
    // case where there is no annotation with @This but we're instrumenting an instance method
    // (that is, the advice is not interested in the object whose method is being called)
    // setup
    Type source = Type.getType(CallSiteWithArraysExample.class);
    Type target = renameType(source, "Test");
    Type helperType = Type.getType(InstrumentationHelper.class);
    Type helperMethod =
        Type.getType(
            InstrumentationHelper.class.getDeclaredMethod(
                "onInsertPartialArgs", int.class, char[].class, int.class));
    Pointcut pointcut = stringBuilderInsertPointcut();
    InvokeAdvice advice =
        mockAdvice(
            pointcut,
            (handler, opcode, owner, name, descriptor, isInterface) -> {
              int[] parameterIndices = new int[] {0, 1, 2};
              handler.dupParameters(descriptor, parameterIndices, owner);
              handler.method(
                  Opcodes.INVOKESTATIC,
                  helperType.getInternalName(),
                  "onInsertPartialArgs",
                  helperMethod.getDescriptor(),
                  false);
              handler.method(opcode, owner, name, descriptor, isInterface);
            });
    Advices advices =
        buildAdvices(singletonList(mockCallSites(advice, pointcut, helperType.getClassName())));
    CallSiteTransformer callSiteTransformer = new CallSiteTransformer(advices);
    Object[][] callbackArg = new Object[1][];
    InstrumentationHelper.callback = arg -> callbackArg[0] = arg;

    // when
    byte[] transformedClass = transformType(source, target, callSiteTransformer);
    TriFunction<String, Integer, Integer, String> insert =
        loadTriFunction(target, transformedClass);
    insert.apply("Hello World!", 6, 5);

    // then
    Object[] callbackArguments = callbackArg[0];
    assertNotNull(callbackArguments);
    assertEquals(0, callbackArguments[0]);
    assertArrayEquals("Hello World!".toCharArray(), (char[]) callbackArguments[1]);
    assertEquals(6, callbackArguments[2]);
    assertEquals(3, callbackArguments.length);
    verifyAdviceApplied(advice, pointcut);
  }

  @Test
  void dupInvokeWithOwnerArgument() throws Exception {
    // case where there is a @This annotation and we're instrumenting an instance method
    // (that is, the advice is interested in the object whose method is being called)
    // setup
    Type source = Type.getType(CallSiteWithArraysExample.class);
    Type target = renameType(source, "Test");
    Type helperType = Type.getType(InstrumentationHelper.class);
    Type helperMethod =
        Type.getType(
            InstrumentationHelper.class.getDeclaredMethod(
                "onInsertSelfAndPartialArgs", StringBuilder.class, int.class, char[].class));
    Pointcut pointcut = stringBuilderInsertPointcut();
    InvokeAdvice advice =
        mockAdvice(
            pointcut,
            (handler, opcode, owner, name, descriptor, isInterface) -> {
              int[] parameterIndices = new int[] {0, 1};
              handler.dupInvoke(owner, descriptor, parameterIndices);
              handler.method(
                  Opcodes.INVOKESTATIC,
                  helperType.getInternalName(),
                  "onInsertSelfAndPartialArgs",
                  helperMethod.getDescriptor(),
                  false);
              handler.method(opcode, owner, name, descriptor, isInterface);
            });
    Advices advices =
        buildAdvices(singletonList(mockCallSites(advice, pointcut, helperType.getClassName())));
    CallSiteTransformer callSiteTransformer = new CallSiteTransformer(advices);
    Object[][] callbackArg = new Object[1][];
    InstrumentationHelper.callback = arg -> callbackArg[0] = arg;

    // when
    byte[] transformedClass = transformType(source, target, callSiteTransformer);
    TriFunction<String, Integer, Integer, String> insert =
        loadTriFunction(target, transformedClass);
    insert.apply("Hello World!", 6, 5);

    // then
    Object[] callbackArguments = callbackArg[0];
    assertNotNull(callbackArguments);
    assertEquals(3, callbackArguments.length);
    assertInstanceOf(StringBuilder.class, callbackArguments[0]);
    assertEquals(0, callbackArguments[1]);
    assertArrayEquals("Hello World!".toCharArray(), (char[]) callbackArguments[2]);
    verifyAdviceApplied(advice, pointcut);
  }

  @Test
  void testCallSiteTransformerWithHelpers() throws Exception {
    // setup
    Class<?> source = StringConcatExample.class;
    Class<?> helper = InstrumentationHelper.class;
    InspectableClassLoader customClassLoader = new InspectableClassLoader();
    DynamicType.Builder<?> builder = mock(DynamicType.Builder.class);
    Pointcut pointcut = stringConcatPointcut();
    InvokeAdvice advice = mock(InvokeAdvice.class);
    CallSiteTransformer callSiteTransformer =
        new CallSiteTransformer(
            buildAdvices(singletonList(mockCallSites(advice, pointcut, helper.getName()))));

    // when
    Class<?> helperClass = customClassLoader.loadedClass(helper.getName());

    // then
    assertNull(helperClass);

    // when
    callSiteTransformer.transform(
        builder, new TypeDescription.ForLoadedType(source), customClassLoader, null, null);
    helperClass = customClassLoader.loadedClass(helper.getName());

    // then
    assertNotNull(helperClass);
  }

  @Test
  void testAfterAdviceWithVoidReturn() throws Exception {
    // setup
    Type source = Type.getType(StringBuilderSetLengthExample.class);
    Type target = renameType(source, "Test");
    Pointcut pointcut = stringBuilderSetLengthPointcut();
    Type callSite = Type.getType(StringBuilderSetLengthCallSite.class);
    InvokeAdvice advice =
        mockAdvice(
            pointcut,
            (handler, opcode, owner, name, descriptor, isInterface) -> {
              handler.dupInvoke(owner, descriptor, COPY);
              handler.method(opcode, owner, name, descriptor, isInterface);
              handler.advice(callSite.getInternalName(), "after", "(Ljava/lang/StringBuilder;I)V");
            });
    CallSiteTransformer callSiteTransformer =
        new CallSiteTransformer(buildAdvices(singletonList(mockCallSites(advice, pointcut))));
    StringBuilder builder = new StringBuilder("Hello World!");
    int length = 5;
    StringBuilderSetLengthCallSite.LAST_CALL = null;

    // when
    byte[] transformedClass = transformType(source, target, callSiteTransformer);
    @SuppressWarnings("unchecked")
    BiConsumer<StringBuilder, Integer> instance =
        (BiConsumer<StringBuilder, Integer>) loadType(target, transformedClass);
    instance.accept(builder, length);

    // then
    verifyAdviceApplied(advice, pointcut);
    assertEquals("Hello", builder.toString());
    assertSame(builder, StringBuilderSetLengthCallSite.LAST_CALL[0]);
    assertEquals(length, StringBuilderSetLengthCallSite.LAST_CALL[1]);
  }

  /**
   * Mocks an advice for the pointcut's {@code INVOKEVIRTUAL} that delegates to {@code behavior}.
   */
  private static InvokeAdvice mockAdvice(Pointcut pointcut, InvokeAdvice behavior) {
    InvokeAdvice advice = mock(InvokeAdvice.class);
    doAnswer(delegatesTo(behavior))
        .when(advice)
        .apply(
            any(MethodHandler.class),
            eq(Opcodes.INVOKEVIRTUAL),
            eq(pointcut.type),
            eq(pointcut.method),
            eq(pointcut.descriptor),
            eq(false));
    return advice;
  }

  private static void verifyAdviceApplied(InvokeAdvice advice, Pointcut pointcut) {
    verify(advice)
        .apply(
            any(MethodHandler.class),
            eq(Opcodes.INVOKEVIRTUAL),
            eq(pointcut.type),
            eq(pointcut.method),
            eq(pointcut.descriptor),
            eq(false));
  }

  @SuppressWarnings("unchecked")
  private static BiFunction<String, String, String> loadBiFunction(
      Type target, byte[] transformedClass) throws ReflectiveOperationException {
    return (BiFunction<String, String, String>) loadType(target, transformedClass);
  }

  @SuppressWarnings("unchecked")
  private static TriFunction<String, Integer, Integer, String> loadTriFunction(
      Type target, byte[] transformedClass) throws ReflectiveOperationException {
    return (TriFunction<String, Integer, Integer, String>) loadType(target, transformedClass);
  }

  /** Exposes {@link ClassLoader#findLoadedClass(String)} to check helper injection. */
  private static class InspectableClassLoader extends ClassLoader {
    Class<?> loadedClass(String name) {
      return findLoadedClass(name);
    }
  }

  public static class InstrumentationHelper {

    private static Consumer<Object[]> callback = null;

    public static void onConcat(String first, String second) {
      if (callback != null) {
        callback.accept(new Object[] {first, second});
      }
    }

    public static void onInsert(StringBuilder self, int index, char[] str, int offset, int length) {
      if (callback != null) {
        callback.accept(new Object[] {index, str, offset, length});
      }
    }

    public static void onInsertPartialArgs(int index, char[] str, int offset) {
      if (callback != null) {
        callback.accept(new Object[] {index, str, offset});
      }
    }

    public static void onInsertSelfAndPartialArgs(StringBuilder self, int index, char[] str) {
      if (callback != null) {
        callback.accept(new Object[] {self, index, str});
      }
    }
  }
}
