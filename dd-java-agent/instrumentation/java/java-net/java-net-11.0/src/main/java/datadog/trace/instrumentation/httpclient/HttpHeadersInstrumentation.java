package datadog.trace.instrumentation.httpclient;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class HttpHeadersInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap,
        Instrumenter.ForTypeHierarchy,
        Instrumenter.HasMethodAdvice {

  public HttpHeadersInstrumentation() {
    super("java-http-client");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("java.net.http.HttpRequest");
  }

  @Override
  public boolean isEnabled() {
    return JavaVirtualMachine.isJavaVersionAtLeast(11) && super.isEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return nameStartsWith("java.net.")
        .or(nameStartsWith("jdk.internal."))
        .and(extendsClass(named("java.net.http.HttpRequest")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CaseInsensitiveKey",
      packageName + ".HttpHeadersInjectAdapter",
      packageName + ".JavaNetClientDecorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isMethod().and(named("headers")), packageName + ".HeadersAdvice");
  }
}
