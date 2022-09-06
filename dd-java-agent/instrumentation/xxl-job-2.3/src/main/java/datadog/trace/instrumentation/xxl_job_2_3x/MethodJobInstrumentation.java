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
public class MethodJobInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public MethodJobInstrumentation() {
    super(INSTRUMENTATION_NAME);
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return named(JobConstants.HandleClassName.METHOD_CLASS);
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("execute"))
            .and(takesArguments(0)),
        packageName + ".MethodJobAdvice");
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
