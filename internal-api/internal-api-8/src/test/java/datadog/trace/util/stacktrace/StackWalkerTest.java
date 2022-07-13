package datadog.trace.util.stacktrace;

import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;
import java.util.stream.Stream;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodCall;
import org.junit.jupiter.api.Test;

public class StackWalkerTest {

  public static final String COM_FOO_STACK_SUPPLIER = "com.foo.StackSupplier";
  public static final String DATADOG_TRACE_STACK_SUPPLIER = "datadog.trace.StackSupplier";
  public static final String COM_DATADOG_APPSEC_STACK_SUPPLIER = "com.datadog.appsec.StackSupplier";

  @Test
  public void stackTraceGenerator_instance_must_be_enabled() {
    // When
    StackWalker stackWalker = StackWalker.INSTANCE;
    // Then
    assertTrue(stackWalker.isEnabled());
  }

  @Test
  public void retrieve_stackTraceElements() {
    // When
    Stream<StackTraceElement> stackTraceElements = StackWalker.INSTANCE.walk();
    // Then
    assertNotEquals(stackTraceElements.count(), 0);
  }

  @Test
  public void retrieve_fist_stack_element_not_in_DD_trace_project() throws Exception {
    // when
    Supplier<Stream<StackTraceElement>> stream = getSupplier(COM_FOO_STACK_SUPPLIER);
    // Then
    assertEquals(stream.get().findFirst().get().getClassName(), COM_FOO_STACK_SUPPLIER);
  }

  @Test
  public void filter_DataDog_Trace_classes_from_StackTraceElements() throws Exception {
    // when
    Supplier<Stream<StackTraceElement>> stream = getSupplier(DATADOG_TRACE_STACK_SUPPLIER);
    // then
    assertTrue(
        stream
            .get()
            .noneMatch(
                stackTraceElement ->
                    stackTraceElement.getClassName().equals(DATADOG_TRACE_STACK_SUPPLIER)));
  }

  @Test
  public void filter_DataDog_AppSec_classes_from_StackTraceElements() throws Exception {
    // When
    Supplier<Stream<StackTraceElement>> stream = getSupplier(COM_DATADOG_APPSEC_STACK_SUPPLIER);
    // Then
    assertTrue(
        stream
            .get()
            .noneMatch(
                stackTraceElement ->
                    stackTraceElement.getClassName().equals(COM_DATADOG_APPSEC_STACK_SUPPLIER)));
  }

  public static class StackTarget implements Supplier<Stream<StackTraceElement>> {
    @Override
    public Stream<StackTraceElement> get() {
      return Stream.empty();
    }
  }

  private static Supplier<Stream<StackTraceElement>> getSupplier(final String clazzName)
      throws Exception {
    DynamicType.Unloaded<?> unloadedType =
        new ByteBuddy()
            .subclass(StackTarget.class)
            .name(clazzName)
            .method(isPublic().and(named("get")))
            .intercept(MethodCall.call(StackWalkerTest::getStack))
            .make();
    DynamicType.Loaded<?> loaded =
        unloadedType.load(Thread.currentThread().getContextClassLoader());
    return (Supplier<Stream<StackTraceElement>>) loaded.getLoaded().getConstructor().newInstance();
  }

  public static Stream<StackTraceElement> getStack() {
    return StackWalker.INSTANCE.walk();
  }
}
