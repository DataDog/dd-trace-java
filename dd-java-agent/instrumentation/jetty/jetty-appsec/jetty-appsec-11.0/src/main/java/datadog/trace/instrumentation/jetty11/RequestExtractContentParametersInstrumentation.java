package datadog.trace.instrumentation.jetty11;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.fieldType;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import jakarta.servlet.http.Part;
import java.util.Collection;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.MultiMap;

@AutoService(InstrumenterModule.class)
public class RequestExtractContentParametersInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType,
        Instrumenter.WithTypeStructure,
        Instrumenter.HasMethodAdvice {

  public RequestExtractContentParametersInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.Request";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".MultipartHelper"};
  }

  // Discriminates Jetty 11.0.x ([11.0, 12.0)):
  //  - _contentParameters: MultiMap field exists in 11.x (excludes Jetty 12 where
  //    org.eclipse.jetty.server.Request was removed)
  //  - _dispatcherType: jakarta.servlet.DispatcherType in the Request bytecode (excludes
  //    Jetty 9.4–10.x where the field type is javax.servlet.DispatcherType). Checked against
  //    Request.class bytecode, so it works even when both javax and jakarta are on the classpath.
  // NOTE: _multiParts changes type at 11.0.10 (MultiPartFormInputStream → MultiParts); both
  // are handled transparently because GetFilenamesAdvice reads it with typing=DYNAMIC.
  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    return declaresField(
            named("_contentParameters").and(fieldType(named("org.eclipse.jetty.util.MultiMap"))))
        .and(
            declaresField(
                named("_dispatcherType").and(fieldType(named("jakarta.servlet.DispatcherType")))));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("extractContentParameters").and(takesArguments(0)).or(named("getParts")),
        getClass().getName() + "$ExtractContentParametersAdvice");
    transformer.applyAdvice(
        named("getParts").and(takesArguments(0)), getClass().getName() + "$GetFilenamesAdvice");
    transformer.applyAdvice(
        named("getParts").and(takesArguments(1)),
        getClass().getName() + "$GetFilenamesFromMultiPartAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class ExtractContentParametersAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static boolean before(@Advice.FieldValue("_contentParameters") final MultiMap<String> map) {
      if (map != null) {
        return false;
      }
      CallDepthThreadLocalMap.incrementCallDepth(Request.class);
      return true;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void after(
        @Advice.Enter boolean proceed,
        @Advice.FieldValue("_contentParameters") final MultiMap<String> map,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (!proceed) {
        return;
      }
      if (CallDepthThreadLocalMap.decrementCallDepth(Request.class) != 0) {
        return;
      }
      if (map == null || map.isEmpty()) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      if (callback == null) {
        return;
      }

      Flow<Void> flow = callback.apply(reqCtx, map);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        BlockResponseFunction blockResponseFunction = reqCtx.getBlockResponseFunction();
        if (blockResponseFunction != null) {
          blockResponseFunction.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
          if (t == null) {
            t = new BlockingException("Blocked request (for Request/extractContentParameters)");
            reqCtx.getTraceSegment().effectivelyBlocked();
          }
        }
      }
    }
  }

  /**
   * Fires the {@code requestFilesFilenames} event when the application calls public {@code
   * getParts()}. Guards prevent double-firing:
   *
   * <ul>
   *   <li>{@code _contentParameters != null}: set by {@code extractContentParameters()} (the {@code
   *       getParameterMap()} path); means filenames were already reported via {@code
   *       GetFilenamesFromMultiPartAdvice}.
   *   <li>{@code _multiParts != null}: set by the first {@code getParts()} call in Jetty 11.0.x;
   *       means filenames were already reported.
   * </ul>
   */
  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class GetFilenamesAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static boolean before(
        @Advice.FieldValue("_contentParameters") final MultiMap<String> contentParameters,
        @Advice.FieldValue(value = "_multiParts", typing = Assigner.Typing.DYNAMIC)
            final Object multiParts) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(MultipartHelper.class);
      return callDepth == 0 && contentParameters == null && multiParts == null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void after(
        @Advice.Enter boolean proceed,
        @Advice.Return Collection<Part> parts,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      CallDepthThreadLocalMap.decrementCallDepth(MultipartHelper.class);
      if (!proceed || t != null || parts == null || parts.isEmpty()) {
        return;
      }
      t = MultipartHelper.fireFilenamesEvent(parts, reqCtx);
      if (t == null) {
        t = MultipartHelper.fireFilesContentEvent(parts, reqCtx);
      }
    }
  }

  /**
   * Fires the {@code requestFilesFilenames} event when multipart content is parsed via the internal
   * {@code getParts(MultiMap)} path triggered by {@code getParameter*()} / {@code
   * getParameterMap()} — i.e. when the application never calls public {@code getParts()}.
   */
  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class GetFilenamesFromMultiPartAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static boolean before() {
      return CallDepthThreadLocalMap.incrementCallDepth(MultipartHelper.class) == 0;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void after(
        @Advice.Enter boolean proceed,
        @Advice.Return Collection<Part> parts,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      CallDepthThreadLocalMap.decrementCallDepth(MultipartHelper.class);
      if (!proceed || t != null || parts == null || parts.isEmpty()) {
        return;
      }
      t = MultipartHelper.fireFilenamesEvent(parts, reqCtx);
      if (t == null) {
        t = MultipartHelper.fireFilesContentEvent(parts, reqCtx);
      }
    }
  }
}
