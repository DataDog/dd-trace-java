package datadog.trace.instrumentation.azure.cosmos;

import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.JavaModuleOpenProvider;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Traces every request the Azure Cosmos DB SDK (v4) hands to one of its store models, in both
 * gateway (HTTP) and direct (RNTBD/TCP) connection modes.
 */
@AutoService(InstrumenterModule.class)
public final class AzureCosmosModule extends InstrumenterModule.Tracing
    implements JavaModuleOpenProvider {

  public AzureCosmosModule() {
    super("azure-cosmos");
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(new DocumentClientInstrumentation(), new StoreModelInstrumentation());
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "com.azure.cosmos.implementation.RxStoreModel", packageName + ".CosmosClientInfo");
  }

  @Override
  public Collection<String> triggerClasses() {
    // the helpers call into com.azure.cosmos.implementation, which is only exported to a few
    // named modules when the SDK runs on the module path
    return singleton("com.azure.cosmos.implementation.RxDocumentClientImpl");
  }
}
