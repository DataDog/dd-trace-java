package datadog.trace.instrumentation.hazelcast4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.hazelcast4.HazelcastConstants.DEFAULT_ENABLED;
import static datadog.trace.instrumentation.hazelcast4.HazelcastConstants.INSTRUMENTATION_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.hazelcast.client.impl.clientside.HazelcastClientInstanceImpl;
import com.hazelcast.client.impl.proxy.ClientMapProxy;
import com.hazelcast.client.impl.spi.impl.ClientInvocation;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class ClientInvocationInstrumentation extends Instrumenter.Tracing {

  public ClientInvocationInstrumentation() {
    super(INSTRUMENTATION_NAME);
  }

  @Override
  protected boolean defaultEnabled() {
    return DEFAULT_ENABLED;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".HazelcastConstants",
      packageName + ".HazelcastDecorator",
      packageName + ".SpanFinishingExecutionCallback"
    };
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("com.hazelcast.client.impl.spi.impl.ClientInvocation");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "com.hazelcast.client.impl.spi.impl.ClientInvocation", String.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<MethodDescription>, String> transformers = new HashMap<>(4);

    transformers.put(isMethod().and(named("invokeOnSelection")), packageName + ".InvocationAdvice");
    transformers.put(
        isConstructor()
            .and(
                takesArgument(
                    0, named("com.hazelcast.client.impl.clientside.HazelcastClientInstanceImpl"))),
        getClass().getName() + "$ConstructAdvice");

    return transformers;
  }

  public static class ConstructAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void constructorExit(
        @Advice.This ClientInvocation that,
        @Advice.Argument(0) final HazelcastClientInstanceImpl hazelcastInstance) {

      if (hazelcastInstance != null) {
        hazelcastInstance.getLifecycleService();
        if (hazelcastInstance.getLifecycleService().isRunning()) {

          InstrumentationContext.get(ClientInvocation.class, String.class)
              .put(that, hazelcastInstance.getName());
        }
      }
    }

    public static void muzzleCheck(
        // Moved in 4.0
        ClientMapProxy proxy) {
      proxy.getServiceName();
    }
  }
}
