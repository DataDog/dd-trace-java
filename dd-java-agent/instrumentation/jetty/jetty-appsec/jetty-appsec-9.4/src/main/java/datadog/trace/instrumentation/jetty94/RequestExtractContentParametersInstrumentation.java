package datadog.trace.instrumentation.jetty94;

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
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;
import javax.servlet.http.Part;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatcher;
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

  // Discriminates Jetty 9.4.10–10.x ([9.4.10, 11.0)):
  //  - _contentParameters + extractContentParameters(void) exist from 9.3+ (excludes 9.2)
  //  - javax.servlet.http.Part exists in 9.4–10.x classpath (excludes Jetty 11+ which uses jakarta)
  //  - classLoaderMatcher checks _multiParts field exists (any type) to exclude Jetty 9.3.x and
  //    early 9.4.x (< 9.4.10) which use _multiPartInputStream instead (covered by
  // jetty-appsec-9.3).
  //    The _multiParts field type changed between 9.4.10 (MultiParts) and 10.x
  //    (MultiPartFormInputStream), so a typed muzzle reference cannot cover the full range.
  private static final Reference REQUEST_REFERENCE =
      new Reference.Builder("org.eclipse.jetty.server.Request")
          .withMethod(new String[0], 0, "extractContentParameters", "V")
          .withField(new String[0], 0, "_contentParameters", MULTI_MAP_INTERNAL_NAME)
          .build();

  private static final Reference JAVAX_PART_REFERENCE =
      new Reference.Builder("javax.servlet.http.Part").build();

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {REQUEST_REFERENCE, JAVAX_PART_REFERENCE};
  }

  /** Accepts classloaders where {@code Request._multiParts} field exists (any type). */
  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return MultiPartsFieldMatcher.INSTANCE;
  }

  public static class MultiPartsFieldMatcher
      extends ElementMatcher.Junction.ForNonNullValues<ClassLoader> {
    public static final ElementMatcher.Junction<ClassLoader> INSTANCE =
        new MultiPartsFieldMatcher();

    @Override
    protected boolean doMatch(ClassLoader cl) {
      try (InputStream is = cl.getResourceAsStream("org/eclipse/jetty/server/Request.class")) {
        if (is == null) {
          return false;
        }
        ClassReader cr = new ClassReader(is);
        final boolean[] found = {false};
        cr.accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public FieldVisitor visitField(
                  int access, String name, String descriptor, String signature, Object value) {
                if ("_multiParts".equals(name)) {
                  found[0] = true;
                }
                return null;
              }
            },
            ClassReader.SKIP_CODE);
        return found[0];
      } catch (IOException e) {
        return false;
      }
    }
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
   *   <li>{@code _multiParts != null}: set by the first {@code getParts()} call in Jetty 9.4+;
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
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Collection.class);
      return callDepth == 0 && contentParameters == null && multiParts == null;
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
      List<String> filenames = MultipartHelper.extractFilenames(parts);
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
   * getParameterMap()} — i.e. when the application never calls public {@code getParts()}.
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
      List<String> filenames = MultipartHelper.extractFilenames(parts);
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
