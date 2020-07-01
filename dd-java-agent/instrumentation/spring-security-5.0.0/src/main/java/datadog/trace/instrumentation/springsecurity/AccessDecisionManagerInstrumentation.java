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
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

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

@AutoService(Instrumenter.class)
public final class AccessDecisionManagerInstrumentation extends Instrumenter.Default {

  public AccessDecisionManagerInstrumentation() {
    super("spring-security");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("org.springframework.security.access.AccessDecisionManager");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return not(isInterface())
        .and(safeHasSuperType(named("org.springframework.security.access.AccessDecisionManager")));
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
        isMethod().and(isPublic()).and(named("decide")).and(takesArguments(3)),
        AccessDecisionManagerInstrumentation.class.getName() + "$AccessDecisionAdvice");
  }

  public static class AccessDecisionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope start(
        @Advice.Argument(0) final org.springframework.security.core.Authentication auth,
        @Advice.Argument(1) final Object object,
        @Advice.Argument(2) final Collection<ConfigAttribute> configAttributes) {
      final AgentSpan span = startSpan("security.access_decision");

      DECORATOR.afterStart(span);
      DECORATOR.onAuthentication(span, auth);
      DECORATOR.onSecuredObject(span, object);
      DECORATOR.onConfigAttributes(span, configAttributes);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stop(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      final AgentSpan span = scope.span();
      DECORATOR.onError(span, throwable);
      DECORATOR.beforeFinish(span);
      scope.close();
      span.finish();
    }
  }
}
