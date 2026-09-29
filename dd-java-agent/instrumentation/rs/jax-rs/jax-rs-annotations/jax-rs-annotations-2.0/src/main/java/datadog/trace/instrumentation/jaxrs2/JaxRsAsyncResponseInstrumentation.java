package datadog.trace.instrumentation.jaxrs2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.ResourceMethodSpanTracker.isInnermost;
import static datadog.trace.instrumentation.jaxrs2.JaxRsAnnotationsDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import java.util.Map;
import javax.ws.rs.container.AsyncResponse;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class JaxRsAsyncResponseInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public JaxRsAsyncResponseInstrumentation() {
    super("jax-rs", "jaxrs", "jax-rs-annotations");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "javax.ws.rs.container.AsyncResponse", AgentSpan.class.getName());
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.ws.rs.container.AsyncResponse";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JaxRsAnnotationsDecorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("resume").and(takesArgument(0, Object.class)).and(isPublic()),
        JaxRsAsyncResponseInstrumentation.class.getName() + "$AsyncResponseAdvice");
    transformer.applyAdvice(
        named("resume").and(takesArgument(0, Throwable.class)).and(isPublic()),
        JaxRsAsyncResponseInstrumentation.class.getName() + "$AsyncResponseThrowableAdvice");
    transformer.applyAdvice(
        named("cancel"),
        JaxRsAsyncResponseInstrumentation.class.getName() + "$AsyncResponseCancelAdvice");
  }

  public static class AsyncResponseAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final AsyncResponse asyncResponse,
        @Advice.Thrown Throwable throwable,
        @Advice.Return final boolean succeeded) {
      if (throwable == null && !succeeded) {
        // AsyncResponse#resume() returns false when the response was already resolved (a
        // second resume()/cancel() call on the same response); nothing changed, so there is
        // nothing to tag or finish. If the call threw instead of returning, treat it the same
        // as before: @Advice.Return defaults to false on the exception path, so that alone
        // does not mean "already resolved".
        return;
      }

      final ContextStore<AsyncResponse, AgentSpan> contextStore =
          InstrumentationContext.get(AsyncResponse.class, AgentSpan.class);

      final AgentSpan span = contextStore.get(asyncResponse);
      if (span != null) {
        DECORATE.onError(span, throwable);
        if (isInnermost(span)) {
          // resume() was called synchronously, nested inside the still-running resource
          // method that owns this span (ResourceMethodSpanTracker.isInnermost(span) says its
          // invocation is still the innermost open one on this thread). Let that method's own
          // exit advice close the scope and finish the span instead of finishing it here,
          // which would both double-finish the span and finish it prematurely while the
          // resource method may still be doing work under it.
          return;
        }
        DECORATE.finishUnlessAlreadyClaimed(contextStore, asyncResponse);
      }
    }
  }

  public static class AsyncResponseThrowableAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final AsyncResponse asyncResponse,
        @Advice.Argument(0) final Throwable throwable,
        @Advice.Thrown final Throwable methodThrew,
        @Advice.Return final boolean succeeded) {
      if (methodThrew == null && !succeeded) {
        // see comment in AsyncResponseAdvice#stopSpan
        return;
      }

      final ContextStore<AsyncResponse, AgentSpan> contextStore =
          InstrumentationContext.get(AsyncResponse.class, AgentSpan.class);

      final AgentSpan span = contextStore.get(asyncResponse);
      if (span != null) {
        DECORATE.onError(span, throwable);
        if (isInnermost(span)) {
          // see comment in AsyncResponseAdvice#stopSpan
          return;
        }
        DECORATE.finishUnlessAlreadyClaimed(contextStore, asyncResponse);
      }
    }
  }

  public static class AsyncResponseCancelAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final AsyncResponse asyncResponse,
        @Advice.Thrown Throwable throwable,
        @Advice.Return final boolean succeeded) {
      if (throwable == null && !succeeded) {
        // AsyncResponse#cancel() returns false when the response was already resolved (a
        // second resume()/cancel() call on the same response); nothing changed, so there is
        // nothing to tag or finish. If the call threw instead of returning, treat it the same
        // as before: @Advice.Return defaults to false on the exception path, so that alone
        // does not mean "already resolved".
        return;
      }

      final ContextStore<AsyncResponse, AgentSpan> contextStore =
          InstrumentationContext.get(AsyncResponse.class, AgentSpan.class);

      final AgentSpan span = contextStore.get(asyncResponse);
      if (span != null) {
        if (throwable != null) {
          DECORATE.onError(span, throwable);
        } else {
          span.setTag("canceled", true);
        }
        if (isInnermost(span)) {
          // see comment in AsyncResponseAdvice#stopSpan
          return;
        }
        DECORATE.finishUnlessAlreadyClaimed(contextStore, asyncResponse);
      }
    }
  }
}
