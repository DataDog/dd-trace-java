package datadog.trace.instrumentation.commons.fileupload;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemIterator;

@AutoService(InstrumenterModule.class)
public class ServletFileUploadInstrumenter extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType {

  public ServletFileUploadInstrumenter() {
    super("commons-fileupload", "servlet");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.commons.fileupload.servlet.ServletFileUpload";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("parseRequest")
            .and(isPublic())
            .and(takesArgument(0, named("javax.servlet.http.HttpServletRequest"))),
        getClass().getName() + "$ParseRequestAdvice");
    transformer.applyAdvice(
        named("parseParameterMap")
            .and(isPublic())
            .and(takesArgument(0, named("javax.servlet.http.HttpServletRequest"))),
        getClass().getName() + "$ParseParameterMapAdvice");
    transformer.applyAdvice(
        named("getItemIterator")
            .and(isPublic())
            .and(takesArgument(0, named("javax.servlet.http.HttpServletRequest"))),
        getClass().getName() + "$GetItemIteratorAdvice");
  }

  public static class ParseRequestAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_MULTIPART_PARAMETER)
    public static void onExit(@Advice.Return final List<FileItem> fileItems) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        final TaintedObjects to = IastContext.Provider.taintedObjects();
        for (final FileItem fileItem : fileItems) {
          module.taintObject(to, fileItem, SourceTypes.REQUEST_MULTIPART_PARAMETER);
        }
      }
    }
  }

  public static class ParseParameterMapAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_MULTIPART_PARAMETER)
    public static void onExit(@Advice.Return final Map<String, List<FileItem>> parameterMap) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        final TaintedObjects to = IastContext.Provider.taintedObjects();
        for (List<FileItem> fileItems : parameterMap.values()) {
          for (FileItem fileItem : fileItems) {
            module.taintObject(to, fileItem, SourceTypes.REQUEST_MULTIPART_PARAMETER);
          }
        }
      }
    }
  }

  public static class GetItemIteratorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_MULTIPART_PARAMETER)
    public static void onExit(@Advice.Return final FileItemIterator fileItemIterator) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        final TaintedObjects to = IastContext.Provider.taintedObjects();
        module.taintObject(to, fileItemIterator, SourceTypes.REQUEST_MULTIPART_PARAMETER);
      }
    }
  }
}
