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
import net.bytebuddy.asm.Advice;
import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;

@AutoService(InstrumenterModule.class)
public class FileItemIteratorInstrumenter extends InstrumenterModule.Iast
    implements Instrumenter.ForKnownTypes {

  public FileItemIteratorInstrumenter() {
    super("commons-fileupload", "fileitemiterator");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.commons.fileupload.FileItemIterator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("next").and(isPublic()).and(takesArguments(0)), getClass().getName() + "$NextAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class NextAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Return final FileItemStream fileItemStream,
        @Advice.This final FileItemIterator self,
        @ActiveRequestContext RequestContext reqCtx) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
        module.taintObjectIfTainted(ctx, fileItemStream, self);
      }
    }
  }
}
