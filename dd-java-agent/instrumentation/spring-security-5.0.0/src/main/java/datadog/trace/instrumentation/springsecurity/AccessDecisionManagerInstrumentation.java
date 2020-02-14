package datadog.trace.instrumentation.springsecurity;

import static datadog.trace.agent.tooling.ByteBuddyElementMatchers.safeHasSuperType;
import static datadog.trace.api.DDTags.RESOURCE_NAME;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.springsecurity.SpringSecurityDecorator.DECORATOR;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collection;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.security.access.ConfigAttribute;

/* Instrumentation of
org.springframework.security.access
public interface AccessDecisionManager
  void decide(Authentication authentication,
            Object object,
            Collection<ConfigAttribute> configAttributes)
  throws AccessDeniedException,
  InsufficientAuthenticationException

*/

@AutoService(Instrumenter.class)
public final class AccessDecisionManagerInstrumentation extends Instrumenter.Default {
  public static final String DELIMITER = ", ";

  public AccessDecisionManagerInstrumentation() {
    super("spring-security");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return not(isInterface())
        .and(safeHasSuperType(named("org.springframework.security.access.AccessDecisionManager")));
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
        isMethod().and(isPublic()).and(named("decide")).and(takesArguments(3)),
        AccessDecisionManagerInstrumentation.class.getName() + "$AccessDecisionAdvice";
  }

  public static class AccessDecisionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope StartSpan(
        @Advice.Argument(0) final org.springframework.security.core.Authentication auth,
        @Advice.Argument(1) final Object object,
        @Advice.Argument(2) final Collection<ConfigAttribute> configAttributes) {
      AgentSpan span = startSpan("security");

      span = DECORATOR.afterStart(span);
      DECORATOR.setTagsFromAuth(span, auth);
      DECORATOR.setTagsFromSecuredObject(span, object);
      DECORATOR.setTagsFromConfigAttributes(span, configAttributes);

      String resource_name = "access_decision" + " " + DECORATOR.securedObject();
      span.setTag(RESOURCE_NAME, resource_name);

      return activateSpan(span, true);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {

      if (throwable != null) {
        AgentSpan span = scope.span();
        span.setError(Boolean.TRUE);
        span.addThrowable(throwable);
      }

      scope.close();
    }
  }
}
