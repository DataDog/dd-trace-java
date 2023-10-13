package datadog.trace.instrumentation.vertx_4_0.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.vertx_4_0.server.VertxVersionMatcher.HTTP_1X_SERVER_RESPONSE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

public abstract class AbstractHttpServerRequestInstrumentation extends Instrumenter.Iast {

  private final String className = AbstractHttpServerRequestInstrumentation.class.getName();

  public AbstractHttpServerRequestInstrumentation() {
    super("vertx", "vertx-4.0");
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {HTTP_1X_SERVER_RESPONSE};
  }

  protected abstract ElementMatcher.Junction<MethodDescription> attributesFilter();

  @Override
  public void adviceTransformations(final AdviceTransformation transformation) {
    transformation.applyAdvice(
        isPublic().and(isMethod()).and(named("headers")).and(takesNoArguments()),
        className + "$HeadersAdvice");
    transformation.applyAdvice(
        isPublic().and(isMethod()).and(named("params")).and(takesNoArguments()),
        className + "$ParamsAdvice");
    transformation.applyAdvice(
        isMethod().and(takesNoArguments()).and(attributesFilter()),
        className + "$AttributesAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(named("handleData").or(named("onData")))
            .and(takesArguments(1).and(takesArgument(0, named("io.vertx.core.buffer.Buffer")))),
        className + "$DataAdvice");
    transformation.applyAdvice(
        isPublic().and(isMethod()).and(named("cookies")).and(returns(Set.class)),
        className + "$CookiesAdvice");
    transformation.applyAdvice(
        isPublic().and(isMethod()).and(named("getCookie")).and(takesArgument(0, String.class)),
        className + "$GetCookieAdvice");
  }

  public static class ParamsAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(
        @Advice.Local("beforeParams") Object beforeParams,
        @Advice.FieldValue("params") final Object params) {
      beforeParams = params;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void onExit(
        @Advice.Local("beforeParams") final Object beforeParams,
        @Advice.Return final Object multiMap) {
      // only taint the map the first time
      if (beforeParams != multiMap) {
        final PropagationModule module = InstrumentationBridge.PROPAGATION;
        if (module != null) {
          module.taintObject(SourceTypes.REQUEST_PARAMETER_VALUE, multiMap);
        }
      }
    }
  }

  public static class AttributesAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(
        @Advice.Local("beforeAttributes") Object beforeAttributes,
        @Advice.FieldValue("attributes") final Object attributes) {
      beforeAttributes = attributes;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void onExit(
        @Advice.Local("beforeAttributes") final Object beforeAttributes,
        @Advice.Return final Object multiMap) {
      // only taint the map the first time
      if (beforeAttributes != multiMap) {
        final PropagationModule module = InstrumentationBridge.PROPAGATION;
        if (module != null) {
          module.taintObject(SourceTypes.REQUEST_PARAMETER_VALUE, multiMap);
        }
      }
    }
  }

  public static class HeadersAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    public static void onExit(@Advice.Return final Object multiMap) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taintObject(SourceTypes.REQUEST_HEADER_VALUE, multiMap);
      }
    }
  }

  public static class DataAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_BODY)
    public static void onExit(@Advice.Argument(0) final Object data) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taintObject(SourceTypes.REQUEST_BODY, data);
      }
    }
  }

  public static class CookiesAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_COOKIE_VALUE)
    public static void onExit(@Advice.Return final Set<Object> cookies) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taintObjects(SourceTypes.REQUEST_COOKIE_VALUE, cookies);
      }
    }
  }

  public static class GetCookieAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_COOKIE_VALUE)
    public static void onExit(@Advice.Return final Object cookie) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taintObject(SourceTypes.REQUEST_COOKIE_VALUE, cookie);
      }
    }
  }
}
