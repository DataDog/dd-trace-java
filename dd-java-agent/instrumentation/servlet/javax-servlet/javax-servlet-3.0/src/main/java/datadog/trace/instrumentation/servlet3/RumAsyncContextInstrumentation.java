package datadog.trace.instrumentation.servlet3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_RUM_INJECTED;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.rum.RumControllableResponse;
import javax.servlet.AsyncContext;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class RumAsyncContextInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public RumAsyncContextInstrumentation() {
    super("servlet", "servlet-3", "servlet-3-async-context");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.servlet.AsyncContext";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".RumHttpServletResponseWrapper", packageName + ".WrappedServletOutputStream",
    };
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String muzzleDirective() {
    return "servlet-3.x";
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && InstrumenterConfig.get().isRumEnabled();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(namedOneOf("complete", "dispatch")), getClass().getName() + "$CommitAdvice");
  }

  public static class CommitAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void commitRumBuffer(@Advice.This final AsyncContext asyncContext) {
      final Object maybeRumWrappedResponse =
          asyncContext.getRequest().getAttribute(DD_RUM_INJECTED);
      if (maybeRumWrappedResponse instanceof RumControllableResponse) {
        ((RumControllableResponse) maybeRumWrappedResponse).commit();
      }
    }
  }
}
