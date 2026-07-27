package datadog.trace.instrumentation.springsecurity7;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.springsecurity5.SpringSecurityUserEventDecorator;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments {@code UsernameNotFoundException.<init>()} and {@code fromUsername()} for Spring
 * Security 7.x AppSec user-not-found tracking.
 */
@AutoService(InstrumenterModule.class)
public class UsernameNotFoundExceptionInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public UsernameNotFoundExceptionInstrumentation() {
    super("spring-security");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.security.core.userdetails.UsernameNotFoundException";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
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
        isConstructor().and(takesArgument(0, named("java.lang.String"))).and(isPublic()),
        getClass().getName() + "$UsernameNotFoundExceptionAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("fromUsername"))
            .and(isStatic())
            .and(isPublic())
            .and(takesArgument(0, named("java.lang.String"))),
        getClass().getName() + "$FromUsernameAdvice");
  }

  public static class UsernameNotFoundExceptionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
      SpringSecurityUserEventDecorator.DECORATE.onUserNotFound();
    }
  }

  /** Advice for the static factory method {@code UsernameNotFoundException.fromUsername()}. */
  public static class FromUsernameAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
      SpringSecurityUserEventDecorator.DECORATE.onUserNotFound();
    }
  }
}
