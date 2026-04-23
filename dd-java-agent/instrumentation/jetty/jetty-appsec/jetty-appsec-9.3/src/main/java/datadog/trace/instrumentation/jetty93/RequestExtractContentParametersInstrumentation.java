package datadog.trace.instrumentation.jetty93;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Collection;
import java.util.function.BiFunction;
import javax.servlet.http.Part;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.MultiMap;

@AutoService(InstrumenterModule.class)
public class RequestExtractContentParametersInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  private static final String MULTI_MAP_INTERNAL_NAME = "Lorg/eclipse/jetty/util/MultiMap;";

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

  // Discriminates Jetty 9.3.x–9.4.9.x ([9.3, 9.4.10)):
  //  - _contentParameters + extractContentParameters(void) exist from 9.3+ (excludes 9.2)
  //  - _multiPartInputStream exists in 9.3.x and early 9.4.x (< 9.4.10); replaced by _multiParts
  //    in 9.4.10 (covered by jetty-appsec-9.4)
  private static final Reference REQUEST_REFERENCE =
      new Reference.Builder("org.eclipse.jetty.server.Request")
          .withMethod(new String[0], 0, "extractContentParameters", "V")
          .withField(new String[0], 0, "_contentParameters", MULTI_MAP_INTERNAL_NAME)
          .withField(
              new String[0],
              0,
              "_multiPartInputStream",
              "Lorg/eclipse/jetty/util/MultiPartInputStreamParser;")
          .build();

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {REQUEST_REFERENCE};
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class ExtractContentParametersAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static boolean before(@Advice.FieldValue("_contentParameters") final MultiMap<String> map) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Request.class);
      return callDepth == 0 && map == null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Enter boolean proceed,
        @Advice.FieldValue("_contentParameters") final MultiMap<String> map,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      CallDepthThreadLocalMap.decrementCallDepth(Request.class);
      if (!proceed) {
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
   *   <li>{@code _multiPartInputStream != null}: set by the first {@code getParts()} call in Jetty
   *       9.3.x; means filenames were already reported.
   * </ul>
   */
  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class GetFilenamesAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static boolean before(
        @Advice.FieldValue("_contentParameters") final MultiMap<String> contentParameters,
        @Advice.FieldValue(value = "_multiPartInputStream", typing = Assigner.Typing.DYNAMIC)
            final Object multiPartInputStream) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Collection.class);
      return callDepth == 0 && contentParameters == null && multiPartInputStream == null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Enter boolean proceed,
        @Advice.Return Collection<Part> parts,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      CallDepthThreadLocalMap.decrementCallDepth(Collection.class);
      if (!proceed || t != null || parts == null || parts.isEmpty()) {
        return;
      }
      t = MultipartHelper.fireFilenamesEvent(parts, reqCtx);
    }
  }

  /**
   * Fires the {@code requestFilesFilenames} event when multipart content is parsed via the internal
   * {@code getParts(MultiMap)} path triggered by {@code getParameter*()} / {@code
   * getParameterMap()} — i.e. when the application never calls public {@code getParts()}. In Jetty
   * 9.3+, {@code extractContentParameters()} assigns {@code _contentParameters} before calling this
   * method, so {@code map == null} cannot be used as a "first parse" guard here; the call-depth
   * guard prevents double-firing when {@code getParts()} internally delegates to this method.
   */
  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class GetFilenamesFromMultiPartAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static boolean before() {
      return CallDepthThreadLocalMap.incrementCallDepth(Collection.class) == 0;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Enter boolean proceed,
        @Advice.Return Collection<Part> parts,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      CallDepthThreadLocalMap.decrementCallDepth(Collection.class);
      if (!proceed || t != null || parts == null || parts.isEmpty()) {
        return;
      }
      t = MultipartHelper.fireFilenamesEvent(parts, reqCtx);
    }
  }
}
