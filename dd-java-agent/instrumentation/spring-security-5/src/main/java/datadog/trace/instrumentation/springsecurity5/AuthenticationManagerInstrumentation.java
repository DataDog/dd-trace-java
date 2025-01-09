package datadog.trace.instrumentation.springsecurity5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ActiveSubsystems;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

@AutoService(InstrumenterModule.class)
public class AuthenticationManagerInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public AuthenticationManagerInstrumentation() {
    super("spring-security");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.security.authentication.AuthenticationManager";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.springsecurity5.SpringSecurityUserEventDecorator"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("authenticate"))
            .and(takesArgument(0, named("org.springframework.security.core.Authentication")))
            .and(returns(named("org.springframework.security.core.Authentication")))
            .and(isPublic()),
        getClass().getName() + "$AuthenticationManagerAdvice");
  }

  public static class AuthenticationManagerAdvice {

    @Advice.OnMethodExit(onThrowable = AuthenticationException.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(value = 0, readOnly = false) Authentication authentication,
        @Advice.Return final Authentication result,
        @Advice.Thrown final AuthenticationException throwable) {
      if (ActiveSubsystems.APPSEC_ACTIVE) {
        SpringSecurityUserEventDecorator.DECORATE.onLogin(authentication, throwable, result);
      }
    }
  }
}
