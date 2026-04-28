package datadog.trace.instrumentation.netty41;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.multipart.InterfaceHttpPostRequestDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class HttpPostRequestDecoderInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  public HttpPostRequestDecoderInstrumentation() {
    super(
        NettyChannelPipelineInstrumentation.INSTRUMENTATION_NAME,
        NettyChannelPipelineInstrumentation.ADDITIONAL_INSTRUMENTATION_NAMES);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.netty.handler.codec.http.multipart.HttpPostMultipartRequestDecoder",
      "io.netty.handler.codec.http.multipart.HttpPostStandardRequestDecoder",
    };
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {
      new Reference.Builder("io.netty.handler.codec.http.multipart.HttpPostMultipartRequestDecoder")
          .withField(
              new String[0],
              Reference.EXPECTS_NON_STATIC,
              "currentStatus",
              "Lio/netty/handler/codec/http/multipart/HttpPostRequestDecoder$MultiPartStatus;")
          .withField(new String[0], Reference.EXPECTS_NON_STATIC, "isLastChunk", "Z")
          .build(),
      new Reference.Builder("io.netty.handler.codec.http.multipart.HttpPostStandardRequestDecoder")
          .withField(
              new String[0],
              Reference.EXPECTS_NON_STATIC,
              "currentStatus",
              "Lio/netty/handler/codec/http/multipart/HttpPostRequestDecoder$MultiPartStatus;")
          .withField(new String[0], Reference.EXPECTS_NON_STATIC, "isLastChunk", "Z")
          .build()
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".NettyMultipartHelper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("parseBody").and(takesArguments(0)).and(isPrivate()),
        getClass().getName() + "$ParseBodyAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  static class ParseBodyAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.This InterfaceHttpPostRequestDecoder thiz,
        @Advice.FieldValue("currentStatus") Enum currentStatus,
        @Advice.FieldValue("isLastChunk") boolean isLastChunk,
        @ActiveRequestContext RequestContext requestContext,
        @Advice.Thrown(readOnly = false) Throwable thr) {
      String statusName = currentStatus.name();
      if (!statusName.equals("EPILOGUE")) {
        // For multipart decoders, the PREEPILOGUE→EPILOGUE transition requires a second
        // parseBody() call that never comes when the full request arrives in one shot.
        // Fire on PREEPILOGUE + isLastChunk to handle that case.
        if (!statusName.equals("PREEPILOGUE") || !isLastChunk) {
          return;
        }
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());

      BiFunction<RequestContext, List<String>, Flow<Void>> filenamesCb =
          cbp.getCallback(EVENTS.requestFilesFilenames());

      BiFunction<RequestContext, List<String>, Flow<Void>> contentCb =
          cbp.getCallback(EVENTS.requestFilesContent());

      if (callback == null && filenamesCb == null && contentCb == null) {
        return;
      }

      Map<String, List<String>> attributes = callback != null ? new LinkedHashMap<>() : null;
      List<String> filenames = filenamesCb != null ? new ArrayList<>() : null;
      List<String> filesContent = contentCb != null ? new ArrayList<>() : null;

      RuntimeException exc =
          NettyMultipartHelper.collectBodyData(
              thiz.getBodyHttpDatas(), attributes, filenames, filesContent);

      if (callback != null) {
        // effectivelyBlocked() is intentionally absent: tryCommitBlockingResponse finishes
        // the span synchronously in this Netty path; calling it on a finished span throws.
        thr =
            NettyMultipartHelper.tryBlock(
                requestContext,
                callback.apply(requestContext, attributes),
                "Blocked request (multipart/urlencoded post data)");
      }

      if (filenames != null && !filenames.isEmpty()) {
        Flow<Void> filenamesFlow = filenamesCb.apply(requestContext, filenames);
        if (thr == null) {
          thr =
              NettyMultipartHelper.tryBlock(
                  requestContext, filenamesFlow, "Blocked request (multipart file upload)");
        }
      }

      if (thr == null && filesContent != null && !filesContent.isEmpty()) {
        thr =
            NettyMultipartHelper.tryBlock(
                requestContext,
                contentCb.apply(requestContext, filesContent),
                "Blocked request (multipart file upload content)");
      }

      if (exc != null) {
        // for it to be logged
        throw exc;
      }
    }

    Object muzzle() {
      return EmptyHttpHeaders.class;
    }
  }
}
