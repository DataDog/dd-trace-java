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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;
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

  private static final Reference REQUEST_REFERENCE =
      new Reference.Builder("org.eclipse.jetty.server.Request")
          .withMethod(new String[0], 0, "extractContentParameters", "V")
          .withField(new String[0], 0, "_contentParameters", MULTI_MAP_INTERNAL_NAME)
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
   *   <li>{@code contentParameters != null}: set by {@code extractContentParameters()} (the {@code
   *       getParameterMap()} path); means filenames were already reported via {@code
   *       GetFilenamesFromMultiPartAdvice}.
   *   <li>{@code _multiParts != null} (Jetty 9.4+, read via reflection): set by the first {@code
   *       getParts()} call; means filenames were already reported. In Jetty 9.3 this field does not
   *       exist, so the reflection throws {@code NoSuchFieldException} and we treat it as null.
   * </ul>
   */
  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class GetFilenamesAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static boolean before(
        @Advice.FieldValue("_contentParameters") final MultiMap<String> contentParameters,
        @Advice.This final Request request) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Collection.class);
      if (callDepth != 0 || contentParameters != null) {
        return false;
      }
      // Check the multipart cache field to detect repeated calls.
      // Jetty 9.4+: _multiParts is set after the first getParts() call.
      // Jetty 9.3.x: _multiPartInputStream is set instead (_multiParts doesn't exist).
      // A non-null value means getParts() was already invoked and filenames were reported.
      try {
        java.lang.reflect.Field f = request.getClass().getDeclaredField("_multiParts");
        f.setAccessible(true);
        if (f.get(request) != null) {
          return false;
        }
      } catch (NoSuchFieldException e9_3) {
        try {
          java.lang.reflect.Field f = request.getClass().getDeclaredField("_multiPartInputStream");
          f.setAccessible(true);
          if (f.get(request) != null) {
            return false;
          }
        } catch (Exception ignored) {
        }
      } catch (Exception ignored) {
      }
      return true;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Enter boolean proceed,
        @Advice.Return Collection parts,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      CallDepthThreadLocalMap.decrementCallDepth(Collection.class);
      if (!proceed || t != null || parts == null || parts.isEmpty()) {
        return;
      }
      Method getSubmittedFileName = null;
      try {
        getSubmittedFileName = parts.iterator().next().getClass().getMethod("getSubmittedFileName");
      } catch (Exception ignored) {
      }
      if (getSubmittedFileName == null) {
        return;
      }
      List<String> filenames = new ArrayList<>();
      for (Object part : parts) {
        try {
          String name = (String) getSubmittedFileName.invoke(part);
          if (name != null && !name.isEmpty()) {
            filenames.add(name);
          }
        } catch (Exception ignored) {
        }
      }
      if (filenames.isEmpty()) {
        return;
      }
      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, List<String>, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestFilesFilenames());
      if (callback == null) {
        return;
      }
      Flow<Void> flow = callback.apply(reqCtx, filenames);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
        if (brf != null) {
          brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
          if (t == null) {
            t = new BlockingException("Blocked request (multipart file upload)");
            reqCtx.getTraceSegment().effectivelyBlocked();
          }
        }
      }
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
        @Advice.Return Collection parts,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      CallDepthThreadLocalMap.decrementCallDepth(Collection.class);
      if (!proceed || t != null || parts == null || parts.isEmpty()) {
        return;
      }
      Method getSubmittedFileName = null;
      try {
        getSubmittedFileName = parts.iterator().next().getClass().getMethod("getSubmittedFileName");
      } catch (Exception ignored) {
      }
      if (getSubmittedFileName == null) {
        return;
      }
      List<String> filenames = new ArrayList<>();
      for (Object part : parts) {
        try {
          String name = (String) getSubmittedFileName.invoke(part);
          if (name != null && !name.isEmpty()) {
            filenames.add(name);
          }
        } catch (Exception ignored) {
        }
      }
      if (filenames.isEmpty()) {
        return;
      }
      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, List<String>, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestFilesFilenames());
      if (callback == null) {
        return;
      }
      Flow<Void> flow = callback.apply(reqCtx, filenames);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
        if (brf != null) {
          brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
          if (t == null) {
            t = new BlockingException("Blocked request (multipart file upload)");
            reqCtx.getTraceSegment().effectivelyBlocked();
          }
        }
      }
    }
  }
}
