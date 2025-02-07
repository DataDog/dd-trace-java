package datadog.trace.instrumentation.xxl_job_2_3x;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.xxl_job_2_3x.JobConstants.INSTRUMENTATION_NAME;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class ScriptJobInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public ScriptJobInstrumentation() {
    super(INSTRUMENTATION_NAME);
  }

  @Override
  public String hierarchyMarkerType() {
    return JobConstants.HandleClassName.SCRIPT_CLASS;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return named(hierarchyMarkerType());
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("execute")),
        packageName + ".ScriptJobAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".JobDecorator",
        packageName + ".JobConstants",
        packageName + ".ScriptJobAdvice",
        packageName + ".JobConstants$JobType",
        packageName + ".JobConstants$HandleClassName"
    };
  }

}
