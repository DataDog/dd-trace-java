package datadog.trace.instrumentation.vertx_3_4.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class HttpServerRequestInstrumentation extends AbstractHttpServerRequestInstrumentation {

  @Override
  protected ElementMatcher.Junction<MethodDescription> attributesFilter() {
    return isPrivate().and(named("attributes"));
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.core.http.impl.HttpServerRequestImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    super.methodAdvice(transformer);
    transformer.applyAdvice(
        isPublic().and(isMethod()).and(named("headers")).and(takesNoArguments()),
        HttpServerRequestInstrumentation.class.getName() + "$HeadersAdvice");
  }

  public static class HeadersAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Local("beforeHeaders") Object beforeHeaders,
        @Advice.FieldValue("headers") final Object headers) {
      beforeHeaders = headers;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    public static void onExit(
        @Advice.Local("beforeHeaders") final Object beforeHeaders,
        @Advice.Return final Object multiMap) {
      // only taint the map the first time
      if (beforeHeaders != multiMap) {
        final PropagationModule module = InstrumentationBridge.PROPAGATION;
        if (module != null) {
          module.taint(multiMap, SourceTypes.REQUEST_HEADER_VALUE);
        }
      }
    }
  }
}
