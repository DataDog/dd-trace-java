package datadog.trace.instrumentation.tibcobw5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.tibcobw5.TibcoDecorator.DECORATE;
import static datadog.trace.instrumentation.tibcobw5.TibcoDecorator.TIBCO_PROCESS_OPERATION;

import com.google.auto.service.AutoService;
import com.tibco.pe.plugin.ProcessContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class JobInstrumentation extends AbstractTibcoInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "com.tibco.pe.core.Job";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(named("callProcess"), getClass().getName() + "$JobCallAdvice");
  }

  public static class JobCallAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(
        @Advice.This ProcessContext processContext, @Advice.Argument(value = 0) String wId) {

      String workflowName = wId;
      int suffixIdx = workflowName.indexOf(".process");
      if (suffixIdx > 0) {
        workflowName = workflowName.substring(0, suffixIdx);
      }
      AgentSpan span = startSpan(TIBCO_PROCESS_OPERATION);
      DECORATE.afterStart(span);
      DECORATE.onProcessStart(span, workflowName);
      Map<String, AgentSpan> map =
          InstrumentationContext.get(ProcessContext.class, Map.class)
              .putIfAbsent(processContext, HashMap::new);
      map.put(wId, span);
    }
  }
}
