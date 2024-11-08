package datadog.trace.instrumentation.apachehttpcore5;

import static net.bytebuddy.matcher.ElementMatchers.any;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public class HttpRequestInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType {

  public HttpRequestInstrumentation() {
    super("testApache");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.hc.core5.http.message.BasicHttpRequest";
  }

  //  @Override
  //  public String[] getClassNamesToBePreloaded() {
  //    try {
  //      ClassLoader cl = ClassLoader.getSystemClassLoader();
  //      Object loadedClass = cl.loadClass(instrumentedType());
  //      System.out.println(loadedClass != null);
  //    } catch (Throwable e) {
  //      System.out.println(e);
  //    }
  //    return new String[] {instrumentedType()};
  //  }

  //  @Override
  //  public void typeAdvice(TypeTransformer transformer) {
  //    transformer.applyAdvice(new TaintableVisitor(instrumentedType()));
  //  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CtorAdvice",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    //    transformer.applyAdvice(
    //        isConstructor().and(takesArguments(String.class, String.class)),
    //        HttpRequestInstrumentation.class.getName() + "$CtorAdvice");
    //    transformer.applyAdvice(
    //        isConstructor().and(takesArguments(String.class, URI.class)),
    //        HttpRequestInstrumentation.class.getName() + "$CtorAdvice");
    //    transformer.applyAdvice(
    //        isConstructor().and(takesArguments(Method.class, String.class)),
    //        HttpRequestInstrumentation.class.getName() + "$CtorAdvice");
    //    transformer.applyAdvice(
    //        isConstructor().and(takesArguments(Method.class, URI.class)),
    //        HttpRequestInstrumentation.class.getName() + "$CtorAdvice");

    transformer.applyAdvice(
        //        isConstructor().and(takesArguments(2)).and(takesArgument(1, String.class)),
        any(), packageName + ".CtorAdvice");
    //    transformer.applyAdvice(
    //        isConstructor().and(takesArguments(2)).and(takesArgument(1, URI.class)),
    //        CtorAdvice.class.getName());

    //    transformer.applyAdvice(
    //        isConstructor().and(takesArguments(3)).and(takesArgument(1,
    // named("org.apache.hc.core5.http.HttpHost"))),
    //        CtorAdviceHost.class.getName());

    //    transformer.applyAdvice(
    //        isConstructor().and(takesArguments(String.class, HttpHost.class, String.class)),
    //        HttpRequestInstrumentation.class.getName() + "$CtorAdviceHost");
    //    transformer.applyAdvice(
    //        isConstructor().and(takesArguments(Method.class, HttpHost.class, String.class)),
    //        HttpRequestInstrumentation.class.getName() + "$CtorAdviceHost");
  }

  //  public static class CtorAdvice {
  //    @Advice.OnMethodExit()
  //    @Propagation
  //    public static void afterCtor(@Advice.This final Object self, @Advice.Argument(1) Object
  // argument) {
  //      final PropagationModule module = InstrumentationBridge.PROPAGATION;
  //      if (module != null) {
  //        module.taintObjectIfTainted(self, argument);
  //      }
  //    }
  //  }

  //  public static class CtorAdviceHost {
  //    @Advice.OnMethodExit()
  //    @Propagation
  //    public static void afterCtor(@Advice.This final Object self, @Advice.Argument(2) String
  // path) {
  //      final PropagationModule module = InstrumentationBridge.PROPAGATION;
  //      if (module != null) {
  //        module.taintObjectIfTainted(self, path);
  //      }
  //    }
  //  }

  //  @Override
  //  public String hierarchyMarkerType() {
  //    return "org.apache.hc.core5.http.message.BasicHttpRequest";
  //  }
  //
  //  @Override
  //  public ElementMatcher<TypeDescription> hierarchyMatcher() {
  //    return extendsClass(named(hierarchyMarkerType()));
  //  }
  //
  //  @Override
  //  public void methodAdvice(MethodTransformer transformer) {
  //    transformer.applyAdvice(
  //        named("getUri").and(isMethod()).and(takesArguments(0)),
  //        HttpRequestInstrumentation.class.getName() + "$GetUriAdvice");
  //    transformer.applyAdvice(
  //        named("setUri").and(isMethod()).and(takesArguments(URI.class)),
  //        HttpRequestInstrumentation.class.getName() + "$SetUriAdvice");
  //  }
  //
  //  public static class SetUriAdvice {
  //    @Advice.OnMethodExit()
  //    @Propagation
  //    public static void methodExit(@Advice.This HttpRequest self, @Advice.Argument(0) final
  // String path) {
  //      final PropagationModule propagationModule = InstrumentationBridge.PROPAGATION;
  //      if (propagationModule != null) {
  //        propagationModule.taintObjectIfTainted(self, path);
  //      }
  //    }
  //  }
  //
  //  public static class GetUriAdvice {
  //    @Advice.OnMethodExit()
  //    @Propagation
  //    public static void methodExit(@Advice.This HttpRequest self, @Advice.Return URI result) {
  //      final PropagationModule propagationModule = InstrumentationBridge.PROPAGATION;
  //      if (propagationModule != null) {
  //        propagationModule.taintObjectIfTainted(result, self);
  //      }
  //    }
  //  }
}
