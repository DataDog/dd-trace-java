package datadog.trace.instrumentation.springsecurity5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class AbstractUserDetailsAuthenticationProviderInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForTypeHierarchy {

  public AbstractUserDetailsAuthenticationProviderInstrumentation() {
    super("spring-security");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.security.authentication.dao.AbstractUserDetailsAuthenticationProvider";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("authenticate"))
            .and(takesArgument(0, named("org.springframework.security.core.Authentication")))
            .and(returns(named("org.springframework.security.core.Authentication")))
            .and(isPublic()),
        packageName + ".AbstractUserDetailsAuthenticationProviderAdvice");
  }
}
