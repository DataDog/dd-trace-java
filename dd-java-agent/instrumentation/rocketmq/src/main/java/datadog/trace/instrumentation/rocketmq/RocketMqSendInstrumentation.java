package datadog.trace.instrumentation.rocketmq;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
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

import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public class RocketMqSendInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

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
  public Map<String, String> contextStore() {
    Map<String, String> map = new HashMap<>(1);
    map.put("org.apache.rocketmq.client.hook.SendMessageContext", "datadog.trace.bootstrap.instrumentation.api.AgentScope");
    return map;
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
      final ContextStore<SendMessageContext, AgentScope> contextStore =
          InstrumentationContext.get(SendMessageContext.class, AgentScope.class);

      defaultMqProducerImpl.registerSendMessageHook(RocketMqHook.buildSendHook(contextStore));
    }
  }
}
