package datadog.trace.instrumentation.vertx_3_4.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxVersionMatcher.PARSABLE_HEADER_VALUE;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxVersionMatcher.VIRTUAL_HOST_HANDLER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

public abstract class AbstractHttpServerRequestInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  private final String className = AbstractHttpServerRequestInstrumentation.class.getName();

  protected AbstractHttpServerRequestInstrumentation() {
    super("vertx", "vertx-3.4");
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {PARSABLE_HEADER_VALUE, VIRTUAL_HOST_HANDLER};
  }

  @Override
  public void adviceTransformations(final AdviceTransformation transformation) {
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
  }

  protected abstract ElementMatcher.Junction<MethodDescription> attributesFilter();

  public static class ParamsAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(
        @Advice.Local("beforeParams") Object beforeParams,
        @Advice.FieldValue("params") final Object params) {
      beforeParams = params;
    }

    @Advice.OnMethodExit
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void onExit(
        @Advice.Local("beforeParams") final Object beforeParams,
        @Advice.Return final Object multiMap) {
      // only taint the map the first time
      if (beforeParams != multiMap) {
        final PropagationModule module = InstrumentationBridge.PROPAGATION;
        if (module != null) {
          try {
            module.taintObject(SourceTypes.REQUEST_PARAMETER_VALUE, multiMap);
          } catch (final Throwable e) {
            module.onUnexpectedException("params threw", e);
          }
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

    @Advice.OnMethodExit
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void onExit(
        @Advice.Local("beforeAttributes") final Object beforeAttributes,
        @Advice.Return final Object multiMap) {
      // only taint the map the first time
      if (beforeAttributes != multiMap) {
        final PropagationModule module = InstrumentationBridge.PROPAGATION;
        if (module != null) {
          try {
            module.taintObject(SourceTypes.REQUEST_PARAMETER_VALUE, multiMap);
          } catch (final Throwable e) {
            module.onUnexpectedException("formAttributes threw", e);
          }
        }
      }
    }
  }

  public static class DataAdvice {

    @Advice.OnMethodEnter
    @Source(SourceTypes.REQUEST_BODY)
    public static void onExit(@Advice.Argument(0) final Object data) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          module.taintObject(SourceTypes.REQUEST_BODY, data);
        } catch (final Throwable e) {
          module.onUnexpectedException("handleData threw", e);
        }
      }
    }
  }
}
