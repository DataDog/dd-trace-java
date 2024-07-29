package datadog.trace.instrumentation.commons.fileupload;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.io.InputStream;
import net.bytebuddy.asm.Advice;
import org.apache.commons.fileupload.FileItem;

@AutoService(InstrumenterModule.class)
public class FileItemInstrumenter extends InstrumenterModule.Iast
    implements Instrumenter.ForKnownTypes {

  public FileItemInstrumenter() {
    super("commons-fileupload", "fileitem");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.commons.fileupload.FileItem",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getInputStream").and(isPublic()).and(takesArguments(0)),
        getClass().getName() + "$GetInputStreamAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetInputStreamAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Return final InputStream inputStream,
        @Advice.This final FileItem self,
        @ActiveRequestContext RequestContext reqCtx) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
        module.taintObjectIfTainted(ctx, inputStream, self);
      }
    }
  }
}
