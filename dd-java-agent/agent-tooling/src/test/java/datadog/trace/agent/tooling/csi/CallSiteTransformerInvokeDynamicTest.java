package datadog.trace.agent.tooling.csi;

import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.APPEND_ARRAY;
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.PREPEND_ARRAY;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.JRE.JAVA_9;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteTransformer;
import datadog.trace.agent.tooling.csi.CallSiteAdvice.MethodHandler;
import datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.function.TriFunction;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.tabletest.junit.TableTest;

@EnabledForJreRange(min = JAVA_9)
class CallSiteTransformerInvokeDynamicTest extends BaseCallSiteTest {

  private static final String MAKE_CONCAT_WITH_CONSTANTS = "makeConcatWithConstants";
  private static final String THREE_STRINGS_CONCAT_DESCRIPTOR =
      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
  private static final Pattern UNKNOWN_ARITY_RESULT =
      Pattern.compile(
          "My name is (?<name>[\\w\\-]+), I'm (?<age>\\d+) years old, (?<height>\\d+) cm tall and I weight (?<weight>[\\d\\.]+) kg");

  @Test
  void testCallSiteTransformerWithInvokeDynamic() throws Exception {
    // setup
    Type source = Type.getType(StringPlusExample.class);
    Type target = renameType(source, "Test");
    Pointcut pointcut = stringConcatFactoryPointcut();
    InvokeDynamicAdvice advice =
        mockAdvice(
            THREE_STRINGS_CONCAT_DESCRIPTOR,
            (handler, name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments) -> {
              handler.instruction(Opcodes.SWAP);
              handler.instruction(Opcodes.POP);
              handler.loadConstant("Friend");
              handler.instruction(Opcodes.SWAP);
              handler.invokeDynamic(
                  name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
            });
    CallSiteTransformer callSiteTransformer =
        new CallSiteTransformer(buildAdvices(singletonList(mockCallSites(advice, pointcut))));

    // when
    byte[] transformedClass = transformType(source, target, callSiteTransformer);
    TriFunction<String, String, String, String> instance =
        loadTriFunction(target, transformedClass);
    String result = instance.apply("Hello ", "World", "!");

    // then
    verifyAdviceApplied(advice, THREE_STRINGS_CONCAT_DESCRIPTOR);
    assertEquals("Hello Friend!", result);
  }

  @Test
  void testCallSiteWithInvokeDynamicWithNonMatchingAdvices() throws Exception {
    // setup
    Type source = Type.getType(StringPlusExample.class);
    Type target = renameType(source, "TestNoAdvices");
    CallSiteTransformer callSiteTransformer = new CallSiteTransformer(buildAdvices(emptyList()));

    // when
    byte[] transformedClass = transformType(source, target, callSiteTransformer);
    TriFunction<String, String, String, String> instance =
        loadTriFunction(target, transformedClass);
    String result = instance.apply("Hello ", "World", "!");

    // then
    assertEquals("Hello World!", result);
  }

  @Test
  void testCallSiteTransformerWithInvokeDynamicAndConstants() throws Exception {
    // setup
    Type source = Type.getType(StringPlusConstantsExample.class);
    Type target = renameType(source, "Test");
    Pointcut pointcut = stringConcatFactoryPointcut();
    InvokeDynamicAdvice advice =
        mockAdvice(
            THREE_STRINGS_CONCAT_DESCRIPTOR,
            (handler, name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments) -> {
              handler.instruction(Opcodes.SWAP);
              handler.instruction(Opcodes.POP);
              handler.loadConstant("Friend");
              handler.instruction(Opcodes.SWAP);
              Object[] dynamicArguments = new Object[] {"\u0001_\u0001-\u0001"};
              handler.invokeDynamic(name, descriptor, bootstrapMethodHandle, dynamicArguments);
            });
    CallSiteTransformer callSiteTransformer =
        new CallSiteTransformer(buildAdvices(singletonList(mockCallSites(advice, pointcut))));

    // when
    byte[] transformedClass = transformType(source, target, callSiteTransformer);
    TriFunction<String, String, String, String> instance =
        loadTriFunction(target, transformedClass);
    String result = instance.apply("Hello", "World", "!");

    // then
    verifyAdviceApplied(advice, THREE_STRINGS_CONCAT_DESCRIPTOR);
    assertEquals("Hello_Friend-!", result);
  }

  @Test
  void testCallSiteWithInvokeDynamicAndConstantsAndNonMatchingAdvices() throws Exception {
    // setup
    Type source = Type.getType(StringPlusConstantsExample.class);
    Type target = renameType(source, "TestNoAdvices");
    CallSiteTransformer callSiteTransformer = new CallSiteTransformer(buildAdvices(emptyList()));

    // when
    byte[] transformedClass = transformType(source, target, callSiteTransformer);
    TriFunction<String, String, String, String> instance =
        loadTriFunction(target, transformedClass);
    String result = instance.apply("Hello", "World", "!");

    // then
    assertEquals("Hello World !", result);
  }

  @TableTest({
    "scenario      | mode          | helper                                                                                       ",
    "APPEND_ARRAY  | APPEND_ARRAY  | datadog.trace.agent.tooling.csi.CallSiteTransformerInvokeDynamicTest$UnknownArityHelperBefore",
    "PREPEND_ARRAY | PREPEND_ARRAY | datadog.trace.agent.tooling.csi.CallSiteTransformerInvokeDynamicTest$UnknownArityHelperAfter "
  })
  void testUnknownArityExampleWithStackDup(StackDupMode mode, Class<?> helper) throws Exception {
    // setup
    Type source = Type.getType(UnknownArityExample.class);
    Type target = renameType(source, "Test");
    Type helperType = Type.getType(helper);
    Type helperMethod = Type.getType(onConcatMethod(helper));
    Pointcut pointcut = stringConcatFactoryPointcut();
    String unknownArityDescriptor = "(Ljava/lang/String;IJD)Ljava/lang/String;";
    InvokeDynamicAdvice advice =
        mockAdvice(
            unknownArityDescriptor,
            (handler, name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments) -> {
              handler.dupParameters(descriptor, mode);
              if (mode == APPEND_ARRAY) {
                handler.method(
                    Opcodes.INVOKESTATIC,
                    helperType.getInternalName(),
                    "onConcat",
                    helperMethod.getDescriptor(),
                    false);
                handler.invokeDynamic(
                    name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
              } else {
                handler.invokeDynamic(
                    name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
                handler.method(
                    Opcodes.INVOKESTATIC,
                    helperType.getInternalName(),
                    "onConcat",
                    helperMethod.getDescriptor(),
                    false);
              }
            });
    CallSiteTransformer callSiteTransformer =
        new CallSiteTransformer(buildAdvices(singletonList(mockCallSites(advice, pointcut))));
    Object[] callbackArguments = new Object[4];
    Consumer<Object[]> callback =
        args -> System.arraycopy(args, 0, callbackArguments, 0, args.length);
    if (helper == UnknownArityHelperBefore.class) {
      UnknownArityHelperBefore.callback = callback;
    } else {
      UnknownArityHelperAfter.callback = callback;
    }

    // when
    byte[] transformedClass = transformType(source, target, callSiteTransformer);
    @SuppressWarnings("unchecked")
    Supplier<String> instance = (Supplier<String>) loadType(target, transformedClass);
    String result = instance.get();

    // then
    Matcher matcher = UNKNOWN_ARITY_RESULT.matcher(result);
    assertTrue(matcher.matches(), result);
    assertEquals(matcher.group("name"), callbackArguments[0]);
    assertEquals(Integer.valueOf(matcher.group("age")), callbackArguments[1]);
    assertEquals(Long.valueOf(matcher.group("height")), callbackArguments[2]);
    assertEquals(Double.valueOf(matcher.group("weight")), callbackArguments[3]);
    verifyAdviceApplied(advice, unknownArityDescriptor);
  }

  @Test
  void testAddingBoostrapConstantsToTheStack() throws Exception {
    // setup
    Type source = Type.getType(StringPlusLoadWithTagsExample.class);
    Type target = renameType(source, "Test");
    Type helperType = Type.getType(StringPlusHelperWithConstants.class);
    Type helperMethod = Type.getType(onConcatMethod(StringPlusHelperWithConstants.class));
    Pointcut pointcut = stringConcatFactoryPointcut();
    InvokeDynamicAdvice advice =
        mockAdvice(
            THREE_STRINGS_CONCAT_DESCRIPTOR,
            (handler, name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments) -> {
              handler.dupParameters(descriptor, PREPEND_ARRAY);
              handler.invokeDynamic(
                  name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
              handler.loadConstantArray(bootstrapMethodArguments);
              handler.method(
                  Opcodes.INVOKESTATIC,
                  helperType.getInternalName(),
                  "onConcat",
                  helperMethod.getDescriptor(),
                  false);
            });
    CallSiteTransformer callSiteTransformer =
        new CallSiteTransformer(buildAdvices(singletonList(mockCallSites(advice, pointcut))));
    Object[] callbackArguments = new Object[3];
    String[] callbackResult = new String[1];
    Object[] callbackConstants = new Object[3];
    StringPlusHelperWithConstants.callback =
        (arguments, concatResult, constants) -> {
          System.arraycopy(arguments, 0, callbackArguments, 0, arguments.length);
          callbackResult[0] = concatResult;
          System.arraycopy(constants, 0, callbackConstants, 0, constants.length);
        };

    // when
    byte[] transformedClass = transformType(source, target, callSiteTransformer);
    TriFunction<String, String, String, String> instance =
        loadTriFunction(target, transformedClass);
    String result = instance.apply("Hello \u0001", "World \u0002", "!");

    // then
    assertEquals("Hello \u0001 \u0002 World \u0002 \u0001 !", result);
    assertEquals("Hello \u0001", callbackArguments[0]);
    assertEquals("World \u0002", callbackArguments[1]);
    assertEquals("!", callbackArguments[2]);
    assertEquals(result, callbackResult[0]);
    // \u0001 is the place holder for the string concat arguments
    // \u0002 is used as a place holder for internal arguments, when the JDK finds an \u0001 or
    // \u0002 in the string template, it replaces the current entry with a \u0002 and introduces a
    // new boostrap argument with the pattern
    assertEquals("\u0001\u0002\u0001\u0002\u0001", callbackConstants[0]);
    assertEquals(" \u0002 ", callbackConstants[1]);
    assertEquals(" \u0001 ", callbackConstants[2]);
    verifyAdviceApplied(advice, THREE_STRINGS_CONCAT_DESCRIPTOR);
  }

  /** Mocks an advice for {@code makeConcatWithConstants} that delegates to {@code behavior}. */
  private static InvokeDynamicAdvice mockAdvice(String descriptor, InvokeDynamicAdvice behavior) {
    InvokeDynamicAdvice advice = mock(InvokeDynamicAdvice.class);
    doAnswer(delegatesTo(behavior))
        .when(advice)
        .apply(
            any(MethodHandler.class),
            eq(MAKE_CONCAT_WITH_CONSTANTS),
            eq(descriptor),
            any(Handle.class),
            any()); // any() matches every vararg element
    return advice;
  }

  private static void verifyAdviceApplied(InvokeDynamicAdvice advice, String descriptor) {
    verify(advice)
        .apply(
            any(MethodHandler.class),
            eq(MAKE_CONCAT_WITH_CONSTANTS),
            eq(descriptor),
            any(Handle.class),
            any()); // any() matches every vararg element
  }

  @SuppressWarnings("unchecked")
  private static TriFunction<String, String, String, String> loadTriFunction(
      Type target, byte[] transformedClass) throws ReflectiveOperationException {
    return (TriFunction<String, String, String, String>) loadType(target, transformedClass);
  }

  private static Method onConcatMethod(Class<?> helper) {
    return Arrays.stream(helper.getDeclaredMethods())
        .filter(method -> method.getName().equals("onConcat"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No onConcat method in " + helper));
  }

  public static class UnknownArityHelperBefore {

    private static Consumer<Object[]> callback = null;

    public static void onConcat(Object[] parameters) {
      if (callback != null) {
        callback.accept(parameters);
      }
    }
  }

  public static class UnknownArityHelperAfter {

    private static Consumer<Object[]> callback = null;

    public static String onConcat(Object[] parameters, String result) {
      if (callback != null) {
        callback.accept(parameters);
      }
      return result;
    }
  }

  public static class StringPlusHelperWithConstants {

    private static TriConsumer<Object[], String, Object[]> callback = null;

    public static String onConcat(Object[] parameters, String result, Object[] constants) {
      if (callback != null) {
        callback.accept(parameters, result, constants);
      }
      return result;
    }
  }
}
