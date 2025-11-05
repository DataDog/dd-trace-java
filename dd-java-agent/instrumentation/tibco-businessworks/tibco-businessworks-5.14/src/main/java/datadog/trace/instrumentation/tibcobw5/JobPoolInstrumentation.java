package datadog.trace.instrumentation.tibcobw5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.tibcobw5.TibcoDecorator.DECORATE;
import static datadog.trace.instrumentation.tibcobw5.TibcoDecorator.TIBCO_PROCESS_OPERATION;

import com.google.auto.service.AutoService;
import com.tibco.pe.core.DDJobMate;
import com.tibco.pe.core.JobPool;
import com.tibco.pe.core.Workflow;
import com.tibco.pe.plugin.ProcessContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import edu.umd.cs.findbugs.annotations.SuppressWarnings;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class JobPoolInstrumentation extends AbstractTibcoInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "com.tibco.pe.core.JobPool";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {

    transformer.applyAdvice(named("addJob"), getClass().getName() + "$JobStartAdvice");
    transformer.applyAdvice(named("removeJob"), getClass().getName() + "$JobEndAdvice");
  }

  public static class JobStartAdvice {
    @SuppressWarnings("UC_USELESS_OBJECT")
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void after(@Advice.Argument(value = 0) ProcessContext processContext) {
      final Workflow workflow = DDJobMate.getJobWorkflow(processContext);
      if (workflow == null) {
        return;
      }
      final String wId = workflow.getName();
      String workflowName = wId;
      int suffixIdx = workflowName.indexOf(".process");
      if (suffixIdx > 0) {
        workflowName = workflowName.substring(0, suffixIdx);
      }
      AgentSpan span = startSpan(TIBCO_PROCESS_OPERATION);
      DECORATE.afterStart(span);
      DECORATE.onProcessStart(span, workflowName);
      Map<String, AgentSpan> map = new HashMap<>();
      map.put(wId, span);
      InstrumentationContext.get(ProcessContext.class, Map.class).put(processContext, map);
    }
  }

  public static class JobEndAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.This final JobPool self,
        @Advice.Argument(value = 0) ProcessContext processContext,
        @Advice.Thrown final Throwable thrown) {
      Map<String, AgentSpan> map =
          InstrumentationContext.get(ProcessContext.class, Map.class).remove(processContext);
      final String workflowName = DDJobMate.getJobWorkflow(processContext).getName();
      if (map == null || !map.containsKey(workflowName)) {
        return;
      }
      AgentSpan span = map.get(workflowName);
      if (thrown != null) {
        DECORATE.onError(span, thrown);
      }
      DECORATE.beforeFinish(span);
      span.finish();
      map.clear();
    }
  }
}
