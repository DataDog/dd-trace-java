package datadog.trace.instrumentation.rocketmq;


import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.impl.consumer.DefaultMQPushConsumerImpl;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public class RocketMqInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public static final String CLASS_NAME = "org.apache.rocketmq.client.consumer.DefaultMQPushConsumer";

  public RocketMqInstrumentation() {
    super("rocketmq", "rocketmq-client");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed(CLASS_NAME);
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return named(CLASS_NAME);
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".RocketMqHook",
        packageName + ".TracingConsumeMessageHookImpl",
        packageName + ".TracingSendMessageHookImpl",
        packageName + ".RocketMqDecorator",
        packageName + ".TextMapExtractAdapter",
        packageName + ".TextMapInjectAdapter",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {

    transformation.applyAdvice(
        isMethod().
            and(named("start")).
            and(takesArguments(0)),
        RocketMqInstrumentation.class.getName() + "$AdviceStart");
  }


  public static class AdviceStart {
    @Advice.OnMethodEnter
    public static void onEnter(
        @Advice.FieldValue(
            value = "defaultMQPushConsumerImpl", declaringType = DefaultMQPushConsumer.class)
        DefaultMQPushConsumerImpl defaultMqPushConsumerImpl) {
      defaultMqPushConsumerImpl.registerConsumeMessageHook(
          RocketMqHook.CONSUME_MESSAGE_HOOK);
    }
  }
}
