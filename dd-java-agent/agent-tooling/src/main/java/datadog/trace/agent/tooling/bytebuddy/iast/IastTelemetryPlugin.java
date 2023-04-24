package datadog.trace.agent.tooling.bytebuddy.iast;

import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.api.Config;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.telemetry.IastMetric;
import datadog.trace.api.iast.telemetry.IastTelemetryCollector;
import datadog.trace.api.iast.telemetry.Verbosity;
import java.io.File;
import java.io.IOException;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

public class IastTelemetryPlugin extends Plugin.ForElementMatcher {

  public IastTelemetryPlugin(File targetDir) {
    super(getElementMatcher());
  }

  private static ElementMatcher<? super TypeDescription> getElementMatcher() {
    final Config config = Config.get();
    final boolean isEnabled =
        config.getIastActivation() != ProductActivation.FULLY_DISABLED
            && config.getIastTelemetryVerbosity() != Verbosity.OFF;
    if (isEnabled) {
      return HierarchyMatchers.declaresAnnotation(
          NameMatchers.namedOneOf(
              IastAdvice.Source.class.getName(),
              IastAdvice.Propagation.class.getName(),
              IastAdvice.Sink.class.getName()));
    } else {
      return ElementMatchers.none();
    }
  }

  @Override
  public DynamicType.Builder<?> apply(
      final DynamicType.Builder<?> builder,
      final TypeDescription typeDescription,
      final ClassFileLocator classFileLocator) {
    final AnnotationList annotations = typeDescription.getDeclaredAnnotations();
    for (final AnnotationDescription annotation : annotations) {
      final TypeDescription typeDesc = annotation.getAnnotationType();
      if (typeDesc.isAssignableTo(IastAdvice.Source.class)) {
        final IastAdvice.Source source = annotation.prepare(IastAdvice.Source.class).load();
        IastTelemetryCollector.add(IastMetric.INSTRUMENTED_SOURCE, 1, source.value());
      } else if (typeDesc.isAssignableTo(IastAdvice.Propagation.class)) {
        final IastAdvice.Propagation propagation =
            annotation.prepare(IastAdvice.Propagation.class).load();
        IastTelemetryCollector.add(IastMetric.INSTRUMENTED_PROPAGATION, 1, propagation.value());
      } else if (typeDesc.isAssignableTo(IastAdvice.Sink.class)) {
        final IastAdvice.Sink sink = annotation.prepare(IastAdvice.Sink.class).load();
        IastTelemetryCollector.add(IastMetric.INSTRUMENTED_SINK, 1, sink.value());
      }
    }
    return builder;
  }

  @Override
  public void close() throws IOException {}
}
