package datadog.trace.instrumentation.grizzly.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.grizzly.client.ClientDecorator.DECORATE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHandler;
import com.ning.http.client.Response;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Pair;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class AsyncCompletionHandlerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public AsyncCompletionHandlerInstrumentation() {
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
    return "com.ning.http.client.AsyncCompletionHandler";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
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
        named("onCompleted")
            .and(takesArgument(0, named("com.ning.http.client.Response")))
            .and(isPublic()),
        getClass().getName() + "$OnComplete");
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static class OnActivity {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentSpan resume(@Advice.This final AsyncCompletionHandler<?> handler) {
      ContextStore<AsyncHandler, Pair> contextStore =
          InstrumentationContext.get(AsyncHandler.class, Pair.class);
      Pair<AgentSpan, AgentSpan> pair = contextStore.get(handler);
      if (null == pair) {
        return null;
      }
      return pair.getRight();
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static class OnComplete {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This final AsyncCompletionHandler<?> handler,
        @Advice.Argument(0) final Response response) {
      ContextStore<AsyncHandler, Pair> contextStore =
          InstrumentationContext.get(AsyncHandler.class, Pair.class);
      Pair<AgentSpan, AgentSpan> pair = contextStore.get(handler);
      if (null == pair) {
        return null;
      }
      contextStore.put(handler, null);
      AgentSpan requestSpan = pair.getRight();
      if (null != requestSpan) {
        DECORATE.onResponse(requestSpan, response);
        DECORATE.beforeFinish(requestSpan);
        requestSpan.finish();
      }
      AgentSpan parent = pair.getLeft();
      if (null == parent) {
        return null;
      }
      return activateSpan(parent);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter final AgentScope scope) {
      if (null != scope) {
        scope.close();
      }
    }
  }
}
