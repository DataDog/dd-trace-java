package datadog.trace.instrumentation.jakarta3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.jakarta3.JakartaRsAnnotationsDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.ws.rs.container.AsyncResponse;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class JakartaRsAsyncResponseInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public JakartaRsAsyncResponseInstrumentation() {
    super("jakarta-rs", "jakartars", "jakarta-rs-annotations");
  }

  static final ElementMatcher<ClassLoader> CLASS_LOADER_MATCHER =
      hasClassesNamed("jakarta.ws.rs.container.AsyncResponse");

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "jakarta.ws.rs.container.AsyncResponse", AgentSpan.class.getName());
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return CLASS_LOADER_MATCHER;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("jakarta.ws.rs.container.AsyncResponse"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.agent.tooling.ClassHierarchyIterable",
      "datadog.trace.agent.tooling.ClassHierarchyIterable$ClassIterator",
      packageName + ".JakartaRsAnnotationsDecorator",
      packageName + ".JakartaRsAnnotationsDecorator$MethodDetails",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("resume").and(takesArgument(0, Object.class)).and(isPublic()),
        JakartaRsAsyncResponseInstrumentation.class.getName() + "$AsyncResponseAdvice");
    transformation.applyAdvice(
        named("resume").and(takesArgument(0, Throwable.class)).and(isPublic()),
        JakartaRsAsyncResponseInstrumentation.class.getName() + "$AsyncResponseThrowableAdvice");
    transformation.applyAdvice(
        named("cancel"),
        JakartaRsAsyncResponseInstrumentation.class.getName() + "$AsyncResponseCancelAdvice");
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
