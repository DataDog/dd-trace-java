package datadog.trace.instrumentation.trace_annotation;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeHasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.instrumentation.trace_annotation.TraceConfigInstrumentation.PACKAGE_CLASS_NAME_REGEX;
import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(Instrumenter.class)
public final class TraceAnnotationsInstrumentation extends Instrumenter.Tracing {

  private static final Logger log = LoggerFactory.getLogger(TraceAnnotationsInstrumentation.class);

  static final String CONFIG_FORMAT =
      "(?:\\s*"
          + PACKAGE_CLASS_NAME_REGEX
          + "\\s*;)*\\s*"
          + PACKAGE_CLASS_NAME_REGEX
          + "\\s*;?\\s*";

  private final Set<String> annotations;
  private final ElementMatcher.Junction<NamedElement> methodTraceMatcher;

  @SuppressForbidden
  public TraceAnnotationsInstrumentation() {
    super("trace", "trace-annotation");
    Set<String> annotations = new HashSet<>();
    annotations.add("datadog.trace.api.Trace");
    final String configString = Config.get().getTraceAnnotations();
    if (configString == null) {
      annotations.addAll(
          Arrays.asList(
              "com.newrelic.api.agent.Trace",
              "kamon.annotation.Trace",
              "com.tracelytics.api.ext.LogMethod",
              "io.opentracing.contrib.dropwizard.Trace",
              "org.springframework.cloud.sleuth.annotation.NewSpan"));
    } else if (!configString.matches(CONFIG_FORMAT)) {
      log.warn(
          "Invalid trace annotations config '{}'. Must match 'package.Annotation$Name;*'.",
          configString);
    } else if (!configString.trim().isEmpty()) {
      final String[] annotationClasses = configString.split(";", -1);
      for (final String annotationClass : annotationClasses) {
        if (!annotationClass.trim().isEmpty()) {
          annotations.add(annotationClass.trim());
        }
      }
    }
    this.annotations = annotations;
    this.methodTraceMatcher = namedOneOf(annotations);
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    ElementMatcher.Junction<ClassLoader> matcher = hasClassesNamed("datadog.trace.api.Trace");
    for (final String name : annotations) {
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
