// package datadog.trace.instrumentation.ddtrace;
//
// import static
// datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
// import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
//
// import com.google.auto.service.AutoService;
// import datadog.trace.agent.tooling.Instrumenter;
// import net.bytebuddy.description.type.TypeDescription;
// import net.bytebuddy.matcher.ElementMatcher;
//
// @AutoService(Instrumenter.class)
// public class SpanInstrumentation extends Instrumenter.Tracing
//    implements Instrumenter.ForTypeHierarchy {
//
//  public SpanInstrumentation(String instrumentationName) {
//    super("dd-trace");
//  }
//
//  @Override
//  public String hierarchyMarkerType() {
//  }
//
//  @Override
//  public ElementMatcher<TypeDescription> hierarchyMatcher() {
//
//  }
//
//  @Override
//  public void adviceTransformations(AdviceTransformation transformation) {}
// }
