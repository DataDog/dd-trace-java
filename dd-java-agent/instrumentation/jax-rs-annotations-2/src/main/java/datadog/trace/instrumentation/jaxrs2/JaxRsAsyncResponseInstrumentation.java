package datadog.trace.instrumentation.jaxrs2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
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
      "datadog.trace.agent.tooling.ClassHierarchyIterable",
      "datadog.trace.agent.tooling.ClassHierarchyIterable$ClassIterator",
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

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void stopSpan(@Advice.This final AsyncResponse asyncResponse) {

      final ContextStore<AsyncResponse, AgentSpan> contextStore =
          InstrumentationContext.get(AsyncResponse.class, AgentSpan.class);

      final AgentSpan span = contextStore.get(asyncResponse);
      if (span != null) {
        contextStore.put(asyncResponse, null);
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }
  }

  public static class AsyncResponseThrowableAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final AsyncResponse asyncResponse,
        @Advice.Argument(0) final Throwable throwable) {

      final ContextStore<AsyncResponse, AgentSpan> contextStore =
          InstrumentationContext.get(AsyncResponse.class, AgentSpan.class);

      final AgentSpan span = contextStore.get(asyncResponse);
      if (span != null) {
        contextStore.put(asyncResponse, null);
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }
  }

  public static class AsyncResponseCancelAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void stopSpan(@Advice.This final AsyncResponse asyncResponse) {

      final ContextStore<AsyncResponse, AgentSpan> contextStore =
          InstrumentationContext.get(AsyncResponse.class, AgentSpan.class);

      final AgentSpan span = contextStore.get(asyncResponse);
      if (span != null) {
        contextStore.put(asyncResponse, null);
        span.setTag("canceled", true);
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }
  }
}
