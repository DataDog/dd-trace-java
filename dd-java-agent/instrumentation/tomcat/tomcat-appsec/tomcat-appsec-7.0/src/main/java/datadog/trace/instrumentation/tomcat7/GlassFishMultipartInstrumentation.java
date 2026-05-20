package datadog.trace.instrumentation.tomcat7;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;

/**
 * GlassFish/Payara does not have {@code Request.parseParts()} - instead {@code Request.getParts()}
 * delegates to {@code org.apache.catalina.fileupload.Multipart.getParts()}. This instrumentation
 * hooks that GlassFish-specific class to report uploaded file names and contents to the AppSec WAF
 * via the {@code requestFilesFilenames} and {@code requestFilesContent} IG events.
 *
 * <p>Because {@code org.apache.catalina.fileupload.Multipart} does not exist in standard Tomcat,
 * this instrumentation is automatically skipped by ByteBuddy on non-GlassFish containers.
 */
@AutoService(InstrumenterModule.class)
public class GlassFishMultipartInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public GlassFishMultipartInstrumentation() {
    super("tomcat");
  }

  @Override
  public String muzzleDirective() {
    return "glassfish";
  }

  @Override
  public String instrumentedType() {
    return "org.apache.catalina.fileupload.Multipart";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.tomcat7.GlassFishBlockingHelper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getParts").and(takesArguments(0)).and(isPublic()),
        getClass().getName() + "$GetPartsAdvice");
  }

  public static class GetPartsAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Return(readOnly = false) Collection<?> parts,
        @Advice.Thrown Throwable t,
        @Advice.FieldValue("request") org.apache.catalina.Request catRequest) {
      if (t != null || parts == null || parts.isEmpty()) {
        return;
      }

      AgentSpan agentSpan = AgentTracer.activeSpan();
      if (agentSpan == null) {
        return;
      }
      RequestContext reqCtx = agentSpan.getRequestContext();
      if (reqCtx == null || reqCtx.getData(RequestContextSlot.APPSEC) == null) {
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

      if (GlassFishBlockingHelper.processPartsAndBlock(
          parts, reqCtx, catRequest, filenamesCb, contentCb)) {
        parts = Collections.emptyList();
      }
    }
  }
}
