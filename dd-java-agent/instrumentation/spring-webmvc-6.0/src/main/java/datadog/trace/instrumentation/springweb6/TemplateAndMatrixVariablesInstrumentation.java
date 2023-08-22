package datadog.trace.instrumentation.springweb6;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Set;
import net.bytebuddy.matcher.ElementMatcher;

/** Obtain template and matrix variables for RequestMappingInfoHandlerMapping. */
@AutoService(Instrumenter.class)
public class TemplateAndMatrixVariablesInstrumentation extends Instrumenter.Default
    implements Instrumenter.ForSingleType {
  public TemplateAndMatrixVariablesInstrumentation() {
    super("spring-web");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return enabledSystems.contains(TargetSystem.APPSEC)
        || enabledSystems.contains(TargetSystem.IAST);
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Only apply to versions of spring-webmvc that include request mapping information
    return hasClassNamed("org.springframework.web.servlet.mvc.method.RequestMappingInfo");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isProtected())
            .and(named("handleMatch"))
            .and(
                takesArgument(
                    0, named("org.springframework.web.servlet.mvc.method.RequestMappingInfo")))
            .and(takesArgument(1, String.class))
            .and(takesArgument(2, named("jakarta.servlet.http.HttpServletRequest")))
            .and(takesArguments(3)),
        packageName + ".HandleMatchAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".PairList",
    };
  }
}
