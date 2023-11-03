package datadog.trace.instrumentation.servlet5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.Collection;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class JakartaMultipartInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForTypeHierarchy {

  public JakartaMultipartInstrumentation() {
    super("servlet", "servlet-5", "multipart");
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.servlet.http.Part";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("getName").and(isPublic()).and(takesArguments(0)),
        getClass().getName() + "$GetNameAdvice");
    transformation.applyAdvice(
        named("getHeader").and(isPublic()).and(takesArguments(String.class)),
        getClass().getName() + "$GetHeaderAdvice");
    transformation.applyAdvice(
        named("getHeaders").and(isPublic()).and(takesArguments(String.class)),
        getClass().getName() + "$GetHeadersAdvice");
    transformation.applyAdvice(
        named("getHeaderNames").and(isPublic()).and(takesArguments(0)),
        getClass().getName() + "$GetHeaderNamesAdvice");
  }

  public static class GetNameAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_MULTIPART_PARAMETER)
    public static String onExit(@Advice.Return final String name) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taint(name, SourceTypes.REQUEST_MULTIPART_PARAMETER, "Content-Disposition");
      }
      return name;
    }
  }

  public static class GetHeaderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_MULTIPART_PARAMETER)
    public static String onExit(
        @Advice.Return final String value, @Advice.Argument(0) final String name) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taint(value, SourceTypes.REQUEST_MULTIPART_PARAMETER, name);
      }
      return value;
    }
  }

  public static class GetHeadersAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_MULTIPART_PARAMETER)
    public static void onExit(
        @Advice.Argument(0) final String headerName,
        @Advice.Return Collection<String> headerValues) {
      if (null == headerValues || headerValues.isEmpty()) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        for (final String value : headerValues) {
          module.taint(value, SourceTypes.REQUEST_MULTIPART_PARAMETER, headerName);
        }
      }
    }
  }

  public static class GetHeaderNamesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_MULTIPART_PARAMETER)
    public static void onExit(@Advice.Return final Collection<String> headerNames) {
      if (null == headerNames || headerNames.isEmpty()) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        for (final String name : headerNames) {
          module.taint(name, SourceTypes.REQUEST_MULTIPART_PARAMETER);
        }
      }
    }
  }
}
