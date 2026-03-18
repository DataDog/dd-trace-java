package datadog.trace.instrumentation.powerjob;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.powerjob.PowJobDecorator.DECORATOR;
import static datadog.trace.instrumentation.powerjob.PowerjobConstants.ProcessType.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BroadcastProcessor;
import tech.powerjob.worker.core.processor.sdk.MapProcessor;
import tech.powerjob.worker.core.processor.sdk.MapReduceProcessor;

@AutoService(InstrumenterModule.class)
public class BasicProcessorInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy , Instrumenter.HasMethodAdvice{
  public BasicProcessorInstrumentation() {
    super("powerjob");
  }

  @Override
  public String hierarchyMarkerType() {
    return PowerjobConstants.Processer.BASIC_PROCESSOR;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
//        .and(not(implementsInterface(named(PowerjobConstants.Processer.BROADCAST_PROCESSOR))))
//        .and(not(implementsInterface(named(PowerjobConstants.Processer.MAP_PROCESSOR))))
//        .and(not(implementsInterface(named(PowerjobConstants.Processer.MAP_REDUCE_PROCESSOR))))
//        .and(not(named(PowerjobConstants.Processer.MAP_PROCESSOR)))
//        .and(not(named(PowerjobConstants.Processer.MAP_REDUCE_PROCESSOR)))
        ;
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".PowerjobConstants",
        packageName + ".PowerjobConstants$Tags",
        packageName + ".PowerjobConstants$ProcessType",
        packageName + ".PowerjobConstants$Processer",
        packageName + ".PowJobDecorator"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice( isMethod()
            .and(isPublic())
            .and(nameStartsWith("process"))
            .and(takesArgument(0,named("tech.powerjob.worker.core.processor.TaskContext"))),
        this.getClass().getName() + "$BasicProcessorAdvice");
  }

  public static class BasicProcessorAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope execute(@Advice.This Object processor,@Advice.Argument(0) TaskContext context){
      String processType;
      if (processor instanceof BroadcastProcessor){
        processType = BROADCAST;
      }else if (processor instanceof MapProcessor){
        processType = MAP;
      }else if (processor instanceof MapReduceProcessor){
        processType = MAP_REDUCE;
      }else{
        processType = BASE;
      }
      AgentSpan span = DECORATOR.createSpan(processor.getClass().getName(),context, processType);
      AgentScope agentScope = activateSpan(span);
      return agentScope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      DECORATOR.onError(scope, throwable);
      DECORATOR.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
    }
  }
}
