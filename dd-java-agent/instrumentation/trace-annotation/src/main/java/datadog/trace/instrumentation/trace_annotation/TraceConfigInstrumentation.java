package datadog.trace.instrumentation.trace_annotation;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
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
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * TraceConfig Instrumentation does not extend Default.
 *
 * <p>Instead it directly implements Instrumenter#instrument() and adds one default Instrumenter for
 * every configured class+method-list.
 *
 * <p>If this becomes a more common use case the building logic should be abstracted out into a
 * super class.
 */
@AutoService(InstrumenterModule.class)
public class TraceConfigInstrumentation extends InstrumenterModule {
  private final Map<String, Set<String>> classMethodsToTrace;

  public TraceConfigInstrumentation() {
    super("trace", "trace-config");
    classMethodsToTrace = InstrumenterConfig.get().getTraceMethods();
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return enabledSystems.contains(TargetSystem.TRACING);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TraceDecorator",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    if (classMethodsToTrace.isEmpty()) {
      return emptyList();
    }
    List<Instrumenter> typeInstrumentations = new ArrayList<>();
    for (Map.Entry<String, Set<String>> entry : classMethodsToTrace.entrySet()) {
      List<String> integrationNames = singletonList("trace-config_" + entry.getKey());
      if (InstrumenterConfig.get().isIntegrationEnabled(integrationNames, true)) {
        typeInstrumentations.add(new TracerClassInstrumentation(entry.getKey(), entry.getValue()));
      }
    }
    return typeInstrumentations;
  }

  // Not Using AutoService to hook up this instrumentation
  public static class TracerClassInstrumentation implements ForTypeHierarchy, HasMethodAdvice {
    private final String className;
    private final Set<String> methodNames;

    public TracerClassInstrumentation(final String className, final Set<String> methodNames) {
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
    public void methodAdvice(MethodTransformer transformer) {
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
      transformer.applyAdvice(
          isMethod().and(methodFilter),
          "datadog.trace.instrumentation.trace_annotation.TraceAdvice");
    }
  }
}
