package datadog.trace.instrumentation.trace_annotation;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeHasSuperType;
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
import datadog.trace.api.Config;
import datadog.trace.util.Strings;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
    final String configString =
        Config.get().getTraceMethods() == null ? null : Config.get().getTraceMethods().trim();
    if (configString == null || configString.isEmpty()) {
      classMethodsToTrace = Collections.emptyMap();
    } else {
      Map<String, Set<String>> toTrace = new HashMap<>();
      int start = 0;
      do {
        int next = configString.indexOf(';', start + 1);
        int end = next == -1 ? configString.length() : next;
        if (end > start + 1) {
          int methodsStart = configString.indexOf('[', start);
          if (methodsStart == -1) {
            if (!configString.substring(start).trim().isEmpty()) {
              // this had other things than trailing whitespace after the ';' which is illegal
              toTrace = logWarn("with incomplete definition", start, end, configString);
            }
            break;
          } else if (methodsStart >= end) {
            // this part didn't contain a '[' or ended in a '[' which is illegal
            toTrace = logWarn("with incomplete method definition", start, end, configString);
            break;
          }
          int methodsEnd = configString.indexOf(']', methodsStart);
          if (methodsEnd == -1 || methodsEnd > end) {
            // this part didn't contain a ']' which is illegal
            toTrace = logWarn("does not contain a ']'", start, end, configString);
            break;
          } else if (methodsEnd < end
              && !configString.substring(methodsEnd + 1, end).trim().isEmpty()) {
            // this had other things than trailing whitespace after the ']'
            toTrace = logWarn("with extra characters after ']'", start, end, configString);
            break;
          }
          String className = configString.substring(start, methodsStart).trim();
          if (className.isEmpty() || isIllegalClassName(className)) {
            toTrace = logWarn("with illegal class name", start, end, configString);
            break;
          }
          Set<String> methodNames = toTrace.get(className);
          if (null == methodNames) {
            methodNames = new HashSet<>();
            toTrace.put(className, methodNames);
          }
          int methods = 0;
          int emptyMethods = 0;
          boolean hasStar = false;
          for (int methodStart = methodsStart + 1; methodStart < methodsEnd; ) {
            int nextComma = configString.indexOf(',', methodStart);
            int methodEnd = nextComma == -1 || nextComma >= methodsEnd ? methodsEnd : nextComma;
            String method = configString.substring(methodStart, methodEnd).trim();
            if (isIllegalMethodName(method)) {
              toTrace = logWarn("with illegal method name", start, end, configString);
              methods++; // don't log empty method warning at end
              next = -1;
              break;
            } else if (method.isEmpty()) {
              emptyMethods++;
              if (emptyMethods > 1) {
                // we can't have multiple empty methods
                toTrace = logWarn("with multiple emtpy method names", start, end, configString);
                methods++; // don't log empty method warning at end
                next = -1;
                break;
              }
            } else {
              methods++;
              if (emptyMethods > 0) {
                // the empty method name was not the last one, which makes it illegal
                toTrace =
                    logWarn("with method name and emtpy method name", start, end, configString);
                next = -1;
                break;
              }
              hasStar |= method.indexOf('*') != -1;
              if (hasStar && methods > 1) {
                // having both a method and a '*' is illegal
                toTrace = logWarn("with both method name and '*'", start, end, configString);
                next = -1;
                break;
              }
              methodNames.add(method);
            }
            methodStart = methodEnd + 1;
          }
          if (methods == 0) {
            // empty method description is illegal
            toTrace = logWarn("with empty method definition", start, end, configString);
            break;
          }
        }
        start = next + 1;
      } while (start != 0);
      classMethodsToTrace = Collections.unmodifiableMap(toTrace);
    }
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
      super(
          "trace",
          "trace-config",
          "trace-config_"
              + className
              + (!Config.get().isDebugEnabled() ? "" : "[" + Strings.join(",", methodNames) + "]"));
      this.className = className;
      this.methodNames = methodNames;
    }

    @Override
    public ElementMatcher<ClassLoader> classLoaderMatcher() {
      // Optimization for expensive typeMatcher.
      return hasClassesNamed(className);
    }

    @Override
    public ElementMatcher<TypeDescription> hierarchyMatcher() {
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
