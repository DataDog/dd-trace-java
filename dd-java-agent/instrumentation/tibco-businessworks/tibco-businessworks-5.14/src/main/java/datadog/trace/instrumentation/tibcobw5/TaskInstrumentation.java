package datadog.trace.instrumentation.tibcobw5;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.tibcobw5.TibcoDecorator.DECORATE;
import static datadog.trace.instrumentation.tibcobw5.TibcoDecorator.TIBCO_ACTIVITY_OPERATION;

import com.google.auto.service.AutoService;
import com.tibco.pe.core.ActivityGroup;
import com.tibco.pe.core.ProcessGroup;
import com.tibco.pe.core.Task;
import com.tibco.pe.plugin.ProcessContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

@AutoService(InstrumenterModule.class)
public class TaskInstrumentation extends AbstractTibcoInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "com.tibco.pe.core.TaskImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(NameMatchers.named("eval"), getClass().getName() + "$EvalAdvice");
    transformer.applyAdvice(
        NameMatchers.named("handleError"), getClass().getName() + "$ErrorAdvice");
  }

  public static class ErrorAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void captureError(@Advice.Argument(2) Throwable t) {
      AgentSpan span = activeSpan();
      if (span != null) {
        DECORATE.onError(span, t);
      }
    }
  }

  public static class EvalAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean before(
        @Advice.This Task self,
        @Advice.Argument(0) ProcessContext processContext,
        @Advice.Local("ddActivityInfo") ActivityHelper.ActivityInfo ddActivityInfo,
        @Advice.Local("ddScope") AgentScope ddScope) {

      ContextStore<ProcessContext, Map> store =
          InstrumentationContext.get(ProcessContext.class, Map.class);
      Map<String, AgentSpan> map = store.get(processContext);
      if (map == null) {
        return false;
      }

      ddActivityInfo = ActivityHelper.activityInfo(self);
      AgentSpan span = map.get(ddActivityInfo.id);
      if (span == null) {
        AgentSpan parent = map.getOrDefault(ddActivityInfo.parent, activeSpan());
        span = startSpan(TIBCO_ACTIVITY_OPERATION, parent != null ? parent.context() : null);
        DECORATE.afterStart(span);
        DECORATE.onActivityStart(span, ddActivityInfo.name);
        map.put(ddActivityInfo.id, span);
      }
      if (ddActivityInfo.trace) {
        ddScope = activateSpan(span);
      }
      return ddActivityInfo.trace;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(
        @Advice.This(typing = Assigner.Typing.DYNAMIC) Task self,
        @Advice.Argument(0) ProcessContext processContext,
        @Advice.Return String ret,
        @Advice.Enter boolean traced,
        @Advice.Local("ddActivityInfo") ActivityHelper.ActivityInfo ddActivityInfo,
        @Advice.Local("ddScope") AgentScope ddScope) {
      try (AgentScope closeMe = ddScope) {
        if (!traced) {
          return;
        }

        if ("STAY_HERE".equals(ret)
            || ("DEAD".equals(ret)
                && self.getActivity() instanceof ActivityGroup
                && !(self.getActivity() instanceof ProcessGroup))) {
          return;
        }

        Map<String, AgentSpan> map =
            InstrumentationContext.get(ProcessContext.class, Map.class).get(processContext);
        if (map == null) {
          return;
        }

        AgentSpan span = map.remove(ddActivityInfo.id);
        if (span != null) {
          DECORATE.beforeFinish(span);
          span.finish();
        }
      }
    }
  }
}
