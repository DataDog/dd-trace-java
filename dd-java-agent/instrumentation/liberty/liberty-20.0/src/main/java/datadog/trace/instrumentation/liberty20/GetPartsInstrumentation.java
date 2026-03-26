package datadog.trace.instrumentation.liberty20;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

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
import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class GetPartsInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public GetPartsInstrumentation() {
    super("liberty");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "com.ibm.ws.webcontainer.srt.SRTServletRequest",
      "com.ibm.ws.webcontainer31.srt.SRTServletRequest31",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("getParts")).and(isPublic()).and(takesArguments(0)),
        GetPartsInstrumentation.class.getName() + "$GetFilenamesAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class GetFilenamesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Return Collection<?> parts,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (t != null || parts == null || parts.isEmpty()) {
        return;
      }
      List<String> filenames = new ArrayList<>();
      for (Object part : parts) {
        String name = null;
        // Try Servlet 3.1+ API first (getSubmittedFileName)
        try {
          name = (String) part.getClass().getMethod("getSubmittedFileName").invoke(part);
        } catch (Exception ignored) {
        }
        // Fallback: parse filename from Content-Disposition header (Servlet 3.0)
        if (name == null) {
          try {
            String cd =
                (String)
                    part.getClass()
                        .getMethod("getHeader", String.class)
                        .invoke(part, "content-disposition");
            if (cd != null) {
              for (String tok : cd.split(";")) {
                tok = tok.trim();
                if (tok.startsWith("filename=")) {
                  name = tok.substring(9).trim();
                  if (name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1);
                  }
                  break;
                }
              }
            }
          } catch (Exception ignored) {
          }
        }
        if (name != null && !name.isEmpty()) {
          filenames.add(name);
        }
      }
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
