package datadog.trace.instrumentation.rocketmq;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.rocketmq.client.hook.SendMessageContext;
import org.apache.rocketmq.client.impl.producer.DefaultMQProducerImpl;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import java.util.HashMap;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class RocketMqSendInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

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
  public Map<String, String> contextStore() {
    return singletonMap(
        "org.apache.rocketmq.client.hook.SendMessageContext", AgentScope.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
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
        isMethod().and(named("start")).and(takesArguments(0)),
        RocketMqSendInstrumentation.class.getName() + "$AdviceStart");
  }

  public static class AdviceStart {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.FieldValue(value = "defaultMQProducerImpl", declaringType = DefaultMQProducer.class)
            DefaultMQProducerImpl defaultMqProducerImpl) {

      defaultMqProducerImpl.registerSendMessageHook(
          RocketMqHook.buildSendHook(
              InstrumentationContext.get(SendMessageContext.class, AgentScope.class)));
    }
  }
}
