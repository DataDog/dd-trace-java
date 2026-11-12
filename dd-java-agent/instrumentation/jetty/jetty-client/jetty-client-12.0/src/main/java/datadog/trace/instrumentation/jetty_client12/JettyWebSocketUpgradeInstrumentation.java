package datadog.trace.instrumentation.jetty_client12;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.jetty_client12.JettyClientDecorator.DECORATE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;

@AutoService(InstrumenterModule.class)
public class JettyWebSocketUpgradeInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public JettyWebSocketUpgradeInstrumentation() {
    super("jetty-client");
  }

  @Override
  public String muzzleDirective() {
    return "jetty-websocket-core-client";
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".JettyClientDecorator"};
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.eclipse.jetty.client.Request", AgentSpan.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("upgrade")
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.eclipse.jetty.client.Response")))
            .and(takesArgument(1, named("org.eclipse.jetty.io.EndPoint"))),
        getClass().getName() + "$WebSocketUpgradeAdvice");
  }

  public static class WebSocketUpgradeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope beforeUpgrade(@Advice.Argument(0) Response response) {
      AgentSpan span =
          InstrumentationContext.get(Request.class, AgentSpan.class).get(response.getRequest());
      return span == null ? null : activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterUpgrade(
        @Advice.Argument(0) Response response,
        @Advice.FieldValue("futureCoreSession") CompletableFuture<?> futureCoreSession,
        @Advice.Enter ContextScope scope,
        @Advice.Thrown Throwable failure) {
      AgentSpan span =
          InstrumentationContext.get(Request.class, AgentSpan.class).get(response.getRequest());
      try {
        if (span != null && failure == null) {
          // Upgrades bypass response completion listeners even when endpoint failures are
          // swallowed.
          DECORATE.onResponse(span, response);
          try {
            futureCoreSession.getNow(null);
          } catch (CompletionException e) {
            DECORATE.onError(span, e.getCause());
          } catch (CancellationException e) {
            DECORATE.onError(span, e);
          }
          DECORATE.beforeFinish(span);
        }
      } finally {
        if (scope != null) {
          scope.close();
        }
        if (span != null && failure == null) {
          span.finish();
        }
      }
    }

    /**
     * Lets Muzzle fail CI if the upgrade method is removed or its signature changes, instead of
     * silently skipping instrumentation.
     */
    private void muzzleCheck(
        CoreClientUpgradeRequest request, Response response, EndPoint endPoint) {
      request.upgrade(response, endPoint);
    }
  }
}
