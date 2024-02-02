package datadog.trace.instrumentation.springsecurity5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class UserDetailsManagerInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForTypeHierarchy {

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
        packageName + ".UserDetailsManagerAdvice");
  }
}
