package datadog.trace.instrumentation.grizzly;

import static java.util.Collections.singletonList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class GrizzlyModule extends InstrumenterModule.Tracing {
  public GrizzlyModule() {
    super("grizzly");
  }

  @Override
  public boolean defaultEnabled() {
    return false;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ExtractAdapter",
      packageName + ".ExtractAdapter$Request",
      packageName + ".ExtractAdapter$Response",
      packageName + ".GrizzlyDecorator",
      packageName + ".GrizzlyDecorator$GrizzlyBlockResponseFunction",
      packageName + ".RequestURIDataAdapter",
      packageName + ".SpanClosingListener",
      packageName + ".GrizzlyBlockingHelper",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return singletonList(new GrizzlyHttpHandlerInstrumentation());
  }
}
