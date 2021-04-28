package datadog.trace.instrumentation.v3_9;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.instrumentation.v3_9.HazelcastConstants.DEFAULT_ENABLED;
import static datadog.trace.instrumentation.v3_9.HazelcastConstants.INSTRUMENTATION_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.proxy.ClientMapProxy;
import com.hazelcast.client.spi.impl.NonSmartClientInvocationService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * This instrumentation captures the operation name when it is being set on the Client Message.
 *
 * <p>It is required because there is no getter for this value until 4.0.
 */
@AutoService(Instrumenter.class)
public class ClientMessageInstrumentation extends Instrumenter.Tracing {

  public ClientMessageInstrumentation() {
    super(INSTRUMENTATION_NAME);
  }

  @Override
  protected boolean defaultEnabled() {
    return DEFAULT_ENABLED;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".HazelcastConstants"};
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("com.hazelcast.client.impl.protocol.ClientMessage");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "com.hazelcast.client.impl.protocol.ClientMessage", String.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
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
