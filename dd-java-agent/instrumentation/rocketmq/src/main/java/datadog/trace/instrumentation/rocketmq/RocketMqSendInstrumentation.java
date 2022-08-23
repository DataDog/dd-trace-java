package datadog.trace.instrumentation.rocketmq;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.rocketmq.client.impl.producer.DefaultMQProducerImpl;
import org.apache.rocketmq.client.producer.DefaultMQProducer;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public class RocketMqSendInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public static final String CLASS_NAME = "org.apache.rocketmq.client.producer.DefaultMQProducer";

  public RocketMqSendInstrumentation() {
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
        packageName + ".TracingSendMessageHookImpl",
        packageName + ".TracingConsumeMessageHookImpl",
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
        RocketMqSendInstrumentation.class.getName() + "$AdviceStart");
  }

  public static class AdviceStart {
    @Advice.OnMethodEnter
    public static void onEnter(
        @Advice.FieldValue(value = "defaultMQProducerImpl", declaringType = DefaultMQProducer.class)
        DefaultMQProducerImpl defaultMqProducerImpl) {
      defaultMqProducerImpl.registerSendMessageHook(
          RocketMqHook.SEND_MESSAGE_HOOK);
    }
  }
}
