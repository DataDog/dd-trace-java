package datadog.trace.instrumentation.commons.fileupload;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class CommonsFileuploadInstrumenter extends Instrumenter.Iast
    implements Instrumenter.ForKnownTypes {

  public CommonsFileuploadInstrumenter() {
    super("commons-fileupload");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("parse"))
            .and(isPublic())
            .and(returns(Map.class))
            .and(takesArguments(char[].class, int.class, int.class, char.class)),
        getClass().getName() + "$ParseAdvice");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.commons.fileupload.ParameterParser",
      "org.apache.tomcat.util.http.fileupload.ParameterParser"
    };
  }

  public static class ParseAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_MULTIPART_PARAMETER)
    public static Map<String, String> onExit(@Advice.Return final Map<String, String> map) {
      if (!map.isEmpty()) {
        final PropagationModule module = InstrumentationBridge.PROPAGATION;
        if (module != null) {
          final IastContext ctx = IastContext.Provider.get();
          for (final Map.Entry<String, String> entry : map.entrySet()) {
            if (entry.getValue() != null) {
              module.taint(
                  ctx, entry.getValue(), SourceTypes.REQUEST_MULTIPART_PARAMETER, entry.getKey());
            }
          }
        }
      }
      return map;
    }
  }
}
