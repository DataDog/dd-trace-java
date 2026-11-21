package datadog.trace.agent.tooling.csi;

import static datadog.trace.agent.tooling.csi.CallSiteAdvice.AdviceType.AFTER;
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.AdviceType.BEFORE;
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.PREPEND_ARRAY_CTOR;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteInstrumentation;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteTransformer;
import java.io.StringReader;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.modifier.Ownership;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.TypeCreation;
import net.bytebuddy.implementation.bytecode.member.MethodInvocation;
import net.bytebuddy.implementation.bytecode.member.MethodReturn;
import net.bytebuddy.implementation.bytecode.member.MethodVariableAccess;
import net.bytebuddy.jar.asm.Type;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class CallSiteInstrumentationTest extends BaseCallSiteTest {

  @Test
  void testInstrumentationAddsTypeAdvice() throws NoSuchMethodException {
    // setup
    CallSites advice = mockCallSites(mock(InvokeAdvice.class), stringConcatPointcut());
    CallSiteInstrumentation instrumentation = buildInstrumentation(singletonList(advice));
    DynamicType.Builder<?> builder = mockBuilder();
    TypeDescription type = mockType();

    // when
    AtomicReference<DynamicType.Builder<?>> result = new AtomicReference<>();
    instrumentation.typeAdvice(transformingAdvice ->
        result.set(
            transformingAdvice.transform(builder, type, getClass().getClassLoader(), null, null)));

    // then
    assertSame(builder, result.get());
    verify(builder).visit(any(AsmVisitorWrapper.class));
  }

  @Test
  void testFetchAdvicesFromSpiWithCustomClass() {
    // setup
    CallSiteInstrumentation instrumentation = buildInstrumentation(TestCallSites.class);
    DynamicType.Builder<?> builder = mockBuilder();
    TypeDescription type = mockType();

    // when
    instrumentation.typeAdvice(transformingAdvice ->
        transformingAdvice.transform(builder, type, getClass().getClassLoader(), null, null));

    // then
    verify(builder).visit(any(AsmVisitorWrapper.class));
  }

  @Test
  void testFetchAdvicesFromSpiWithNoImplementations() {
    // setup
    CallSiteInstrumentation instrumentation = buildInstrumentation(CallSiteAdvice.class);
    DynamicType.Builder<?> builder = mockBuilder();
    TypeDescription type = mockType();

    // when
    instrumentation.typeAdvice(transformingAdvice ->
        transformingAdvice.transform(builder, type, getClass().getClassLoader(), null, null));

    // then
    verify(builder, never()).visit(any(AsmVisitorWrapper.class));
  }

  @TableTest({
    "scenario                     | parameterType          ",
    "Operand stack underflow      | java.lang.String       ",
    "Inconsistent stackmap frames | java.lang.StringBuilder"
  })
  void testCallSiteTransformerWithSuperCallInCtor(Class<?> parameterType) throws Exception {
    // setup
    Object param = parameterType == String.class ? "test" : new StringBuilder("test");
    SuperInCtorExampleAdvice.CALLS.set(0);
    Type source = Type.getType(SuperInCtorExample.class);
    Type target = renameType(source, "Test");
    Pointcut pointcut = stringReaderPointcut();
    InvokeAdvice advice =
        new InvokeAdvice() {
          @Override
          public void apply(
              CallSiteAdvice.MethodHandler handler,
              int opcode,
              String owner,
              String name,
              String descriptor,
              boolean isInterface) {
            handler.dupParameters(descriptor, PREPEND_ARRAY_CTOR);
            handler.method(opcode, owner, name, descriptor, isInterface);
            handler.advice(
                Type.getType(SuperInCtorExampleAdvice.class).getInternalName(),
                "onInvoke",
                Type.getMethodType(
                        Type.getType(StringReader.class),
                        Type.getType(Object[].class),
                        Type.getType(StringReader.class))
                    .getDescriptor());
          }
        };
    CallSiteTransformer callSiteTransformer =
        new CallSiteTransformer(
            buildAdvices(singletonList(mockCallSites(AFTER, advice, pointcut))));

    // when
    byte[] transformedClass = transformType(source, target, callSiteTransformer);
    Class<?> transformed = loadClass(target, transformedClass);
    Object reader = transformed.getConstructor(parameterType).newInstance(param);

    // then
    assertNotNull(reader);
    assertTrue(SuperInCtorExampleAdvice.CALLS.get() > 0);
  }

  @Test
  void testNewInvocationWithoutDup() throws Exception {
    // setup
    DynamicType.Loaded<?> consumer = buildConsumerWithNewAndNoDup();
    Type source = Type.getType(consumer.getLoaded());
    Type target = renameType(source, "Test");
    Pointcut pointcut = stringReaderPointcut();
    InvokeAdvice advice = mock(InvokeAdvice.class);
    CallSiteTransformer callSiteTransformer =
        new CallSiteTransformer(
            buildAdvices(singletonList(mockCallSites(AFTER, advice, pointcut))));

    // when
    byte[] transformedClass =
        transformType(source, consumer.getBytes(), target, callSiteTransformer);
    Class<?> transformed = loadClass(target, transformedClass);
    Object instance = transformed.getConstructor().newInstance();
    transformed.getMethod("accept", String.class).invoke(instance, "test");

    // then
    verifyNoInteractions(advice);
  }

  @SuppressWarnings("unchecked")
  private static DynamicType.Builder<?> mockBuilder() {
    DynamicType.Builder<Object> builder = mock(DynamicType.Builder.class);
    when(builder.visit(any(AsmVisitorWrapper.class))).thenReturn(builder);
    return builder;
  }

  private static TypeDescription mockType() {
    TypeDescription type = mock(TypeDescription.class);
    when(type.getName()).thenReturn(StringConcatExample.class.getName());
    return type;
  }

  private static DynamicType.Loaded<?> buildConsumerWithNewAndNoDup() throws NoSuchMethodException {
    MethodDescription stringReaderCtor =
        new MethodDescription.ForLoadedConstructor(StringReader.class.getConstructor(String.class));
    return new ByteBuddy()
        .subclass(Cloneable.class)
        .name("foo.CtorIssue")
        .modifiers(Visibility.PUBLIC)
        .defineMethod("accept", void.class, Visibility.PUBLIC, Ownership.MEMBER)
        .withParameters(String.class)
        .intercept(
            new Implementation.Simple((methodVisitor, context, method) -> {
              StackManipulation compound =
                  new StackManipulation.Compound(
                      TypeCreation.of(new TypeDescription.ForLoadedType(StringReader.class)),
                      // ignore DUP opcode
                      MethodVariableAccess.REFERENCE.loadFrom(1),
                      MethodInvocation.invoke(stringReaderCtor),
                      MethodReturn.VOID);
              StackManipulation.Size size = compound.apply(methodVisitor, context);
              return new ByteCodeAppender.Size(size.getMaximalSize(), method.getStackSize());
            }))
        .make()
        .load(Thread.currentThread().getContextClassLoader(), ClassLoadingStrategy.Default.WRAPPER);
  }

  /** Registered in {@code META-INF/services/datadog.trace.agent.tooling.csi.TestCallSites}. */
  public static class StringCallSites implements CallSites, TestCallSites {

    @Override
    public void accept(Container container) {
      Pointcut pointcut =
          buildPointcut("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;");
      container.addAdvice(
          BEFORE, pointcut.type, pointcut.method, pointcut.descriptor, new StringConcatAdvice());
    }
  }

  public static class StringConcatAdvice implements InvokeAdvice {

    @Override
    public void apply(
        MethodHandler handler,
        int opcode,
        String owner,
        String name,
        String descriptor,
        boolean isInterface) {
      handler.method(opcode, owner, name, descriptor, isInterface);
    }
  }

  public static class SuperInCtorExampleAdvice {

    private static final AtomicInteger CALLS = new AtomicInteger(0);

    public static StringReader onInvoke(Object[] args, StringReader result) {
      CALLS.incrementAndGet();
      return result;
    }
  }
}
