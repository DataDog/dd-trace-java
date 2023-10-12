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
import datadog.trace.api.iast.source.WebModule;
import java.util.Collection;
import java.util.Collections;
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
      final WebModule module = InstrumentationBridge.WEB;
      if (module != null) {
        module.onMultipartValues("Content-Disposition", Collections.singleton(name));
      }
      return name;
    }
  }

  public static class GetHeaderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_MULTIPART_PARAMETER)
    public static String onExit(
        @Advice.Return final String value, @Advice.Argument(0) final String name) {
      final WebModule module = InstrumentationBridge.WEB;
      if (module != null) {
        module.onMultipartValues(name, Collections.singleton(value));
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
      if (null == headerValues) {
        return;
      }
      final WebModule module = InstrumentationBridge.WEB;
      if (module != null) {
        module.onMultipartValues(headerName, headerValues);
      }
    }
  }

  public static class GetHeaderNamesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_MULTIPART_PARAMETER)
    public static void onExit(@Advice.Return final Collection<String> headerNames) {
      if (null == headerNames) {
        return;
      }
      final WebModule module = InstrumentationBridge.WEB;
      if (module != null) {
        module.onMultipartNames(headerNames);
      }
    }
  }
}
