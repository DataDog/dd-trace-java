package datadog.trace.instrumentation.resteasy;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

@AutoService(InstrumenterModule.class)
public class MultipartFormDataReaderInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public MultipartFormDataReaderInstrumentation() {
    super("resteasy");
  }

  @Override
  public String muzzleDirective() {
    return "multipart";
  }

  @Override
  public String instrumentedType() {
    return "org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataReader";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".MultipartHelper"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("readFrom")
            .and(takesArguments(6))
            .and(
                returns(
                    named(
                        "org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput"))),
        MultipartFormDataReaderInstrumentation.class.getName() + "$ReadFromAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class ReadFromAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Return final MultipartFormDataInput ret,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t)
        throws IOException {
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

      if (callback != null) {
        Map<String, List<String>> m = new HashMap<>();
        for (Map.Entry<String, List<InputPart>> e : ret.getFormDataMap().entrySet()) {
          List<String> strings = new ArrayList<>();
          m.put(e.getKey(), strings);
          for (InputPart inputPart : e.getValue()) {
            strings.add(inputPart.getBodyAsString());
          }
        }

        Flow<Void> flow = callback.apply(reqCtx, m);
        BlockingException be =
            MultipartHelper.tryBlock(
                reqCtx, flow, "Blocked request (for MultipartFormDataInput/readFrom)");
        if (be != null) {
          t = be;
        }
      }

      if (filenamesCallback != null) {
        List<String> filenames = MultipartHelper.collectFilenames(ret);
        if (!filenames.isEmpty()) {
          Flow<Void> filenamesFlow = filenamesCallback.apply(reqCtx, filenames);
          if (t == null) {
            BlockingException be =
                MultipartHelper.tryBlock(
                    reqCtx, filenamesFlow, "Blocked request (multipart file upload)");
            if (be != null) {
              t = be;
            }
          }
        }
      }

      if (t == null && contentCallback != null) {
        List<String> filesContent = MultipartHelper.collectFilesContent(ret);
        if (!filesContent.isEmpty()) {
          Flow<Void> contentFlow = contentCallback.apply(reqCtx, filesContent);
          BlockingException be =
              MultipartHelper.tryBlock(
                  reqCtx, contentFlow, "Blocked request (multipart file upload content)");
          if (be != null) {
            t = be;
          }
        }
      }
    }
  }
}
