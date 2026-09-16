package datadog.trace.instrumentation.vertx_4_0.server;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.http.MultipartContentDecoder;
import io.vertx.ext.web.FileUpload;
import java.io.FileInputStream;
import java.util.List;
import java.util.function.BiFunction;

public class FileUploadHelper {

  public static BlockingException commitBlockingResponse(
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

  public static String readUploadContent(FileUpload upload, int maxBytes) {
    try {
      String path = upload.uploadedFileName();
      if (path == null || path.isEmpty()) {
        return "";
      }
      String charSet = upload.charSet();
      String contentType =
          charSet != null && !charSet.isEmpty()
              ? upload.contentType() + "; charset=" + charSet
              : upload.contentType();
      try (FileInputStream fis = new FileInputStream(path)) {
        return MultipartContentDecoder.readInputStream(fis, maxBytes, contentType);
      }
    } catch (Exception ignored) {
      return "";
    }
  }
}
