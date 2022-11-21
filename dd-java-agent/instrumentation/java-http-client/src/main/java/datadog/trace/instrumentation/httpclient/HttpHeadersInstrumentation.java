package datadog.trace.instrumentation.httpclient;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.api.Platform;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class HttpHeadersInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForTypeHierarchy {

  public HttpHeadersInstrumentation() {
    super("java-http-client");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return ClassLoaderMatchers.hasClassNamed("java.net.http.HttpRequest");
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
        .and(HierarchyMatchers.extendsClass(named("java.net.http.HttpRequest")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".HeadersAdvice11"};
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("headers")), getClass().getName() + "$HeadersAdvice");
  }

  public static class HeadersAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object headers) {
      headers = HeadersAdvice11.methodExit(headers);
    }
  }
}
