package datadog.trace.instrumentation.xxl_job_2_3x;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.xxl_job_2_3x.JobConstants.INSTRUMENTATION_NAME;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public class ScriptJobInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public ScriptJobInstrumentation() {
    super(INSTRUMENTATION_NAME);
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return named(JobConstants.HandleClassName.SCRIPT_CLASS);
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("execute"))
            .and(takesArguments(0)),
        packageName + ".ScriptJobAdvice");
  }

}
