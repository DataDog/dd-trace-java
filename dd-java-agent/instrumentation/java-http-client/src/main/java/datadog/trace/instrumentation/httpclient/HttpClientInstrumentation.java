package datadog.trace.instrumentation.httpclient;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class HttpClientInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForTypeHierarchy {

  public HttpClientInstrumentation() {
    super("java-http-client");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return ClassLoaderMatchers.hasClassNamed("java.net.http.HttpClient");
  }

  @Override
  public boolean isEnabled() {
    return Platform.isJavaVersionAtLeast(11) && super.isEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return nameStartsWith("java.net.")
        .or(nameStartsWith("jdk.internal."))
        .and(not(named("jdk.internal.net.http.HttpClientFacade")))
        .and(HierarchyMatchers.extendsClass(named("java.net.http.HttpClient")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".BodyHandlerWrapper",
      packageName + ".BodyHandlerWrapper$BodySubscriberWrapper",
      packageName + ".CompletableFutureWrapper",
      packageName + ".JavaNetClientDecorator",
      packageName + ".ResponseConsumer",
      packageName + ".SendAdvice11",
      packageName + ".SendAsyncAdvice11"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("send"))
            .and(isPublic())
            .and(takesArguments(2))
            .and(takesArgument(0, named("java.net.http.HttpRequest"))),
        getClass().getName() + "$SendAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(named("sendAsync"))
            .and(isPublic())
            .and(takesArgument(0, named("java.net.http.HttpRequest")))
            .and(takesArgument(1, named("java.net.http.HttpResponse$BodyHandler"))),
        getClass().getName() + "$SendAsyncAdvice");
  }

  public static class SendAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(@Advice.Argument(value = 0) final Object httpRequest) {
      return SendAdvice11.methodEnter(httpRequest);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Argument(0) final Object httpRequest,
        @Advice.Return final Object httpResponse,
        @Advice.Thrown final Throwable throwable) {
      SendAdvice11.methodExit(scope, httpRequest, httpResponse, throwable);
    }
  }

  public static class SendAsyncAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.Argument(value = 0) final Object httpRequest,
        @Advice.Argument(value = 1, readOnly = false, typing = Assigner.Typing.DYNAMIC)
            Object bodyHandler) {
      final Object[] ret = SendAsyncAdvice11.methodEnter(httpRequest, bodyHandler);
      if (ret[0] != null) {
        bodyHandler = ret[0];
      }
      return (AgentScope) ret[1];
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Argument(value = 0) final Object httpRequest,
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object future,
        @Advice.Thrown final Throwable throwable) {
      Object ret = SendAsyncAdvice11.methodExit(scope, httpRequest, future, throwable);
      if (ret != null) {
        future = ret;
      }
    }
  }
}
