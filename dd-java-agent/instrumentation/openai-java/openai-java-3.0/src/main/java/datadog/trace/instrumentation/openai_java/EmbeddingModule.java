package datadog.trace.instrumentation.openai_java;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Collections;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class EmbeddingModule extends InstrumenterModule.Tracing {
    public EmbeddingModule() {
        super("openai-java");
    }

    @Override
    public List<Instrumenter> typeInstrumentations() {
        return Collections.singletonList(new EmbeddingServiceInstrumentation());
    }
}
