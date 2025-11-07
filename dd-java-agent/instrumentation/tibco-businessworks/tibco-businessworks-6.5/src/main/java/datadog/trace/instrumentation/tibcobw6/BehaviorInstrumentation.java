package datadog.trace.instrumentation.tibcobw6;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.tibco.pvm.api.PmProcessInstance;
import com.tibco.pvm.api.PmTask;
import com.tibco.pvm.api.PmWorkUnit;
import com.tibco.pvm.api.behavior.PmBehavior;
import com.tibco.pvm.api.event.PmEvent;
import com.tibco.pvm.api.session.PmContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class BehaviorInstrumentation extends AbstractTibcoInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "com.tibco.pvm.api.behavior.PmBehavior";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(
                named("enter")
                    .and(
                        isDeclaredBy(
                            hasInterface(named("com.tibco.pvm.api.behavior.PmProcessBehavior"))))),
        getClass().getName() + "$ProcessStartAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(
                named("exit")
                    .and(
                        isDeclaredBy(
                            hasInterface(named("com.tibco.pvm.api.behavior.PmProcessBehavior"))))),
        getClass().getName() + "$ProcessEndAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("eval"))
            .and(takesArgument(1, hasInterface(named("com.tibco.pvm.api.PmTask")))),
        getClass().getName() + "$ActivityEvalAdvice");
    transformer.applyAdvice(
        isMethod().and(named("handleModelEvent")), getClass().getName() + "$HandleEventAdvice");
  }

  public static class ActivityEvalAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope activityBegin(
        @Advice.This final Object self,
        @Advice.Argument(0) final PmContext pmContext,
        @Advice.Argument(1) final PmTask pmTask) {
      final PmTask parentTask = pmTask.getParent(pmContext);
      if (parentTask == null) {
        // do not trace the Root task
        return null;
      }
      final ContextStore<PmWorkUnit, AgentSpan> contextStore =
          InstrumentationContext.get(PmWorkUnit.class, AgentSpan.class);
      AgentSpan parentSpan = contextStore.get(pmTask.getParent(pmContext));
      if (parentSpan == null) {
        parentSpan = contextStore.get(pmTask.getProcess(pmContext));
      }
      if (IgnoreHelper.notTracing(self)) {
        // record the parent but do not trace scope or flows
        contextStore.put(pmTask, parentSpan);
        return null;
      }
      AgentSpan span =
          startSpan(
              TibcoDecorator.TIBCO_ACTIVITY_OPERATION,
              parentSpan != null ? parentSpan.context() : null);
      TibcoDecorator.DECORATE.afterStart(span);
      TibcoDecorator.DECORATE.onActivityStart(span, pmTask.getName(pmContext));
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void activityEnd(
        @Advice.Enter final AgentScope scope,
        @Advice.This final PmBehavior self,
        @Advice.Argument(0) final PmContext pmContext,
        @Advice.Argument(1) final PmTask pmTask) {
      if (scope == null) {
        return;
      }
      try {
        final AgentSpan span = scope.span();
        if (self.isFinished(pmContext, pmTask)) {
          TibcoDecorator.DECORATE.beforeFinish(span);
          span.finish();
        } else {
          InstrumentationContext.get(PmWorkUnit.class, AgentSpan.class).put(pmTask, span);
        }
      } finally {
        scope.close();
      }
    }
  }

  public static class ProcessStartAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void processStarts(
        @Advice.Argument(0) final PmContext pmContext,
        @Advice.Argument(1) final PmProcessInstance pmProcessInstance) {
      final ContextStore<PmWorkUnit, AgentSpan> contextStore =
          InstrumentationContext.get(PmWorkUnit.class, AgentSpan.class);
      AgentSpan parentSpan = contextStore.get(pmProcessInstance);
      if (parentSpan == null) {
        // are we starting a sub process?
        PmWorkUnit parent = pmProcessInstance.getSpawner(pmContext);
        parentSpan = parent != null ? contextStore.get(parent) : null;
        if (parentSpan == null) {
          /// try to attach to the parent process (even if it's not correct but at least we're
          // keeping the trace continuity)
          parent = pmProcessInstance.getParentProcess(pmContext);
          parentSpan = parent != null ? contextStore.get(parent) : null;
        }
        AgentSpan span =
            startSpan(
                TibcoDecorator.TIBCO_PROCESS_OPERATION,
                parent != null ? parentSpan.context() : null);
        TibcoDecorator.DECORATE.afterStart(span);
        TibcoDecorator.DECORATE.onProcessStart(span, pmProcessInstance.getName(pmContext));
        contextStore.put(pmProcessInstance, span);
      }
    }
  }

  public static class ProcessEndAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void processEnds(@Advice.Argument(1) final PmProcessInstance pmProcessInstance) {
      AgentSpan span =
          InstrumentationContext.get(PmWorkUnit.class, AgentSpan.class).remove(pmProcessInstance);
      if (span != null) {
        TibcoDecorator.DECORATE.beforeFinish(span);
        span.finish();
      }
    }
  }

  public static class HandleEventAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onEvent(
        @Advice.This PmBehavior self,
        @Advice.Argument(0) PmContext pmContext,
        @Advice.Argument(1) PmWorkUnit workUnit,
        @Advice.Argument(2) PmEvent event) {
      final ContextStore<PmWorkUnit, AgentSpan> contextStore =
          InstrumentationContext.get(PmWorkUnit.class, AgentSpan.class);
      final AgentSpan span = contextStore.get(workUnit);
      if (span != null) {
        if (workUnit == event.getSource() && event.getData() instanceof Throwable) {
          TibcoDecorator.DECORATE.onError(span, (Throwable) event.getData());
        }
        if (self.isFinished(pmContext, workUnit)) {
          contextStore.remove(workUnit);
          TibcoDecorator.DECORATE.beforeFinish(span);
          span.finish();
        }
      }
    }
  }
}
