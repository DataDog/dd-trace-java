package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.http.MultipartContentDecoder;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.vertx.ext.web.FileUpload;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;

@RequiresRequestContext(RequestContextSlot.APPSEC)
class RoutingContextFilenamesAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  static int before() {
    return CallDepthThreadLocalMap.incrementCallDepth(FileUpload.class);
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  static void after(
      @Advice.Enter int depth,
      @Advice.Return Collection<FileUpload> uploads,
      @ActiveRequestContext RequestContext reqCtx,
      @Advice.Thrown(readOnly = false) Throwable throwable) {
    CallDepthThreadLocalMap.decrementCallDepth(FileUpload.class);
    if (depth != 0 || throwable != null || uploads == null || uploads.isEmpty()) {
      return;
    }

    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, List<String>, Flow<Void>> filenamesCb =
        cbp.getCallback(EVENTS.requestFilesFilenames());
    BiFunction<RequestContext, List<String>, Flow<Void>> contentCb =
        cbp.getCallback(EVENTS.requestFilesContent());
    if (filenamesCb == null && contentCb == null) {
      return;
    }

    int maxFiles = Config.get().getAppSecMaxFileContentCount();
    int maxBytes = Config.get().getAppSecMaxFileContentBytes();
    List<String> filenames = null;
    List<String> filesContent = null;

    for (FileUpload upload : uploads) {
      String name = upload.fileName();
      if (filenamesCb != null && name != null && !name.isEmpty()) {
        if (filenames == null) {
          filenames = new ArrayList<>();
        }
        filenames.add(name);
      }
      if (contentCb != null
          && maxFiles > 0
          && (filesContent == null || filesContent.size() < maxFiles)) {
        if (filesContent == null) {
          filesContent = new ArrayList<>();
        }
        filesContent.add(readUploadContent(upload, maxBytes));
      }
    }

    if (filenamesCb != null && filenames != null) {
      throwable =
          commitBlockingResponse(
              filenamesCb, reqCtx, filenames, "Blocked request (multipart file upload)");
    }

    if (throwable != null) {
      return;
    }

    if (contentCb != null && filesContent != null) {
      throwable =
          commitBlockingResponse(contentCb, reqCtx, filesContent, "Blocked request (file content)");
    }
  }

  private static BlockingException commitBlockingResponse(
      BiFunction<RequestContext, List<String>, Flow<Void>> cb,
      RequestContext reqCtx,
      List<String> data,
      String reason) {
    Flow<Void> flow = cb.apply(reqCtx, data);
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
      if (brf != null) {
        brf.tryCommitBlockingResponse(
            reqCtx.getTraceSegment(), (Flow.Action.RequestBlockingAction) action);
        return new BlockingException(reason);
      }
    }
    return null;
  }

  private static String readUploadContent(FileUpload upload, int maxBytes) {
    try {
      String path = upload.uploadedFileName();
      if (path == null || path.isEmpty()) {
        return "";
      }
      try (FileInputStream fis = new FileInputStream(path)) {
        return MultipartContentDecoder.readInputStream(fis, maxBytes, upload.contentType());
      }
    } catch (Exception ignored) {
      return "";
    }
  }
}
