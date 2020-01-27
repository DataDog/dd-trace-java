package datadog.trace.instrumentation.springsecurity;

import static datadog.trace.agent.tooling.ByteBuddyElementMatchers.safeHasSuperType;
import static datadog.trace.api.DDTags.RESOURCE_NAME;
import static datadog.trace.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.springsecurity.SpringSecurityDecorator.DECORATOR;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.api.AgentScope;
import datadog.trace.instrumentation.api.AgentSpan;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/* Instrumentation of
 org.springframework.security.authentication
Interface AuthenticationManager
Authentication authenticate(Authentication authentication)
throws AuthenticationException
*/
@AutoService(Instrumenter.class)
public final class AuthenticationManagerInstrumentation extends Instrumenter.Default {

  public AuthenticationManagerInstrumentation() {
    super("spring-security");
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
      "datadog.trace.agent.decorator.BaseDecorator", packageName + ".SpringSecurityDecorator"
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
        AuthenticateAdvice.class.getName());
  }

  public static class AuthenticateAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope StartSpan(
        @Advice.Argument(0) final org.springframework.security.core.Authentication auth,
        @Advice.This(optional = true) Object thiz) {

      AgentSpan span = startSpan("security");
      span = DECORATOR.afterStart(span);
      if (auth != null) {
        String resource_name = "authenticate" + " " + auth.getName();
        span.setTag(RESOURCE_NAME, resource_name);
        DECORATOR.setTagsFromAuth(span, auth);
      }

      return activateSpan(span, true);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope,
        @Advice.Return org.springframework.security.core.Authentication auth,
        @Advice.Thrown final Throwable throwable) {
      AgentSpan span = scope.span();

      if (auth != null) {
        // updates if authentication was a success
        DECORATOR.setTagsFromAuth(span, auth);
      }
      if (throwable != null) {
        span.setError(Boolean.TRUE);
        span.addThrowable(throwable);
      }
      scope.close();
    }
  }
}
