package datadog.trace.instrumentation.springsecurity5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;

/**
 * Hooks the static factory method {@code UsernameNotFoundException.fromUsername(String)} added in
 * Spring Security 7. In Spring Security 7, {@code UserDetailsService} implementations (notably
 * {@code InMemoryUserDetailsManager}) construct {@code UsernameNotFoundException} via this factory
 * rather than the public constructor, so the sibling {@link UsernameNotFoundExceptionInstrumentation}
 * (constructor-based) no longer fires on that path.
 */
@AutoService(InstrumenterModule.class)
public class UsernameNotFoundExceptionFactoryInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public UsernameNotFoundExceptionFactoryInstrumentation() {
    super("spring-security");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.security.core.userdetails.UsernameNotFoundException";
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
        isMethod().and(named("fromUsername")).and(isStatic()).and(isPublic()),
        getClass().getName() + "$FromUsernameAdvice");
  }

  public static class FromUsernameAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) final String username) {
      SpringSecurityUserEventDecorator.DECORATE.onUserNotFound(username);
    }
  }
}
