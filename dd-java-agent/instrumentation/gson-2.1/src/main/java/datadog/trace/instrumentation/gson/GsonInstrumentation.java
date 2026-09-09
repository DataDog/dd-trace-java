package datadog.trace.instrumentation.gson;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public final class GsonInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public GsonInstrumentation() {
    super("gson");
  }

  @Override
  public String instrumentedType() {
    return "com.google.gson.Gson";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GsonDecorator",
      packageName + ".GsonToJsonAdvice",
      packageName + ".GsonFromJsonAdvice",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(named("toJson"), packageName + ".GsonToJsonAdvice");
    transformer.applyAdvice(named("fromJson"), packageName + ".GsonFromJsonAdvice");
  }
}
