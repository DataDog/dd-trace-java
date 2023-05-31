package datadog.trace.instrumentation.springsecurity6;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class LoginInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public LoginInstrumentation() {
    super("spring-security");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.security.authentication.AuthenticationProvider";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("authenticate"))
            .and(takesArgument(0, named("org.springframework.security.core.Authentication")))
            .and(returns(named("org.springframework.security.core.Authentication")))
            .and(isPublic()),
        packageName + ".LoginAdvice");
  }
}
