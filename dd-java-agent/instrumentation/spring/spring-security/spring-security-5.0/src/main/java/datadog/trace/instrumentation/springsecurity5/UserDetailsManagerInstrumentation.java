package datadog.trace.instrumentation.springsecurity5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ActiveSubsystems;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.security.core.userdetails.UserDetails;

@AutoService(InstrumenterModule.class)
public class UserDetailsManagerInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public UserDetailsManagerInstrumentation() {
    super("spring-security");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.security.provisioning.UserDetailsManager";
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
            .and(named("createUser"))
            .and(
                takesArgument(
                    0, named("org.springframework.security.core.userdetails.UserDetails")))
            .and(isPublic()),
        getClass().getName() + "$UserDetailsManagerAdvice");
  }

  public static class UserDetailsManagerAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(value = 0, readOnly = false) UserDetails user,
        @Advice.Thrown Throwable throwable) {
      if (ActiveSubsystems.APPSEC_ACTIVE) {
        SpringSecurityUserEventDecorator.DECORATE.onSignup(user, throwable);
      }
    }
  }
}
