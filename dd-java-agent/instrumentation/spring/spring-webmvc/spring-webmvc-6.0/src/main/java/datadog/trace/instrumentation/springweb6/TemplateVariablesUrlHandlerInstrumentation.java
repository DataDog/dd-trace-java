package datadog.trace.instrumentation.springweb6;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.iast.IastPostProcessorFactory;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

/** Obtain template and matrix variables for AbstractUrlHandlerMapping */
@AutoService(InstrumenterModule.class)
public class TemplateVariablesUrlHandlerInstrumentation extends InstrumenterModule
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private Advice.PostProcessor.Factory postProcessorFactory;

  public TemplateVariablesUrlHandlerInstrumentation() {
    super("spring-web");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    if (enabledSystems.contains(TargetSystem.IAST)) {
      postProcessorFactory = IastPostProcessorFactory.INSTANCE;
      return true;
    }
    return enabledSystems.contains(TargetSystem.APPSEC);
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Only apply to versions of spring-webmvc that include request mapping information
    return hasClassNamed("org.springframework.web.servlet.mvc.method.RequestMappingInfo");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.web.servlet.handler.AbstractUrlHandlerMapping$UriTemplateVariablesHandlerInterceptor";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("preHandle"))
            .and(takesArguments(3))
            .and(takesArgument(0, named("jakarta.servlet.http.HttpServletRequest")))
            .and(takesArgument(1, named("jakarta.servlet.http.HttpServletResponse")))
            .and(takesArgument(2, Object.class)),
        packageName + ".InterceptorPreHandleAdvice");
  }

  @Override
  public Advice.PostProcessor.Factory postProcessor() {
    return postProcessorFactory;
  }
}
