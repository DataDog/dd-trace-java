package datadog.trace.agent.tooling.iast;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static net.bytebuddy.jar.asm.Opcodes.GETSTATIC;
import static net.bytebuddy.jar.asm.Opcodes.ICONST_1;
import static net.bytebuddy.jar.asm.Opcodes.ICONST_2;
import static net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import datadog.trace.api.iast.telemetry.IastMetric;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.api.iast.telemetry.IastMetricCollector.IastMetricData;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.Collection;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Type;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IastPostProcessorFactoryTest extends DDJavaSpecification {

  private static final IastMetricCollector ORIGINAL_COLLECTOR = IastMetricCollector.get();

  private static final Type COLLECTOR_TYPE = Type.getType(IastMetricCollector.class);
  private static final Type METRIC_TYPE = Type.getType(IastMetric.class);

  static class NonAnnotatedAdvice {
    @Advice.OnMethodExit
    static void exit() {}
  }

  @BeforeEach
  void setup() {
    IastMetricCollector.register(new IastMetricCollector());
  }

  @AfterEach
  void cleanup() {
    IastMetricCollector.register(ORIGINAL_COLLECTOR);
  }

  @Test
  void factoryForNonAnnotated() throws NoSuchMethodException {
    // given
    MethodDescription method =
        new MethodDescription.ForLoadedMethod(NonAnnotatedAdvice.class.getDeclaredMethod("exit"));

    // when
    Advice.PostProcessor result =
        IastPostProcessorFactory.INSTANCE.make(
            method.getDeclaredAnnotations(), method.getReturnType().asErasure(), true);

    // then
    assertEquals(Advice.PostProcessor.NoOp.INSTANCE, result);
  }

  @Test
  void factoryForAnnotatedAdvice() throws NoSuchMethodException {
    // given
    IastMetricCollector collector = IastMetricCollector.get();
    MethodDescription method =
        new MethodDescription.ForLoadedMethod(IastAnnotatedAdvice.class.getDeclaredMethod("exit"));
    TypeDescription typeDescription = mock(TypeDescription.class);
    MethodDescription methodDescription = mock(MethodDescription.class);
    Assigner assigner = mock(Assigner.class);
    Advice.ArgumentHandler argumentHandler = mock(Advice.ArgumentHandler.class);
    Advice.StackMapFrameHandler.ForPostProcessor forPostProcessor =
        mock(Advice.StackMapFrameHandler.ForPostProcessor.class);
    StackManipulation stackManipulation = mock(StackManipulation.class);
    MethodVisitor methodVisitor = mock(MethodVisitor.class);
    Implementation.Context context = mock(Implementation.Context.class);

    // when
    Advice.PostProcessor postProcessor =
        IastPostProcessorFactory.INSTANCE.make(
            method.getDeclaredAnnotations(), method.getReturnType().asErasure(), true);

    // then
    assertNotEquals(Advice.PostProcessor.NoOp.INSTANCE, postProcessor);

    // when: a new advice is handled
    StackManipulation manipulation =
        postProcessor.resolve(
            typeDescription,
            methodDescription,
            assigner,
            argumentHandler,
            forPostProcessor,
            stackManipulation);

    // then: a new method has been instrumented and the metric is generated
    assertNotNull(manipulation);
    collector.prepareMetrics();
    Collection<IastMetricData> metrics = collector.drain();
    assertEquals(1, metrics.size());
    // one method has ben instrumented
    IastMetricData metric = metrics.iterator().next();
    assertEquals(IastMetric.INSTRUMENTED_SINK, metric.getMetric());
    assertEquals(singletonList("vulnerability_type:SQL_INJECTION"), metric.tags);
    assertEquals(1L, metric.value);

    // when: the advice is used
    StackManipulation.Size size = manipulation.apply(methodVisitor, context);

    // then: the byte code to generate the metric during runtime is appended
    assertEquals(0, size.getSizeImpact()); // stack remains the same
    assertEquals(3, size.getMaximalSize()); // metric + tag + counter
    verify(forPostProcessor).injectIntermediateFrame(methodVisitor, emptyList()); // new empty frame
    verify(methodVisitor)
        .visitFieldInsn(
            GETSTATIC,
            METRIC_TYPE.getInternalName(),
            "EXECUTED_SINK",
            "L" + METRIC_TYPE.getInternalName() + ";"); // add executed metric to stack
    // add tag to stack: public static final byte SQL_INJECTION = 2
    verify(methodVisitor).visitInsn(ICONST_2);
    verify(methodVisitor).visitInsn(ICONST_1); // add counter to stack
    verify(methodVisitor)
        .visitMethodInsn(
            INVOKESTATIC,
            COLLECTOR_TYPE.getInternalName(),
            "add",
            "(L" + METRIC_TYPE.getInternalName() + ";BI)V",
            false); // call increment
  }
}
