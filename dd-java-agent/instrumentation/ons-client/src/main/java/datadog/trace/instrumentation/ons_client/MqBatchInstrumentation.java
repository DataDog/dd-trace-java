package datadog.trace.instrumentation.ons_client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.instrumentation.ons_client.MqDecorator.DECORATOR;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.aliyun.openservices.ons.api.Message;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class MqBatchInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice{
  public static final String CLASS_NAME = "com.aliyun.openservices.ons.api.batch.BatchMessageListener";

  public MqBatchInstrumentation() {super("rocketmq", "ons-client");}

  @Override
  public String hierarchyMarkerType() {
    return CLASS_NAME;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".MqDecorator",
        packageName + ".ExtractAdapter",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice(
        isMethod().
            and(named("consume")).
            and(takesArguments(2)),
        MqBatchInstrumentation.class.getName() + "$AdviceStart");
  }

  public static class AdviceStart {
    @Advice.OnMethodEnter
    public static AgentScope onEnter(@Advice.Argument(0) List<Message> messages) {
      return DECORATOR.OnStart(messages);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter final AgentScope scope){
      if (scope == null){
        return;
      }
      DECORATOR.OnEnd(scope);
    }
  }
}
