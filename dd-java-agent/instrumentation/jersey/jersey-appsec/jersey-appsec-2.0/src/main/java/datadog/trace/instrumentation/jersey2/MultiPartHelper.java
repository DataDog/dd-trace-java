package datadog.trace.instrumentation.jersey2;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.http.MultipartContentDecoder;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.ws.rs.core.MediaType;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.message.internal.MediaTypes;

public final class MultiPartHelper {

  public static final int MAX_CONTENT_BYTES = Config.get().getAppSecMaxFileContentBytes();
  public static final int MAX_FILES_TO_INSPECT = Config.get().getAppSecMaxFileContentCount();

  private MultiPartHelper() {}

  public static void collectBodyPart(
      FormDataBodyPart bodyPart,
      Map<String, List<String>> bodyMap,
      List<String> filenames,
      List<String> filesContent) {
    if (bodyMap != null
        && MediaTypes.typeEqual(MediaType.TEXT_PLAIN_TYPE, bodyPart.getMediaType())) {
      // BodyPartEntity allows re-reading the part without consuming the stream
      bodyMap.computeIfAbsent(bodyPart.getName(), k -> new ArrayList<>()).add(bodyPart.getValue());
    }
    FormDataContentDisposition cd;
    try {
      cd = bodyPart.getFormDataContentDisposition();
    } catch (Exception ignored) {
      // IllegalArgumentException on malformed Content-Disposition: skip this part gracefully
      // so a single bad part does not abort processing of the remaining parts.
      cd = null;
    }
    // rawFilename == null  → no filename attribute → form field → skip filenames and content
    // rawFilename == ""    → filename attribute present but empty → content YES, filenames NO
    // rawFilename != ""    → file with name → both
    String rawFilename = cd != null ? cd.getFileName() : null;
    if (filenames != null && rawFilename != null && !rawFilename.isEmpty()) {
      filenames.add(rawFilename);
    }
    if (filesContent != null && rawFilename != null && filesContent.size() < MAX_FILES_TO_INSPECT) {
      filesContent.add(readContent(bodyPart));
    }
  }

  public static String readContent(FormDataBodyPart bodyPart) {
    try {
      // getEntityAs(InputStream.class) is backed by BodyPartEntity which supports re-reading:
      // each call creates a fresh stream from the buffered MIME part data.
      InputStream is = bodyPart.getEntityAs(InputStream.class);
      if (is == null) return "";
      String contentType =
          bodyPart.getMediaType() != null ? bodyPart.getMediaType().toString() : null;
      return MultipartContentDecoder.readInputStream(is, MAX_CONTENT_BYTES, contentType);
    } catch (IOException ignored) {
      return "";
    }
  }

  public static BlockingException tryBlock(RequestContext ctx, Flow<Void> flow, String message) {
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
      BlockResponseFunction brf = ctx.getBlockResponseFunction();
      if (brf != null) {
        brf.tryCommitBlockingResponse(ctx.getTraceSegment(), rba);
        ctx.getTraceSegment().effectivelyBlocked();
        return new BlockingException(message);
      }
    }
    return null;
  }
}
