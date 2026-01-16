package datadog.trace.instrumentation.hazelcast4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.hazelcast.client.impl.clientside.HazelcastClientInstanceImpl;
import com.hazelcast.client.impl.proxy.ClientMapProxy;
import com.hazelcast.client.impl.spi.impl.ClientInvocation;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;

public final class ClientInvocationInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.hazelcast.client.impl.spi.impl.ClientInvocation";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("invokeOnSelection")),
        "datadog.trace.instrumentation.hazelcast4.InvocationAdvice");
    transformer.applyAdvice(
        isConstructor()
            .and(
                takesArgument(
                    0, named("com.hazelcast.client.impl.clientside.HazelcastClientInstanceImpl"))),
        getClass().getName() + "$ConstructAdvice");
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
