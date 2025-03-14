package datadog.trace.instrumentation.rocketmq;

import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.rocketmq.client.impl.producer.DefaultMQProducerImpl;
import org.apache.rocketmq.client.producer.DefaultMQProducer;

@AutoService(InstrumenterModule.class)
public class RocketMqSendInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy , Instrumenter.HasMethodAdvice{

  public static final String CLASS_NAME = "org.apache.rocketmq.client.producer.DefaultMQProducer";

  public RocketMqSendInstrumentation() {
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
        packageName + ".TracingSendMessageHookImpl",
        packageName + ".TracingConsumeMessageHookImpl",
        packageName + ".RocketMqDecorator",
        packageName + ".TextMapExtractAdapter",
        packageName + ".TextMapInjectAdapter",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
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

      defaultMqProducerImpl.registerSendMessageHook(RocketMqHook.buildSendHook());
    }
  }
}
