package datadog.trace.agent.tooling.iast

import datadog.trace.api.iast.telemetry.IastMetric
import datadog.trace.api.iast.telemetry.IastMetricCollector
import datadog.trace.test.util.DDSpecification
import net.bytebuddy.asm.Advice
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.implementation.bytecode.StackManipulation
import net.bytebuddy.implementation.bytecode.assign.Assigner
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type
import spock.lang.Shared

class IastPostProcessorFactoryTest extends DDSpecification {

  @Shared
  protected static final IastMetricCollector ORIGINAL_COLLECTOR = IastMetricCollector.INSTANCE

  private static final Type COLLECTOR_TYPE = Type.getType(IastMetricCollector)
  private static final Type METRIC_TYPE = Type.getType(IastMetric)

  static class NonAnnotatedAdvice {
    @Advice.OnMethodExit
    static void exit() {}
  }

  void setup() {
    IastMetricCollector.register(new IastMetricCollector())
  }

  void cleanup() {
    IastMetricCollector.register(ORIGINAL_COLLECTOR)
  }

  void 'test factory for non annotated'() {
    given:
    final method = new MethodDescription.ForLoadedMethod(NonAnnotatedAdvice.getDeclaredMethod('exit'))

    when:
    final result = IastPostProcessorFactory.INSTANCE.make(
      method.getDeclaredAnnotations(), method.getReturnType().asErasure(), true)

    then:
    result == Advice.PostProcessor.NoOp.INSTANCE
  }

  void 'test factory for annotated advice'() {
    given:
    final collector = IastMetricCollector.get()
    final method = new MethodDescription.ForLoadedMethod(IastAnnotatedAdvice.getDeclaredMethod('exit'))
    final typeDescription = Mock(TypeDescription)
    final methodDescription = Mock(MethodDescription)
    final assigner = Mock(Assigner)
    final argumentHandler = Mock(Advice.ArgumentHandler)
    final forPostProcessor = Mock(Advice.StackMapFrameHandler.ForPostProcessor)
    final stackManipulation = Mock(StackManipulation)
    final methodVisitor = Mock(MethodVisitor)
    final context = Mock(Implementation.Context)

    when:
    final postProcessor = IastPostProcessorFactory.INSTANCE.make(
      method.getDeclaredAnnotations(), method.getReturnType().asErasure(), true)

    then:
    postProcessor != Advice.PostProcessor.NoOp.INSTANCE

    when: 'a new advice is handled'
    final manipulation = postProcessor.resolve(typeDescription, methodDescription, assigner, argumentHandler, forPostProcessor, stackManipulation)

    then: 'a new method has been instrumented and the metric is generated'
    manipulation != null
    collector.prepareMetrics()
    final metrics = collector.drain()
    assert metrics.size() == 1
    // one method has ben instrumented
    with(metrics.first()) {
      it.metric == IastMetric.INSTRUMENTED_SINK
      it.tags == ['vulnerability_type:SQL_INJECTION']
      it.value == 1L
    }

    when: 'the advice is used'
    final size = manipulation.apply(methodVisitor, context)

    then: 'the byte code to generate the metric during runtime is appended'
    size.sizeImpact == 0 // stack remains the same
    size.maximalSize == 3 // metric + tag + counter
    1 * forPostProcessor.injectIntermediateFrame(methodVisitor, []) // new empty frame
    1 * methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, METRIC_TYPE.internalName, 'EXECUTED_SINK', 'L' + METRIC_TYPE.internalName + ';') // add executed metric to stack
    1 * methodVisitor.visitInsn(Opcodes.ICONST_2) // add tag to stack: public static final byte SQL_INJECTION = 2
    1 * methodVisitor.visitInsn(Opcodes.ICONST_1) // add counter to stack
    1 * methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, COLLECTOR_TYPE.internalName, 'add', '(L' + METRIC_TYPE.internalName + ';BI)V', false) // call increment
  }
}
