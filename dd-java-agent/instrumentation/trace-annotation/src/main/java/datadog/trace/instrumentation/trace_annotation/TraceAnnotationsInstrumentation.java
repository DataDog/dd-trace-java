package datadog.trace.instrumentation.trace_annotation;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeHasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.instrumentation.trace_annotation.TraceConfigInstrumentation.PACKAGE_CLASS_NAME_REGEX;
import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.is;
import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.api.Trace;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@Slf4j
@AutoService(Instrumenter.class)
public final class TraceAnnotationsInstrumentation extends Instrumenter.Tracing {

  static final String CONFIG_FORMAT =
      "(?:\\s*"
          + PACKAGE_CLASS_NAME_REGEX
          + "\\s*;)*\\s*"
          + PACKAGE_CLASS_NAME_REGEX
          + "\\s*;?\\s*";

  private static final String[] DEFAULT_ANNOTATIONS = {
    "com.newrelic.api.agent.Trace",
    "kamon.annotation.Trace",
    "com.tracelytics.api.ext.LogMethod",
    "io.opentracing.contrib.dropwizard.Trace",
    "org.springframework.cloud.sleuth.annotation.NewSpan"
  };

  private static final String[] EMPTY = new String[0];
  private final String[] additionalTraceAnnotations;
  private final ElementMatcher.Junction<NamedElement> methodTraceMatcher;

  public TraceAnnotationsInstrumentation() {
    super("trace", "trace-annotation");

    final String configString = Config.get().getTraceAnnotations();
    if (configString == null) {
      additionalTraceAnnotations = DEFAULT_ANNOTATIONS;
    } else if (configString.trim().isEmpty()) {
      additionalTraceAnnotations = EMPTY;
    } else if (!configString.matches(CONFIG_FORMAT)) {
      log.warn(
          "Invalid trace annotations config '{}'. Must match 'package.Annotation$Name;*'.",
          configString);
      additionalTraceAnnotations = EMPTY;
    } else {
      final String[] annotationClasses = configString.split(";", -1);
      final Set<String> annotations = new HashSet<>(annotationClasses.length);
      for (final String annotationClass : annotationClasses) {
        if (!annotationClass.trim().isEmpty()) {
          annotations.add(annotationClass.trim());
        }
      }
      additionalTraceAnnotations = annotations.toArray(EMPTY);
    }

    ElementMatcher.Junction<NamedElement> methodTraceMatcher =
        is(new TypeDescription.ForLoadedType(Trace.class))
            .or(namedOneOf(additionalTraceAnnotations));
    this.methodTraceMatcher = methodTraceMatcher;
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    ElementMatcher.Junction<ClassLoader> matcher = hasClassesNamed(Trace.class.getName());
    for (final String name : additionalTraceAnnotations) {
      matcher = matcher.or(hasClassesNamed(name));
    }
    return matcher;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return safeHasSuperType(declaresMethod(isAnnotatedWith(methodTraceMatcher)));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TraceDecorator",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
        isAnnotatedWith(methodTraceMatcher), packageName + ".TraceAdvice");
  }
}
