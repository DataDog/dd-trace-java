package datadog.trace.instrumentation.jetty94;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import javax.servlet.http.Part;

public class MultipartHelper {

  private MultipartHelper() {}

  /**
   * Extracts non-null, non-empty filenames from a collection of multipart {@link Part}s using
   * {@link Part#getSubmittedFileName()} (Servlet 3.1+, Jetty 9.4.x–10.x).
   *
   * @return list of filenames; never {@code null}, may be empty
   */
  public static List<String> extractFilenames(Collection<Part> parts) {
    if (parts == null || parts.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> filenames = new ArrayList<>();
    for (Part part : parts) {
      String name = part.getSubmittedFileName();
      if (name != null && !name.isEmpty()) {
        filenames.add(name);
      }
    }
    return filenames;
  }

  /**
   * Fires the {@code requestFilesFilenames} IG event and returns a {@link BlockingException} if the
   * WAF requests blocking, or {@code null} otherwise.
   */
  public static BlockingException fireFilenamesEvent(
      Collection<Part> parts, RequestContext reqCtx) {
    List<String> filenames = extractFilenames(parts);
    if (filenames.isEmpty()) {
      return null;
    }
    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, List<String>, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestFilesFilenames());
    if (callback == null) {
      return null;
    }
    Flow<Void> flow = callback.apply(reqCtx, filenames);
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
      BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
      if (brf != null) {
        brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
        reqCtx.getTraceSegment().effectivelyBlocked();
        return new BlockingException("Blocked request (multipart file upload)");
      }
    }
    return null;
  }
}
