package datadog.trace.instrumentation.jetty_client91;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

@AutoService(InstrumenterModule.class)
public final class FutureResponseListenerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public FutureResponseListenerInstrumentation() {
    super("jetty-client");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.client.util.FutureResponseListener";
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>(4);
    contextStore.put("org.eclipse.jetty.client.api.Request", AgentSpan.class.getName());
    contextStore.put(
        "org.eclipse.jetty.client.api.Response$ResponseListener",
        "org.eclipse.jetty.client.api.Request");
    return contextStore;
  }

  @Override
  public String muzzleDirective() {
    return "listener";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(
                takesArgument(0, named("org.eclipse.jetty.client.api.Request"))
                    .and(takesArguments(2))),
        getClass().getName() + "$Link");
  }

  public static final class Link {
    @Advice.OnMethodExit
    public static void link(
        @Advice.This Response.ResponseListener listener, @Advice.Argument(0) Request request) {
      // this provides safe access to the request from higher up the class hierarchy where methods
      // we want to instrument are defined
      InstrumentationContext.get(Response.ResponseListener.class, Request.class)
          .put(listener, request);
    }

    private String muzzleCheck(Request request) {
      return request.getMethod(); // Before 9.1 returns an HttpMethod.
    }
  }
}
