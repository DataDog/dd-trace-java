package datadog.trace.instrumentation.netty41;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
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
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.InterfaceHttpPostRequestDecoder;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
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
      packageName + ".NettyFileUploadContentReader",
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

      BiFunction<RequestContext, List<String>, Flow<Void>> contentCb =
          cbp.getCallback(EVENTS.requestFilesContent());

      if (callback == null && contentCb == null) {
        return;
      }

      RuntimeException exc = null;

      Map<String, List<String>> attributes = callback != null ? new LinkedHashMap<>() : null;
      List<String> filenames = new ArrayList<>();
      List<String> filesContent = contentCb != null ? new ArrayList<>() : null;
      for (InterfaceHttpData data : thiz.getBodyHttpDatas()) {
        if (attributes != null
            && data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
          String name = data.getName();
          List<String> values = attributes.get(name);
          if (values == null) {
            attributes.put(name, values = new ArrayList<>(1));
          }

          try {
            values.add(((Attribute) data).getValue());
          } catch (IOException e) {
            exc = new UndeclaredThrowableException(e);
          }
        } else if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
          FileUpload fileUpload = (FileUpload) data;
          String filename = fileUpload.getFilename();
          if (filename != null && !filename.isEmpty()) {
            filenames.add(filename);
          }
          if (contentCb != null && filesContent.size() < NettyFileUploadContentReader.MAX_FILES_TO_INSPECT) {
            filesContent.add(NettyFileUploadContentReader.readContent(fileUpload));
          }
        }
      }

      if (callback != null) {
        Flow<Void> flow = callback.apply(requestContext, attributes);
        Flow.Action action = flow.getAction();
        if (action instanceof Flow.Action.RequestBlockingAction) {
          Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
          BlockResponseFunction brf = requestContext.getBlockResponseFunction();
          if (brf != null) {
            brf.tryCommitBlockingResponse(requestContext.getTraceSegment(), rba);
            // effectivelyBlocked() is intentionally absent: tryCommitBlockingResponse finishes
            // the span synchronously in this Netty path; calling it on a finished span throws.
            thr = new BlockingException("Blocked request (multipart/urlencoded post data)");
          }
        }
      }

      if (!filenames.isEmpty()) {
        BiFunction<RequestContext, List<String>, Flow<Void>> filenamesCb =
            cbp.getCallback(EVENTS.requestFilesFilenames());
        if (filenamesCb != null) {
          Flow<Void> filenamesFlow = filenamesCb.apply(requestContext, filenames);
          Flow.Action filenamesAction = filenamesFlow.getAction();
          if (thr == null && filenamesAction instanceof Flow.Action.RequestBlockingAction) {
            Flow.Action.RequestBlockingAction rba =
                (Flow.Action.RequestBlockingAction) filenamesAction;
            BlockResponseFunction brf = requestContext.getBlockResponseFunction();
            if (brf != null) {
              brf.tryCommitBlockingResponse(requestContext.getTraceSegment(), rba);
              thr = new BlockingException("Blocked request (multipart file upload)");
            }
          }
        }
      }

      if (thr == null && contentCb != null && !filesContent.isEmpty()) {
        Flow<Void> contentFlow = contentCb.apply(requestContext, filesContent);
        Flow.Action contentAction = contentFlow.getAction();
        if (contentAction instanceof Flow.Action.RequestBlockingAction) {
          Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) contentAction;
          BlockResponseFunction brf = requestContext.getBlockResponseFunction();
          if (brf != null) {
            brf.tryCommitBlockingResponse(requestContext.getTraceSegment(), rba);
            thr = new BlockingException("Blocked request (multipart file upload content)");
          }
        }
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
