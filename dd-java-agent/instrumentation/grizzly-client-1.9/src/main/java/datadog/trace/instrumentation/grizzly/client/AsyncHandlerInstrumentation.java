package datadog.trace.instrumentation.grizzly.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.grizzly.client.ClientDecorator.DECORATE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.ning.http.client.AsyncHandler;
import com.ning.http.client.Response;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Pair;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class AsyncHandlerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public AsyncHandlerInstrumentation() {
    super("grizzly-client", "ning");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("com.ning.http.client.AsyncHandler", Pair.class.getName());
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String hierarchyMarkerType() {
    return "com.ning.http.client.AsyncHandler";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".ClientDecorator"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        namedOneOf("onBodyPartReceived", "onHeadersReceived")
            .and(takesArgument(0, named("com.ning.http.client.HttpResponseBodyPart"))),
        getClass().getName() + "$OnActivity");
    transformer.applyAdvice(
        named("onCompleted").and(returns(named("com.ning.http.client.Response"))).and(isPublic()),
        getClass().getName() + "$OnComplete");
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static class OnActivity {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope resume(@Advice.This final AsyncHandler<?> handler) {
      ContextStore<AsyncHandler, Pair> contextStore =
          InstrumentationContext.get(AsyncHandler.class, Pair.class);
      Pair<AgentSpan, AgentSpan> pair = contextStore.get(handler);
      if (null == pair || !pair.hasRight()) {
        return null;
      }
      return activateSpan(pair.getRight());
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter final AgentScope scope) {
      if (null != scope) {
        scope.close();
      }
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static class OnComplete {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This final AsyncHandler<?> handler,
        @Advice.Local("contextPair") Pair<AgentSpan, AgentSpan> pair) {
      pair = InstrumentationContext.get(AsyncHandler.class, Pair.class).remove(handler);

      if (null == pair || !pair.hasLeft()) {
        return null;
      }
      return activateSpan(pair.getLeft());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter final AgentScope scope,
        @Advice.This final AsyncHandler<?> handler,
        @Advice.Local("contextPair") Pair<AgentSpan, AgentSpan> pair,
        @Advice.Return final Response response) {
      if (null != scope) {
        scope.close();
      }

      if (null == pair || !pair.hasRight()) {
        return;
      }
      AgentSpan requestSpan = pair.getRight();
      if (null != requestSpan) {
        DECORATE.onResponse(requestSpan, response);
        DECORATE.beforeFinish(requestSpan);
        requestSpan.finish();
      }
    }
  }
}
