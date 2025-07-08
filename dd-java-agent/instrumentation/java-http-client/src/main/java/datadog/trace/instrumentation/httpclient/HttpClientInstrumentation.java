package datadog.trace.instrumentation.httpclient;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class HttpClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap,
        Instrumenter.ForTypeHierarchy,
        Instrumenter.HasMethodAdvice {

  public HttpClientInstrumentation() {
    super("java-http-client");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("java.net.http.HttpClient");
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
        .and(not(named("jdk.internal.net.http.HttpClientFacade")))
        .and(extendsClass(named("java.net.http.HttpClient")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".BodyHandlerWrapper",
      packageName + ".BodyHandlerWrapper$BodySubscriberWrapper",
      packageName + ".CompletableFutureWrapper",
      packageName + ".JavaNetClientDecorator",
      packageName + ".ResponseConsumer"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("send"))
            .and(isPublic())
            .and(takesArguments(2))
            .and(takesArgument(0, named("java.net.http.HttpRequest"))),
        packageName + ".SendAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("sendAsync"))
            .and(isPublic())
            .and(takesArgument(0, named("java.net.http.HttpRequest")))
            .and(takesArgument(1, named("java.net.http.HttpResponse$BodyHandler"))),
        packageName + ".SendAsyncAdvice");
  }
}
