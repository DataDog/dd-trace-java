package datadog.trace.instrumentation.trace_annotation;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isEquals;
import static net.bytebuddy.matcher.ElementMatchers.isFinalizer;
import static net.bytebuddy.matcher.ElementMatchers.isGetter;
import static net.bytebuddy.matcher.ElementMatchers.isHashCode;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isSetter;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.isToString;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.traceannotation.TraceAnnotationConfigParser;
import datadog.trace.util.Strings;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
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

  private static Map<String, Set<String>> logWarn(
      String message, int start, int end, String configString) {
    String part = configString.substring(start, end).trim();
    log.warn(
        "Invalid trace method config {} in part '{}'. Must match 'package.Class$Name[method1,method2];?' or 'package.Class$Name[*];?'. Config string: '{}'",
        message,
        part,
        configString);
    return Collections.emptyMap();
  }

  private static boolean hasIllegalCharacters(String string) {
    for (int i = 0; i < string.length(); i++) {
      char c = string.charAt(i);
      if (c == '*' || c == '[' || c == ']' || c == ',') {
        return true;
      }
    }
    return false;
  }

  private static boolean isIllegalClassName(String string) {
    return hasIllegalCharacters(string);
  }

  private static boolean isIllegalMethodName(String string) {
    return !string.equals("*") && hasIllegalCharacters(string);
  }

  @SuppressForbidden
  public TraceConfigInstrumentation() {
    final String configString = Strings.trim(InstrumenterConfig.get().getTraceMethods());
    classMethodsToTrace = TraceAnnotationConfigParser.parse(configString);
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return enabledSystems.contains(TargetSystem.TRACING);
  }

  @Override
  public void instrument(TransformerBuilder transformerBuilder) {
    if (classMethodsToTrace.isEmpty()) {
      return;
    }

    for (final Map.Entry<String, Set<String>> entry : classMethodsToTrace.entrySet()) {
      final TracerClassInstrumentation tracerClassInstrumentation =
          new TracerClassInstrumentation(entry.getKey(), entry.getValue());
      transformerBuilder.applyInstrumentation(tracerClassInstrumentation);
    }
  }

  // Not Using AutoService to hook up this instrumentation
  public static class TracerClassInstrumentation extends Tracing implements ForTypeHierarchy {
    private final String className;
    private final Set<String> methodNames;

    /** No-arg constructor only used by muzzle and tests. */
    public TracerClassInstrumentation() {
      this("datadog.trace.api.Trace", Collections.singleton("noop"));
    }

    public TracerClassInstrumentation(final String className, final Set<String> methodNames) {
      super("trace", "trace-config", "trace-config_" + className);
      this.className = className;
      this.methodNames = methodNames;
    }

    @Override
    public String hierarchyMarkerType() {
      return className;
    }

    @Override
    public ElementMatcher<TypeDescription> hierarchyMatcher() {
      return hasSuperType(named(hierarchyMarkerType()));
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
      ElementMatcher<MethodDescription> methodFilter;
      if (hasWildcard) {
        methodFilter =
            not(
                isHashCode()
                    .or(isEquals())
                    .or(isToString())
                    .or(isFinalizer())
                    .or(isGetter())
                    .or(isSetter())
                    .or(isSynthetic()));
      } else {
        methodFilter = namedOneOf(methodNames);
      }
      transformation.applyAdvice(isMethod().and(methodFilter), packageName + ".TraceAdvice");
    }
  }
}
