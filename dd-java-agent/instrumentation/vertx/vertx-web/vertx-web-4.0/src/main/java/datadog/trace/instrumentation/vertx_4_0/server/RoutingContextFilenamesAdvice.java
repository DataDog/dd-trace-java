package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.vertx.ext.web.FileUpload;
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

    List<String> filenames = new ArrayList<>(uploads.size());
    for (FileUpload upload : uploads) {
      String name = upload.fileName();
      if (name != null && !name.isEmpty()) {
        filenames.add(name);
      }
    }
    if (filenames.isEmpty()) {
      return;
    }

    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, List<String>, Flow<Void>> cb =
        cbp.getCallback(EVENTS.requestFilesFilenames());
    if (cb == null) {
      return;
    }

    Flow<Void> flow = cb.apply(reqCtx, filenames);
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
      if (brf != null) {
        brf.tryCommitBlockingResponse(
            reqCtx.getTraceSegment(), (Flow.Action.RequestBlockingAction) action);
        if (throwable == null) {
          throwable = new BlockingException("Blocked request (multipart file upload)");
        }
      }
    }
  }
}
