package datadog.trace.instrumentation.apachehttpasyncclient;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class ApacheHttpAsyncClientModule extends InstrumenterModule.Tracing {
  public ApacheHttpAsyncClientModule() {
    super("httpasyncclient", "apache-httpasyncclient");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".HttpHeadersInjectAdapter",
      packageName + ".DelegatingRequestProducer",
      packageName + ".TraceContinuedFutureCallback",
      packageName + ".ApacheHttpAsyncClientDecorator",
      packageName + ".HostAndRequestAsHttpUriRequest"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "org.apache.http.concurrent.BasicFuture", "org.apache.http.concurrent.FutureCallback");
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new ApacheHttpAsyncClientInstrumentation(),
        new ApacheHttpClientRedirectInstrumentation(),
        new BasicFutureInstrumentation());
  }
}
