package datadog.trace.instrumentation.springsecurity;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeHasSuperType;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.springsecurity.SpringSecurityDecorator.DECORATOR;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.security.core.Authentication;

@AutoService(Instrumenter.class)
public final class AuthenticationManagerInstrumentation extends Instrumenter.Default {

  public AuthenticationManagerInstrumentation() {
    super("spring-security");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("org.springframework.security.authentication.AuthenticationManager");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return not(isInterface())
        .and(
            safeHasSuperType(
                named("org.springframework.security.authentication.AuthenticationManager")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringSecurityDecorator",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(isPublic())
            .and(named("authenticate"))
            .and(takesArgument(0, named("org.springframework.security.core.Authentication")))
            .and(takesArguments(1)),
        AuthenticationManagerInstrumentation.class.getName() + "$AuthenticateAdvice");
  }

  public static class AuthenticateAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope start(@Advice.Argument(0) final Authentication auth) {

      AgentSpan span = startSpan("security.authenticate");
      span = DECORATOR.afterStart(span);
      if (auth != null) {
        DECORATOR.onAuthentication(span, auth);
      }

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stop(
        @Advice.Enter final AgentScope scope,
        @Advice.Return final Authentication auth,
        @Advice.Thrown final Throwable throwable) {
      final AgentSpan span = scope.span();
      if (auth != null) {
        // updates if authentication was a success
        DECORATOR.onAuthentication(span, auth);
      }
      DECORATOR.onError(span, throwable);
      DECORATOR.beforeFinish(span);
      scope.close();
      span.finish();
    }
  }
}
