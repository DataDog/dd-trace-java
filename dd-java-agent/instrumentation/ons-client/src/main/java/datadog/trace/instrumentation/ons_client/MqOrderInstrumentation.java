package datadog.trace.instrumentation.ons_client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.instrumentation.ons_client.MqDecorator.DECORATOR;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.aliyun.openservices.ons.api.Message;
import com.aliyun.openservices.ons.api.order.OrderAction;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class MqOrderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice{

  public static final String CLASS_NAME = "com.aliyun.openservices.ons.api.order.MessageOrderListener";

  public MqOrderInstrumentation() {
    super("rocketmq", "ons-client");
  }

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
        MqOrderInstrumentation.class.getName() + "$AdviceStart");
  }

  public static class AdviceStart {
    @Advice.OnMethodEnter
    public static AgentScope onEnter(@Advice.Argument(0) Message message) {
      return DECORATOR.OnStart(message);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter final AgentScope scope,@Advice.Return OrderAction action){
     if(scope == null){
       return;
     }
      String status;
      switch (action){
        case Success:
          status = "success";
          break;
        case Suspend:
          status = "suspend";
          break;
        default:
          status = "unknown";
      }
      DECORATOR.OnEnd(scope,status);
    }
  }
}
