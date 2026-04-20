package datadog.trace.instrumentation.hazelcast39;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.proxy.ClientMapProxy;
import com.hazelcast.client.spi.impl.NonSmartClientInvocationService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;

/**
 * This instrumentation captures the operation name when it is being set on the Client Message.
 *
 * <p>It is required because there is no getter for this value until 4.0.
 */
public final class ClientMessageInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.hazelcast.client.impl.protocol.ClientMessage";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(namedOneOf("setOperationName"))
            .and(takesArgument(0, named(String.class.getName()))),
        getClass().getName() + "$OperationCapturingAdvice");
  }

  public static class OperationCapturingAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter(
        @Advice.This ClientMessage that, @Advice.Argument(0) final String operationName) {

      InstrumentationContext.get(ClientMessage.class, String.class).put(that, operationName);
    }

    public static void muzzleCheck(
        // Moved in 4.0
        ClientMapProxy proxy,

        // Renamed in 3.9
        NonSmartClientInvocationService invocationService) {
      proxy.getServiceName();
      invocationService.start();
    }
  }
}
