package datadog.trace.instrumentation.commons.fileupload;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;

@AutoService(InstrumenterModule.class)
public class FileItemIteratorInstrumenter extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy {

  public FileItemIteratorInstrumenter() {
    super("commons-fileupload", "fileitemiterator");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.commons.fileupload.FileItemIterator";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("next").and(isPublic()).and(takesArguments(0)), getClass().getName() + "$NextAdvice");
  }

  public static class NextAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Return final FileItemStream fileItemStream,
        @Advice.This final FileItemIterator self) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        final TaintedObjects to = IastContext.Provider.taintedObjects();
        module.taintObjectIfTainted(to, fileItemStream, self);
      }
    }
  }
}
