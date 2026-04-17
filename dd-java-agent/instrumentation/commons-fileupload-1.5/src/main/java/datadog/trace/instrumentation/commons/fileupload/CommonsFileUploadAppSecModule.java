package datadog.trace.instrumentation.commons.fileupload;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;
import org.apache.commons.fileupload.FileItem;

@AutoService(InstrumenterModule.class)
public class CommonsFileUploadAppSecModule extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public CommonsFileUploadAppSecModule() {
    super("commons-fileupload");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.commons.fileupload.servlet.ServletFileUpload";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.commons.fileupload.FileItemContentReader",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("parseRequest")
            .and(isPublic())
            .and(takesArgument(0, named("javax.servlet.http.HttpServletRequest"))),
        getClass().getName() + "$ParseRequestAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class ParseRequestAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Return final List<FileItem> fileItems,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (t != null || fileItems == null || fileItems.isEmpty()) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, List<String>, Flow<Void>> filenamesCallback =
          cbp.getCallback(EVENTS.requestFilesFilenames());
      BiFunction<RequestContext, List<String>, Flow<Void>> contentCallback =
          cbp.getCallback(EVENTS.requestFilesContent());
      if (filenamesCallback == null && contentCallback == null) {
        return;
      }

      List<String> filenames = new ArrayList<>();
      for (FileItem fileItem : fileItems) {
        if (fileItem.isFormField()) {
          continue;
        }
        String name = fileItem.getName();
        if (name != null && !name.isEmpty()) {
          filenames.add(name);
        }
      }
      if (filenames.isEmpty()) {
        return;
      }

      // Fire filenames event
      if (filenamesCallback != null) {
        Flow<Void> flow = filenamesCallback.apply(reqCtx, filenames);
        Flow.Action action = flow.getAction();
        if (action instanceof Flow.Action.RequestBlockingAction) {
          Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
          BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
          if (brf != null) {
            brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
            t = new BlockingException("Blocked request (multipart file upload)");
            reqCtx.getTraceSegment().effectivelyBlocked();
            return;
          }
        }
      }

      // Fire content event only if not blocked
      if (contentCallback == null) {
        return;
      }
      List<String> filesContent = new ArrayList<>();
      for (FileItem fileItem : fileItems) {
        if (fileItem.isFormField()) {
          continue;
        }
        String name = fileItem.getName();
        if (name == null || name.isEmpty()) {
          continue;
        }
        filesContent.add(FileItemContentReader.readContent(fileItem));
      }
      if (filesContent.isEmpty()) {
        return;
      }
      Flow<Void> contentFlow = contentCallback.apply(reqCtx, filesContent);
      Flow.Action contentAction = contentFlow.getAction();
      if (contentAction instanceof Flow.Action.RequestBlockingAction) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) contentAction;
        BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
        if (brf != null) {
          brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
          t = new BlockingException("Blocked request (multipart file upload content)");
          reqCtx.getTraceSegment().effectivelyBlocked();
        }
      }
    }
  }
}
