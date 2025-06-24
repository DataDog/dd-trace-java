package datadog.trace.agent.tooling.iast;

import static datadog.trace.api.iast.telemetry.IastMetric.EXECUTED_PROPAGATION;
import static datadog.trace.api.iast.telemetry.IastMetric.EXECUTED_SINK;
import static datadog.trace.api.iast.telemetry.IastMetric.EXECUTED_SOURCE;
import static datadog.trace.api.iast.telemetry.IastMetric.INSTRUMENTED_PROPAGATION;
import static datadog.trace.api.iast.telemetry.IastMetric.INSTRUMENTED_SINK;
import static datadog.trace.api.iast.telemetry.IastMetric.INSTRUMENTED_SOURCE;
import static net.bytebuddy.jar.asm.Opcodes.BIPUSH;
import static net.bytebuddy.jar.asm.Opcodes.GETSTATIC;
import static net.bytebuddy.jar.asm.Opcodes.ICONST_0;
import static net.bytebuddy.jar.asm.Opcodes.ICONST_1;
import static net.bytebuddy.jar.asm.Opcodes.ICONST_M1;
import static net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC;

import datadog.trace.api.Config;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.telemetry.IastMetric;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.util.Strings;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationValue;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.PackageDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Type;

public class IastPostProcessorFactory implements Advice.PostProcessor.Factory {

  public static final Advice.PostProcessor.Factory INSTANCE;

  static {
    final Verbosity verbosity = Config.get().getIastTelemetryVerbosity();
    INSTANCE = verbosity == Verbosity.OFF ? null : new IastPostProcessorFactory(verbosity);
  }

  private static final String IAST_ANNOTATIONS_PKG = Strings.getPackageName(Sink.class.getName());
  private static final String SINK_NAME = Sink.class.getSimpleName();
  private static final String PROPAGATION_NAME = Propagation.class.getSimpleName();
  private static final String SOURCE_NAME = Source.class.getSimpleName();

  private static final String COLLECTOR_INTERNAL_NAME =
      Type.getType(IastMetricCollector.class).getInternalName();
  private static final String METRIC_INTERNAL_NAME =
      Type.getType(IastMetric.class).getInternalName();
  private static final String METRIC_DESCRIPTOR = "L" + METRIC_INTERNAL_NAME + ";";
  private static final String ADD_DESCRIPTOR = "(" + METRIC_DESCRIPTOR + "I)V";
  private static final String ADD_WITH_TAG_DESCRIPTOR = "(" + METRIC_DESCRIPTOR + "BI)V";

  private final Verbosity verbosity;

  public IastPostProcessorFactory(final Verbosity verbosity) {
    this.verbosity = verbosity;
  }

  @Override
  public @Nonnull Advice.PostProcessor make(
      List<? extends AnnotationDescription> annotations, TypeDescription returnType, boolean exit) {
    for (final AnnotationDescription annotation : annotations) {
      final TypeDescription typeDescr = annotation.getAnnotationType();
      final PackageDescription pkgDescr = typeDescr.getPackage();
      if (pkgDescr != null && IAST_ANNOTATIONS_PKG.equals(pkgDescr.getName())) {
        final String typeName = typeDescr.getSimpleName();
        if (SINK_NAME.equals(typeName)) {
          final AnnotationValue<?, ?> tag = annotation.getValue("value");
          return createPostProcessor(INSTRUMENTED_SINK, EXECUTED_SINK, tag.resolve(Byte.class));
        } else if (SOURCE_NAME.equals(typeName)) {
          final AnnotationValue<?, ?> tag = annotation.getValue("value");
          return createPostProcessor(INSTRUMENTED_SOURCE, EXECUTED_SOURCE, tag.resolve(Byte.class));
        } else if (PROPAGATION_NAME.equals(typeName)) {
          return createPostProcessor(INSTRUMENTED_PROPAGATION, EXECUTED_PROPAGATION, null);
        }
      }
    }
    return Advice.PostProcessor.NoOp.INSTANCE;
  }

  private PostProcessor createPostProcessor(
      final IastMetric instrumented, final IastMetric executed, final Byte tagValue) {
    if (!executed.isEnabled(verbosity)) {
      return new PostProcessor(instrumented, null, tagValue);
    }
    return new PostProcessor(instrumented, executed, tagValue);
  }

  private static class PostProcessor implements Advice.PostProcessor {

    private final IastMetric instrumentation;
    private final IastMetric runtime;
    private final Byte tagValue;

    private PostProcessor(
        final IastMetric instrumentation, final IastMetric runtime, final Byte tagValue) {
      this.instrumentation = instrumentation;
      this.runtime = runtime;
      this.tagValue = tagValue;
    }

    @Override
    public @Nonnull StackManipulation resolve(
        @Nonnull final TypeDescription instrumentedType,
        @Nonnull final MethodDescription instrumentedMethod,
        @Nonnull final Assigner assigner,
        @Nonnull final Advice.ArgumentHandler argumentHandler,
        @Nonnull final Advice.StackMapFrameHandler.ForPostProcessor stackMapFrameHandler,
        @Nonnull final StackManipulation exceptionHandler) {
      if (tagValue == null) {
        IastMetricCollector.add(instrumentation, 1);
      } else {
        IastMetricCollector.add(instrumentation, (byte) tagValue, 1);
      }
      return runtime == null
          ? StackManipulation.Trivial.INSTANCE
          : new TelemetryStackManipulation(stackMapFrameHandler, runtime.name(), tagValue);
    }
  }

  private static class TelemetryStackManipulation extends StackManipulation.AbstractBase {
    private final Advice.StackMapFrameHandler.ForAdvice.ForPostProcessor stackMapFrameHandler;
    private final String metricName;
    private final Byte tagValue;

    private TelemetryStackManipulation(
        final Advice.StackMapFrameHandler.ForPostProcessor stackMapFrameHandler,
        final String metricName,
        final Byte tagValue) {
      this.stackMapFrameHandler = stackMapFrameHandler;
      this.metricName = metricName;
      this.tagValue = tagValue;
    }

    @Override
    public @Nonnull Size apply(
        @Nonnull final MethodVisitor mv, @Nonnull final Implementation.Context ctx) {
      stackMapFrameHandler.injectIntermediateFrame(mv, Collections.emptyList());
      mv.visitFieldInsn(GETSTATIC, METRIC_INTERNAL_NAME, metricName, METRIC_DESCRIPTOR);
      final String descriptor;
      if (tagValue != null) {
        visitTag(mv, tagValue);
        descriptor = ADD_WITH_TAG_DESCRIPTOR;
      } else {
        descriptor = ADD_DESCRIPTOR;
      }
      mv.visitInsn(ICONST_1);
      mv.visitMethodInsn(INVOKESTATIC, COLLECTOR_INTERNAL_NAME, "add", descriptor, false);
      return new Size(0, tagValue != null ? 3 : 2);
    }

    private void visitTag(final MethodVisitor mv, final byte tagValue) {
      switch (tagValue) {
        case -1:
          mv.visitInsn(ICONST_M1);
          break;
        case 0:
        case 1:
        case 2:
        case 3:
        case 4:
        case 5:
          mv.visitInsn(ICONST_0 + tagValue);
          break;
        default:
          mv.visitIntInsn(BIPUSH, tagValue);
      }
    }
  }
}
