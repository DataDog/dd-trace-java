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
import java.io.InputStream;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.commons.fileupload.FileItem;

@AutoService(InstrumenterModule.class)
public class FileItemInstrumenter extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy {

  public FileItemInstrumenter() {
    super("commons-fileupload", "fileitem");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.commons.fileupload.FileItem";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getInputStream").and(isPublic()).and(takesArguments(0)),
        getClass().getName() + "$GetInputStreamAdvice");
  }

  public static class GetInputStreamAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Return final InputStream inputStream, @Advice.This final FileItem self) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        final TaintedObjects to = IastContext.Provider.taintedObjects();
        module.taintObjectIfTainted(to, inputStream, self);
      }
    }
  }
}
