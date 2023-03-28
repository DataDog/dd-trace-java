package datadog.trace.instrumentation.springweb6;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Set;
import net.bytebuddy.matcher.ElementMatcher;

/** Obtain template and matrix variables for AbstractUrlHandlerMapping */
@AutoService(Instrumenter.class)
public class TemplateVariablesUrlHandlerInstrumentation extends Instrumenter.Default
    implements Instrumenter.ForSingleType {

  public TemplateVariablesUrlHandlerInstrumentation() {
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
    return "org.springframework.web.servlet.handler.AbstractUrlHandlerMapping$UriTemplateVariablesHandlerInterceptor";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("preHandle"))
            .and(takesArguments(3))
            .and(takesArgument(0, named("jakarta.servlet.http.HttpServletRequest")))
            .and(takesArgument(1, named("jakarta.servlet.http.HttpServletResponse")))
            .and(takesArgument(2, Object.class)),
        packageName + ".InterceptorPreHandleAdvice");
  }
}
