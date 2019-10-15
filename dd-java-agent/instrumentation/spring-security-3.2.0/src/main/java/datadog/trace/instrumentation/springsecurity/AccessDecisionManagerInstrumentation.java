package datadog.trace.instrumentation.springsecurity;

import static datadog.trace.agent.tooling.ByteBuddyElementMatchers.safeHasSuperType;
import static datadog.trace.instrumentation.springsecurity.SpringSecurityDecorator.DECORATOR;
import static io.opentracing.log.Fields.ERROR_OBJECT;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.security.access.ConfigAttribute;
import org.springframework.security.web.FilterInvocation;

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
        AccessDecisionAdvice.class.getName());
  }

  public static class AccessDecisionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Scope StartSpan(
        @Advice.Argument(0) final org.springframework.security.core.Authentication auth,
        @Advice.Argument(1) final Object object,
        @Advice.Argument(2) final Collection<ConfigAttribute> configAttributes) {

      final Scope scope = GlobalTracer.get().buildSpan("access_decision").startActive(true);
      Span span = scope.span();
      DECORATOR.afterStart(span);
      span = DECORATOR.setTagsFromAuth(span, auth);

      for (ConfigAttribute ca : configAttributes) {
        span.setTag(" config.attribute:", ca.getAttribute());
      }

      if (object != null && (object instanceof org.springframework.security.web.FilterInvocation)) {
        FilterInvocation fi = (FilterInvocation) object;

        span.setTag("request.fullURL", fi.getFullRequestUrl());
        span.setTag("request.URL", fi.getRequestUrl());
      }

      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final Scope scope, @Advice.Thrown final Throwable throwable) {

      if (throwable != null) {
        final Span span = scope.span();
        Tags.ERROR.set(span, Boolean.TRUE);
        span.log(Collections.singletonMap(ERROR_OBJECT, throwable));
      }

      scope.close();
    }
  }
}
