package datadog.trace.instrumentation.trace_annotation;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.isAnnotatedWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.api.Config;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.LoggerFactory;

@AutoService(Instrumenter.class)
public final class TraceAnnotationsInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  static final String CONFIG_FORMAT = "(?:\\s*[\\w.$]+\\s*;)*\\s*[\\w.$]+\\s*;?\\s*";

  private final NameMatchers.OneOf<NamedElement> methodTraceMatcher;

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
      LoggerFactory.getLogger(TraceAnnotationsInstrumentation.class)
          .warn(
              "Invalid trace annotations config '{}'. Must match 'package.Annotation$Name;*'.",
              configString);
    } else {
      int start = 0;
      do {
        int next = configString.indexOf(';', start + 1);
        int end = next == -1 ? configString.length() : next;
        String annotation = configString.substring(start, end).trim();
        if (!annotation.isEmpty()) {
          annotations.add(annotation);
        }
        start = next + 1;
      } while (start != 0);
    }
    this.methodTraceMatcher = namedOneOf(annotations);
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return declaresMethod(isAnnotatedWith(methodTraceMatcher));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TraceDecorator",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isAnnotatedWith(methodTraceMatcher), packageName + ".TraceAdvice");
  }
}
