package datadog.trace.instrumentation;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.lang.reflect.Method;
import java.util.Set;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.instrumentation.AllMethodTraceDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Traces all methods of all classes under the specified business package names globally.
 */
@AutoService(InstrumenterModule.class)
public class AllMethodInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public AllMethodInstrumentation() {
    super("method-trace");
  }

  @Override
  public String hierarchyMarkerType() {
    return "java.lang.Object";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    Set<String> traceMethodPackages = InstrumenterConfig.get().getTraceMethodPackages();
    if (traceMethodPackages.size()==0){
      return nameStartsWith("null");
    }
    ElementMatcher.Junction<TypeDescription> matcher = null;
    for (String pkg : traceMethodPackages) {
      ElementMatcher.Junction<TypeDescription> currentMatcher = nameStartsWith(pkg);
      if (matcher == null) {
        matcher = currentMatcher;
      } else {
        matcher = matcher.or(currentMatcher);
      }
    }
    return matcher;
  }
  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".AllMethodTraceDecorator"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    // 为所有方法添加Advice
    ElementMatcher<MethodDescription> methodFilter =
        not(
            isHashCode()
                .or(isEquals())
                .or(isToString())
                .or(isFinalizer())
                .or(isGetter())
                .or(isSetter())
                .or(isSynthetic()));
    transformation.applyAdvice(
        isMethod().and(methodFilter),
        AllMethodInstrumentation.class.getName() + "$GlobalTracingAdvice");
  }

  public static class GlobalTracingAdvice {
    @Advice.OnMethodEnter
    public static AgentScope onEnter(@Advice.Origin("#t") String className,@Advice.Origin final Method method, @Advice.AllArguments final Object[] args) {
      return DECORATE.buildSpan(className,method,args);
    }
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope,  @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      DECORATE.onError(scope.span(), throwable);
      DECORATE.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
    }
  }


}
