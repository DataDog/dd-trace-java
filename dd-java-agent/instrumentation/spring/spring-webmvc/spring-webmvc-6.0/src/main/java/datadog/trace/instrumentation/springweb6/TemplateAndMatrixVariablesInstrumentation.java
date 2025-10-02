package datadog.trace.instrumentation.springweb6;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.iast.IastPostProcessorFactory;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

/** Obtain template and matrix variables for RequestMappingInfoHandlerMapping. */
@AutoService(InstrumenterModule.class)
public class TemplateAndMatrixVariablesInstrumentation extends InstrumenterModule
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private Advice.PostProcessor.Factory postProcessorFactory;

  public TemplateAndMatrixVariablesInstrumentation() {
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
    return "org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
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

  @Override
  public Advice.PostProcessor.Factory postProcessor() {
    return postProcessorFactory;
  }
}
