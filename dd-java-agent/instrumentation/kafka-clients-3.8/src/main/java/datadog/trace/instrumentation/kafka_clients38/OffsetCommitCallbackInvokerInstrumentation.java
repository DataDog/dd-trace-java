package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

// new - this instrumentation is completely new.
// the purpose of this class is to provide us with information on consumer group and cluster ID
public class OffsetCommitCallbackInvokerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {
  public OffsetCommitCallbackInvokerInstrumentation() {
    super("kafka");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.clients.consumer.internals.OffsetCommitCallbackInvoker";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("enqueueUserCallbackInvocation")),
        packageName + ".OffsetCommitCallbackInvokerAdvice");
  }
}
