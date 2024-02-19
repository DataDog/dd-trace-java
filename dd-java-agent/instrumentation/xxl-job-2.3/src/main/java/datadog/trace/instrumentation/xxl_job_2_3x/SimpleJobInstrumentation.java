package datadog.trace.instrumentation.xxl_job_2_3x;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.xxl_job_2_3x.JobConstants.INSTRUMENTATION_NAME;
import static net.bytebuddy.matcher.ElementMatchers.*;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class SimpleJobInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public SimpleJobInstrumentation() {
    super(INSTRUMENTATION_NAME);
  }


  @Override
  public String hierarchyMarkerType() {
    return JobConstants.HandleClassName.HANDLER_CLASS;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        .and(not(named(JobConstants.HandleClassName.METHOD_CLASS)))
        .and(not(named(JobConstants.HandleClassName.SCRIPT_CLASS)))
        .and(not(named(JobConstants.HandleClassName.GLUE_CLASS)))
        ;
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("execute"))
            .and(takesArguments(0)),
        packageName + ".SimpleJobAdvice");
  }
}
