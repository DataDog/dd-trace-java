package datadog.trace.instrumentation.ons_client;


import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public class MqNormalInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  // 普通消息
  public static final String CLASS_NAME = "com.aliyun.openservices.ons.api.MessageListener";

  public MqNormalInstrumentation() {
    super("rocketmq", "rocketmq-client");
  }

  @Override
  public String hierarchyMarkerType() {
    return CLASS_NAME;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return named(hierarchyMarkerType());
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".RocketMqHook",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {

    transformation.applyAdvice(
        isMethod().
            and(named("start")).
            and(takesArguments(0)),
        MqNormalInstrumentation.class.getName() + "$AdviceStart");
  }


  public static class AdviceStart {
    @Advice.OnMethodEnter
    public static void onEnter() {
     // Action consume(Message var1, ConsumeContext var2);
    }
  }
}
