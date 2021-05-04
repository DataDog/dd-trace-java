package datadog.trace.instrumentation.trace_annotation;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeHasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isEquals;
import static net.bytebuddy.matcher.ElementMatchers.isFinalizer;
import static net.bytebuddy.matcher.ElementMatchers.isGetter;
import static net.bytebuddy.matcher.ElementMatchers.isHashCode;
import static net.bytebuddy.matcher.ElementMatchers.isSetter;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.isToString;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.api.Config;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TraceConfig Instrumentation does not extend Default.
 *
 * <p>Instead it directly implements Instrumenter#instrument() and adds one default Instrumenter for
 * every configured class+method-list.
 *
 * <p>If this becomes a more common use case the building logic should be abstracted out into a
 * super class.
 */
@AutoService(Instrumenter.class)
public class TraceConfigInstrumentation implements Instrumenter {

  private static final Logger log = LoggerFactory.getLogger(TraceConfigInstrumentation.class);

  private final Map<String, Set<String>> classMethodsToTrace;

  @SuppressForbidden
  public TraceConfigInstrumentation() {
    final String configString = Config.get().getTraceMethods();
    if (configString == null || configString.trim().isEmpty()) {
      classMethodsToTrace = Collections.emptyMap();

    } else if (!configString.matches(
        "(?:\\s*[\\w.$]+\\[\\s*(?:\\*|(?:\\w+\\s*,)*\\s*(?:\\w+\\s*,?))\\s*]\\s*;)*(?:\\s*[\\w.$]+\\[\\s*(?:\\*|(?:\\w+\\s*,)*\\s*(?:\\w+\\s*,?))\\s*]\\s*)?\\s*")) {
      log.warn(
          "Invalid trace method config '{}'. Must match 'package.Class$Name[method1,method2];*' or 'package.Class$Name[*];*'.",
          configString);
      classMethodsToTrace = Collections.emptyMap();

    } else {
      final Map<String, Set<String>> toTrace = new HashMap<>();
      int start = 0;
      do {
        int next = configString.indexOf(';', start + 1);
        int end = next == -1 ? configString.length() : next;
        if (end > start + 1) {
          int methodsStart = configString.indexOf('[', start);
          if (methodsStart == -1) {
            break; // reached trailing whitespace
          }
          int methodsEnd = configString.indexOf(']', methodsStart);
          String className = configString.substring(start, methodsStart).trim();
          Set<String> methodNames = toTrace.get(className);
          if (null == methodNames) {
            methodNames = new HashSet<>();
            toTrace.put(className, methodNames);
          }
          for (int methodStart = methodsStart + 1; methodStart < methodsEnd; ) {
            int nextComma = configString.indexOf(',', methodStart);
            int methodEnd = nextComma == -1 ? methodsEnd : nextComma;
            String method = configString.substring(methodStart, methodEnd).trim();
            if (!method.isEmpty()) {
              methodNames.add(method);
            }
            methodStart = methodEnd + 1;
          }
        }
        start = next + 1;
      } while (start != 0);
      classMethodsToTrace = Collections.unmodifiableMap(toTrace);
    }
  }

  @Override
  public AgentBuilder instrument(AgentBuilder agentBuilder) {
    if (classMethodsToTrace.isEmpty()) {
      return agentBuilder;
    }

    for (final Map.Entry<String, Set<String>> entry : classMethodsToTrace.entrySet()) {
      final TracerClassInstrumentation tracerConfigClass =
          new TracerClassInstrumentation(entry.getKey(), entry.getValue());
      agentBuilder = tracerConfigClass.instrument(agentBuilder);
    }
    return agentBuilder;
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    // don't care
    return true;
  }

  // Not Using AutoService to hook up this instrumentation
  public static class TracerClassInstrumentation extends Tracing {
    private final String className;
    private final Set<String> methodNames;

    /** No-arg constructor only used by muzzle and tests. */
    public TracerClassInstrumentation() {
      this("datadog.trace.api.Trace", Collections.singleton("noop"));
    }

    public TracerClassInstrumentation(final String className, final Set<String> methodNames) {
      super("trace", "trace-config");
      this.className = className;
      this.methodNames = methodNames;
    }

    @Override
    public ElementMatcher<ClassLoader> classLoaderMatcher() {
      // Optimization for expensive typeMatcher.
      return hasClassesNamed(className);
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return safeHasSuperType(named(className));
    }

    @Override
    public String[] helperClassNames() {
      return new String[] {
        packageName + ".TraceDecorator",
      };
    }

    @Override
    public void adviceTransformations(AdviceTransformation transformation) {
      boolean hasWildcard = false;
      for (String methodName : methodNames) {
        hasWildcard |= methodName.equals("*");
      }
      transformation.applyAdvice(
          hasWildcard
              ? not(
                  isHashCode()
                      .or(isEquals())
                      .or(isToString())
                      .or(isFinalizer())
                      .or(isGetter())
                      .or(isConstructor())
                      .or(isSetter())
                      .or(isSynthetic()))
              : NameMatchers.<MethodDescription>namedOneOf(methodNames),
          packageName + ".TraceAdvice");
    }
  }
}
