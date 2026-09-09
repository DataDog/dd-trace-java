package datadog.trace.agent.tooling;

import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.utility.OpenedClassReader.ASM_API;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.TypePath;
import net.bytebuddy.utility.JavaModule;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class DebuggingAdviceTransformerTest {

  @Test
  void doesNotChangeGeneratedBytecode() {
    byte[] regular = transform(new AgentBuilder.Transformer.ForAdvice(), ValidAdvice.class);
    byte[] debugging =
        transform(
            new DebuggingAdviceTransformer(
                Advice.withCustomMapping(), "test.Instrumentation", ValidAdvice.class.getName()),
            ValidAdvice.class);

    assertArrayEquals(regular, debugging);
  }

  @Test
  void preservesOriginalBindingFailureAsCause() {
    RuntimeException failure = new IllegalStateException("binding failed");
    AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper delegate =
        (type, method, visitor, context, typePool, writerFlags, readerFlags) -> {
          throw failure;
        };

    RuntimeException thrown =
        assertThrows(RuntimeException.class, () -> wrapTargetMethod(delegate));

    assertTrue(thrown instanceof DebuggingAdviceTransformer.AdviceTransformationException);
    assertSame(failure, thrown.getCause());
  }

  @Test
  void preservesLinkageFailureAsCause() {
    LinkageError failure = new NoClassDefFoundError("missing.AdviceDependency");
    AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper delegate =
        (type, method, visitor, context, typePool, writerFlags, readerFlags) -> {
          throw failure;
        };

    RuntimeException thrown =
        assertThrows(RuntimeException.class, () -> wrapTargetMethod(delegate));

    assertTrue(thrown instanceof DebuggingAdviceTransformer.AdviceTransformationException);
    assertSame(failure, thrown.getCause());
  }

  @Test
  void preservesLinkageFailureRaisedDuringAdviceApplicationAsCause() {
    LinkageError failure = new NoClassDefFoundError("missing.AdviceDependency");
    AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper delegate =
        (type, method, visitor, context, typePool, writerFlags, readerFlags) ->
            new MethodVisitor(ASM_API) {
              @Override
              public void visitMaxs(int maxStack, int maxLocals) {
                throw failure;
              }
            };

    MethodVisitor visitor = wrapTargetMethod(delegate);
    RuntimeException thrown = assertThrows(RuntimeException.class, () -> visitor.visitMaxs(0, 0));

    assertTrue(thrown instanceof DebuggingAdviceTransformer.AdviceTransformationException);
    assertSame(failure, thrown.getCause());
  }

  @Test
  void doesNotAttributeDownstreamRuntimeFailureToAdvice() {
    RuntimeException failure = new IllegalStateException("custom visitor failed");
    MethodVisitor downstream =
        new MethodVisitor(ASM_API) {
          @Override
          public void visitMaxs(int maxStack, int maxLocals) {
            throw failure;
          }
        };

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () -> wrapTargetMethod(forwardingVisitor(), downstream).visitMaxs(0, 0));

    assertSame(failure, thrown);
  }

  @Test
  void doesNotAttributeDownstreamLinkageFailureToAdvice() {
    LinkageError failure = new NoClassDefFoundError("missing.CustomVisitorDependency");
    MethodVisitor downstream =
        new MethodVisitor(ASM_API) {
          @Override
          public void visitMaxs(int maxStack, int maxLocals) {
            throw failure;
          }
        };

    LinkageError thrown =
        assertThrows(
            LinkageError.class,
            () -> wrapTargetMethod(forwardingVisitor(), downstream).visitMaxs(0, 0));

    assertSame(failure, thrown);
  }

  @Test
  void attributesLocalVariableAnnotationFailureToAdvice() {
    RuntimeException failure = new IllegalStateException("annotation binding failed");
    AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper delegate =
        (type, method, visitor, context, typePool, writerFlags, readerFlags) ->
            localVariableAnnotationFailureVisitor(null, failure);

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class, () -> visitLocalVariableAnnotation(wrapTargetMethod(delegate)));

    assertTrue(thrown instanceof DebuggingAdviceTransformer.AdviceTransformationException);
    assertSame(failure, thrown.getCause());
  }

  @Test
  void doesNotAttributeDownstreamLocalVariableAnnotationFailureToAdvice() {
    RuntimeException failure = new IllegalStateException("custom annotation visitor failed");
    MethodVisitor downstream = localVariableAnnotationFailureVisitor(null, failure);

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () -> visitLocalVariableAnnotation(wrapTargetMethod(forwardingVisitor(), downstream)));

    assertSame(failure, thrown);
  }

  @Test
  void logsInvalidArgumentWithFullTransformationContext() {
    AgentBuilder.Transformer.ForAdvice transformer =
        debuggingTransformer(InvalidArgumentAdvice.class);

    Logger debugControl = (Logger) LoggerFactory.getLogger(AgentInstaller.class);
    Logger logger = (Logger) LoggerFactory.getLogger(AgentInstaller.TransformLoggingListener.class);
    Level previousDebugControlLevel = debugControl.getLevel();
    Level previousLoggerLevel = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    InstrumenterFlare.resetTransformationErrors();
    try {
      debugControl.setLevel(Level.DEBUG);
      logger.setLevel(Level.DEBUG);
      RuntimeException failure =
          assertThrows(RuntimeException.class, () -> materialize(transformer));
      assertTrue(failure instanceof DebuggingAdviceTransformer.AdviceTransformationException);
      assertTrue(failure.getCause().getMessage().contains("does not define an index 1"));

      new AgentInstaller.TransformLoggingListener()
          .onError(
              Target.class.getName(),
              Target.class.getClassLoader(),
              JavaModule.ofType(Target.class),
              false,
              failure);

      assertEquals(1, appender.list.size());
      ILoggingEvent event = appender.list.get(0);
      assertSame(EXCLUDE_TELEMETRY, event.getMarker());
      assertTrue(event.getFormattedMessage().contains("test.Instrumentation"));
      assertTrue(event.getFormattedMessage().contains(InvalidArgumentAdvice.class.getName()));
      assertTrue(event.getFormattedMessage().contains(Target.class.getName()));
      assertTrue(
          event.getFormattedMessage().contains("greet(Ljava/lang/String;)Ljava/lang/String;"));
      assertTrue(event.getFormattedMessage().contains("instrumentation.target.loaded=false"));
      assertEquals(failure.getCause().getMessage(), event.getThrowableProxy().getMessage());
      String flareErrors = InstrumenterFlare.transformationErrors();
      assertTrue(flareErrors.contains(InvalidArgumentAdvice.class.getName()));
      assertTrue(flareErrors.contains("greet(Ljava/lang/String;)Ljava/lang/String;"));
      assertTrue(flareErrors.contains("does not define an index 1"));
    } finally {
      InstrumenterFlare.resetTransformationErrors();
      logger.detachAppender(appender);
      appender.stop();
      logger.setLevel(previousLoggerLevel);
      debugControl.setLevel(previousDebugControlLevel);
    }
  }

  @Test
  void attributesStackedAdviceFailureOnlyToFailingAdvice() {
    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () ->
                materialize(
                    debuggingTransformer(InvalidArgumentAdvice.class),
                    debuggingTransformer(ValidAdvice.class)));

    assertTrue(failure instanceof DebuggingAdviceTransformer.AdviceTransformationException);
    DebuggingAdviceTransformer.AdviceTransformationException diagnostic =
        (DebuggingAdviceTransformer.AdviceTransformationException) failure;
    assertEquals(InvalidArgumentAdvice.class.getName(), diagnostic.getAdviceClass());
    assertTrue(diagnostic.getCause().getMessage().contains("does not define an index 1"));
  }

  private AgentBuilder.Transformer.ForAdvice debuggingTransformer(Class<?> adviceClass) {
    return new DebuggingAdviceTransformer(
            Advice.withCustomMapping(), "test.Instrumentation", adviceClass.getName())
        .include(getClass().getClassLoader())
        .advice(named("greet"), adviceClass.getName());
  }

  private MethodVisitor wrapTargetMethod(
      AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper delegate) {
    return wrapTargetMethod(delegate, null);
  }

  private MethodVisitor wrapTargetMethod(
      AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper delegate,
      MethodVisitor methodVisitor) {
    return DebuggingAdviceTransformer.wrap(
            delegate, "test.Instrumentation", InvalidArgumentAdvice.class.getName())
        .wrap(
            new TypeDescription.ForLoadedType(Target.class),
            new MethodDescription.ForLoadedMethod(getTargetMethod()),
            methodVisitor,
            null,
            null,
            0,
            0);
  }

  private AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper forwardingVisitor() {
    return (type, method, visitor, context, typePool, writerFlags, readerFlags) ->
        new MethodVisitor(ASM_API, visitor) {};
  }

  private MethodVisitor localVariableAnnotationFailureVisitor(
      MethodVisitor delegate, RuntimeException failure) {
    return new MethodVisitor(ASM_API, delegate) {
      @Override
      public AnnotationVisitor visitLocalVariableAnnotation(
          int typeRef,
          TypePath typePath,
          Label[] start,
          Label[] end,
          int[] index,
          String descriptor,
          boolean visible) {
        throw failure;
      }
    };
  }

  private void visitLocalVariableAnnotation(MethodVisitor visitor) {
    Label start = new Label();
    Label end = new Label();
    visitor.visitLocalVariableAnnotation(
        0, null, new Label[] {start}, new Label[] {end}, new int[] {0}, "Ltest/Marker;", true);
  }

  private Method getTargetMethod() {
    try {
      return Target.class.getDeclaredMethod("greet", String.class);
    } catch (NoSuchMethodException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private byte[] transform(AgentBuilder.Transformer.ForAdvice transformer, Class<?> adviceClass) {
    return materialize(
        transformer
            .include(getClass().getClassLoader())
            .advice(named("greet"), adviceClass.getName()));
  }

  private byte[] materialize(AgentBuilder.Transformer.ForAdvice... transformers) {
    TypeDescription target = new TypeDescription.ForLoadedType(Target.class);
    DynamicType.Builder<?> builder = new ByteBuddy().redefine(Target.class);
    ProtectionDomain protectionDomain = Target.class.getProtectionDomain();
    for (AgentBuilder.Transformer.ForAdvice transformer : transformers) {
      builder =
          transformer.transform(
              builder,
              target,
              Target.class.getClassLoader(),
              JavaModule.ofType(Target.class),
              protectionDomain);
    }
    return builder.make().getBytes();
  }

  static class Target {
    public String greet(String name) {
      return name;
    }
  }

  static class ValidAdvice {
    @Advice.OnMethodEnter
    public static void enter(@Advice.Argument(0) String name) {}
  }

  static class InvalidArgumentAdvice {
    @Advice.OnMethodEnter
    public static void enter(@Advice.Argument(1) String name) {}
  }
}
