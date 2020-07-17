import datadog.trace.api.Config
import datadog.trace.common.sampling.RateByServiceSampler
import datadog.trace.common.writer.DDAgentWriter
import datadog.trace.core.ConfigModule
import datadog.trace.core.DaggerCoreComponent
import datadog.trace.core.propagation.HttpCodec
import datadog.trace.core.scopemanager.ContinuableScopeManager
import datadog.trace.util.test.DDSpecification

class DaggerTest extends DDSpecification {

  def "test defaults"() {
    when:
    def component = DaggerCoreComponent.builder()
      .config(config)
//      .sampler(new SamplerModule())
//      .statsD(new StatsDModule())
//      .scopeManager(new ScopeManagerModule())
//      .propagation(new PropagationModule())
//      .writer(new WriterModule())
      .build()

    then:
    component.serviceName() == serviceName
    component.scopeManager() instanceof ContinuableScopeManager
    component.sampler() instanceof RateByServiceSampler
    component.injector() instanceof HttpCodec.CompoundInjector
    component.extractor() instanceof HttpCodec.CompoundExtractor
    component.writer() instanceof DDAgentWriter

    where:
    config                                     | serviceName
    new ConfigModule()                         | Config.get().serviceName
    new ConfigModule(serviceName: "some name") | "some name"
  }
}
