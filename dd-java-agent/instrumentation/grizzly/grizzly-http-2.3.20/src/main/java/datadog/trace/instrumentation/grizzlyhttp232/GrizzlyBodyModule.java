package datadog.trace.instrumentation.grizzlyhttp232;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class GrizzlyBodyModule extends InstrumenterModule.AppSec {
  public GrizzlyBodyModule() {
    super("grizzly");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {"datadog.trace.instrumentation.grizzlyhttp232.HttpHeaderFetchingHelper"};
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> ret = new HashMap<>();
    ret.put(
        "org.glassfish.grizzly.http.io.NIOInputStream", "datadog.trace.api.http.StoredByteBody");
    ret.put("org.glassfish.grizzly.http.io.NIOReader", "datadog.trace.api.http.StoredCharBody");
    return ret;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new GrizzlyByteBodyInstrumentation(), new GrizzlyCharBodyInstrumentation());
  }
}
