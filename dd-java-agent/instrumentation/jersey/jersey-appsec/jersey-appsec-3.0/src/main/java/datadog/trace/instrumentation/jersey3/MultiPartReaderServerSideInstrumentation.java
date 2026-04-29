package datadog.trace.instrumentation.jersey3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;
import org.glassfish.jersey.media.multipart.BodyPart;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.MultiPart;

@AutoService(InstrumenterModule.class)
public class MultiPartReaderServerSideInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public MultiPartReaderServerSideInstrumentation() {
    super("jersey");
  }

  @Override
  public String muzzleDirective() {
    return "multipart";
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.media.multipart.internal.MultiPartReaderServerSide";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".MultiPartHelper"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("readMultiPart")
            .and(isProtected())
            .and(returns(named("org.glassfish.jersey.media.multipart.MultiPart")))
            .and(takesArguments(6)),
        getClass().getName() + "$ReadMultiPartAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class ReadMultiPartAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Return final MultiPart ret,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (ret == null || t != null) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      BiFunction<RequestContext, List<String>, Flow<Void>> filenamesCallback =
          cbp.getCallback(EVENTS.requestFilesFilenames());
      BiFunction<RequestContext, List<String>, Flow<Void>> contentCallback =
          cbp.getCallback(EVENTS.requestFilesContent());
      if (callback == null && filenamesCallback == null && contentCallback == null) {
        return;
      }

      Map<String, List<String>> map = callback != null ? new HashMap<>() : null;
      List<String> filenames = filenamesCallback != null ? new ArrayList<>() : null;
      List<String> filesContent = contentCallback != null ? new ArrayList<>() : null;
      for (BodyPart bodyPart : ret.getBodyParts()) {
        if (!(bodyPart instanceof FormDataBodyPart)) {
          continue;
        }
        MultiPartHelper.collectBodyPart((FormDataBodyPart) bodyPart, map, filenames, filesContent);
      }

      if (map != null) {
        Flow<Void> flow = callback.apply(reqCtx, map);
        BlockingException be =
            MultiPartHelper.tryBlock(
                reqCtx, flow, "Blocked request (for MultiPartReaderServerSide/readMultiPart)");
        if (be != null) {
          t = be;
        }
      }

      if (filenames != null && !filenames.isEmpty()) {
        Flow<Void> filenamesFlow = filenamesCallback.apply(reqCtx, filenames);
        if (t == null) {
          BlockingException be =
              MultiPartHelper.tryBlock(
                  reqCtx, filenamesFlow, "Blocked request (multipart file upload)");
          if (be != null) {
            t = be;
          }
        }
      }

      if (filesContent != null && !filesContent.isEmpty()) {
        Flow<Void> contentFlow = contentCallback.apply(reqCtx, filesContent);
        if (t == null) {
          BlockingException be =
              MultiPartHelper.tryBlock(
                  reqCtx, contentFlow, "Blocked request (multipart file upload content)");
          if (be != null) {
            t = be;
          }
        }
      }
    }
  }
}
