package datadog.trace.instrumentation.springsecurity5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ActiveSubsystems;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.security.core.context.SecurityContext;

@AutoService(InstrumenterModule.class)
public class SecurityContextHolderInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public SecurityContextHolderInstrumentation() {
    super("spring-security");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.security.core.context.SecurityContextHolderStrategy";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.springsecurity5.SpringSecurityUserEventDecorator",
      "datadog.trace.instrumentation.springsecurity5.AppSecDeferredContext"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("setContext"))
            .and(takesArguments(1))
            .and(
                takesArgument(
                    0, named("org.springframework.security.core.context.SecurityContext")))
            .and(isPublic()),
        getClass().getName() + "$SetSecurityContextAdvice");
    transformer.applyAdvice(
        isMethod().and(named("setDeferredContext")).and(takesArguments(1)).and(isPublic()),
        getClass().getName() + "$SetDeferredSecurityContextAdvice");
  }

  public static class SetSecurityContextAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) final SecurityContext context) {
      if (context == null) {
        return;
      }
      if (!ActiveSubsystems.APPSEC_ACTIVE) {
        return;
      }
      SpringSecurityUserEventDecorator.DECORATE.onUser(context.getAuthentication());
    }
  }

  public static class SetDeferredSecurityContextAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(value = 0, readOnly = false) Supplier<SecurityContext> deferred) {
      if (deferred == null) {
        return;
      }
      if (!ActiveSubsystems.APPSEC_ACTIVE) {
        return;
      }
      deferred = new AppSecDeferredContext(deferred);
    }
  }
}
