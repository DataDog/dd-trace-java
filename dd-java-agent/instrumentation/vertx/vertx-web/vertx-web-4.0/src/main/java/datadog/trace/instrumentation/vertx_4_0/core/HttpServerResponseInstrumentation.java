package datadog.trace.instrumentation.vertx_4_0.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.vertx_4_0.server.VertxVersionMatcher.HTTP_1X_SERVER_RESPONSE;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.HttpResponseHeaderModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class HttpServerResponseInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {HTTP_1X_SERVER_RESPONSE};
  }

  public HttpServerResponseInstrumentation() {
    super("vertx", "vertx-4.0");
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    transformer.applyAdvice(
        named("putHeader")
            .and(
                takesArguments(CharSequence.class, CharSequence.class)
                    .or(takesArguments(String.class, String.class))),
        HttpServerResponseInstrumentation.class.getName() + "$PutHeaderAdvice1");
    transformer.applyAdvice(
        named("putHeader")
            .and(
                takesArguments(CharSequence.class, Iterable.class)
                    .or(takesArguments(String.class, Iterable.class))),
        HttpServerResponseInstrumentation.class.getName() + "$PutHeaderAdvice2");
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.vertx.core.http.HttpServerResponse";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  public static class PutHeaderAdvice1 {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) final CharSequence name, @Advice.Argument(1) CharSequence value) {
      if (name != null && value != null) {
        HttpResponseHeaderModule mod = InstrumentationBridge.RESPONSE_HEADER_MODULE;
        if (mod != null) {
          mod.onHeader(name.toString(), value.toString());
        }
      }
    }
  }

  public static class PutHeaderAdvice2 {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) final CharSequence name, @Advice.Argument(1) Iterable values) {
      if (name != null && values != null) {
        HttpResponseHeaderModule mod = InstrumentationBridge.RESPONSE_HEADER_MODULE;
        if (mod != null) {
          for (Object value : values) {
            if (value instanceof CharSequence) {
              String stValue = ((CharSequence) value).toString();
              mod.onHeader(name.toString(), stValue);
            }
          }
        }
      }
    }
  }
}
