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
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;

/**
 * GlassFish/Payara does not have {@code Request.parseParts()} - instead {@code Request.getParts()}
 * delegates to {@code org.apache.catalina.fileupload.Multipart.getParts()}. This instrumentation
 * hooks that GlassFish-specific class to report uploaded file names and contents to the AppSec WAF
 * via the {@code requestFilesFilenames} and {@code requestFilesContent} IG events.
 *
 * <p>Because {@code org.apache.catalina.fileupload.Multipart} does not exist in standard Tomcat,
 * this instrumentation is automatically skipped by ByteBuddy on non-GlassFish containers.
 *
 * <p>This advice casts each {@code Part} through the {@code javax.servlet.http.Part} interface
 * (which {@code org.apache.catalina.fileupload.PartItem} implements) to avoid Java module-system
 * access restrictions that prevent reflective invocation of methods on GlassFish-internal classes.
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
        @Advice.FieldValue("request") org.apache.catalina.connector.Request requestField) {
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

      // Extract servlet request/response for fallback blocking when no BlockResponseFunction is
      // registered (Payara: TomcatServerInstrumentation is muzzled out for Payara's response type).
      // @Advice.FieldValue inlines direct field access into Multipart.getParts() - no reflection
      // needed. Typed as connector.Request to also call getResponse() without reflection.
      HttpServletRequest fallbackReq = null;
      HttpServletResponse fallbackResp = null;
      if (requestField != null) {
        fallbackReq = requestField;
        fallbackResp = requestField.getResponse();
      }

      if (GlassFishBlockingHelper.processPartsAndBlock(
          parts, reqCtx, fallbackReq, fallbackResp, filenamesCb, contentCb)) {
        parts = Collections.emptyList();
      }
    }
  }
}
