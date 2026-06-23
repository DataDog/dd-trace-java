package datadog.trace.instrumentation.jetty93;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.http.MultipartContentDecoder;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import javax.servlet.http.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultipartHelper {

  public static final int MAX_CONTENT_BYTES = Config.get().getAppSecMaxFileContentBytes();
  public static final int MAX_FILES_TO_INSPECT = Config.get().getAppSecMaxFileContentCount();

  private static final Logger log = LoggerFactory.getLogger(MultipartHelper.class);

  private MultipartHelper() {}

  /**
   * Extracts non-null, non-empty filenames from a collection of multipart {@link Part}s using
   * {@link Part#getSubmittedFileName()} (Servlet 3.1+, Jetty 9.3.x).
   *
   * @return list of filenames; never {@code null}, may be empty
   */
  public static List<String> extractFilenames(Collection<Part> parts) {
    if (parts == null || parts.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> filenames = new ArrayList<>(parts.size());
    for (Part part : parts) {
      try {
        String name = part.getSubmittedFileName();
        if (name != null && !name.isEmpty()) {
          filenames.add(name);
        }
      } catch (Exception ignored) {
        // malformed or inaccessible part — skip and continue with remaining parts
        log.debug("extractFilenames: skipping malformed part", ignored);
      }
    }
    return filenames;
  }

  /**
   * Extracts file content from a collection of multipart {@link Part}s. Form fields (those with a
   * {@code null} submitted filename) are skipped. Reads up to {@link #MAX_CONTENT_BYTES} bytes per
   * part, up to {@link #MAX_FILES_TO_INSPECT} parts total.
   *
   * @return list of decoded content strings; never {@code null}, may be empty
   */
  public static List<String> extractContents(Collection<Part> parts) {
    if (parts == null || parts.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> contents = new ArrayList<>(Math.min(parts.size(), MAX_FILES_TO_INSPECT));
    for (Part part : parts) {
      if (contents.size() >= MAX_FILES_TO_INSPECT) {
        break;
      }
      try {
        if (part.getSubmittedFileName() == null) {
          continue; // form field — skip
        }
        contents.add(readFileContent(part));
      } catch (Exception ignored) {
        log.debug("extractContents: skipping malformed part", ignored);
      }
    }
    return contents;
  }

  private static String readFileContent(Part part) {
    try (InputStream is = part.getInputStream()) {
      return MultipartContentDecoder.readInputStream(is, MAX_CONTENT_BYTES, part.getContentType());
    } catch (Exception e) {
      log.debug("readFileContent: stream read failed", e);
      return "";
    }
  }

  /**
   * Fires the {@code requestFilesContent} IG event and returns a {@link BlockingException} if the
   * WAF requests blocking, or {@code null} otherwise.
   */
  public static BlockingException fireFilesContentEvent(
      Collection<Part> parts, RequestContext reqCtx) {
    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, List<String>, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestFilesContent());
    if (callback == null) {
      return null;
    }
    List<String> contents = extractContents(parts);
    if (contents.isEmpty()) {
      return null;
    }
    Flow<Void> flow = callback.apply(reqCtx, contents);
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
      BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
      if (brf != null) {
        if (brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba)) {
          reqCtx.getTraceSegment().effectivelyBlocked();
          return new BlockingException("Blocked request (multipart file content)");
        }
      }
    }
    return null;
  }

  /**
   * Fires the {@code requestFilesFilenames} IG event and returns a {@link BlockingException} if the
   * WAF requests blocking, or {@code null} otherwise.
   */
  public static BlockingException fireFilenamesEvent(
      Collection<Part> parts, RequestContext reqCtx) {
    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, List<String>, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestFilesFilenames());
    if (callback == null) {
      return null;
    }
    List<String> filenames = extractFilenames(parts);
    if (filenames.isEmpty()) {
      return null;
    }
    Flow<Void> flow = callback.apply(reqCtx, filenames);
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
      BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
      if (brf != null) {
        if (brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba)) {
          reqCtx.getTraceSegment().effectivelyBlocked();
          return new BlockingException("Blocked request (multipart file upload)");
        }
      }
    }
    return null;
  }
}
