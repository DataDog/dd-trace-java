package datadog.trace.core;

import com.timgroup.statsd.StatsDClient;
import dagger.Component;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.sampling.SamplerModule;
import datadog.trace.common.writer.Writer;
import datadog.trace.common.writer.WriterModule;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.core.jfr.ScopeEventFactoryModule;
import datadog.trace.core.propagation.HttpCodec;
import datadog.trace.core.propagation.PropagationModule;
import datadog.trace.core.scopemanager.ScopeManagerModule;
import java.util.Map;
import javax.inject.Named;
import javax.inject.Singleton;

/** This class simulates all the things that CoreTracer handles internally. */
@Singleton
@Component(
    modules = {
      StatsDModule.class,
      ConfigModule.class,
      ScopeManagerModule.class,
      ScopeEventFactoryModule.class,
      PropagationModule.class,
      SamplerModule.class,
      WriterModule.class
    })
interface CoreComponent {

  @Named("serviceName")
  String serviceName();

  @Named("localRootSpanTags")
  Map<String, String> localRootSpanTags();

  @Named("defaultSpanTags")
  Map<String, String> defaultSpanTags();

  @Named("serviceNameMappings")
  Map<String, String> serviceNameMappings();

  @Named("taggedHeaders")
  Map<String, String> taggedHeaders();

  @Named("partialFlushMinSpans")
  int partialFlushMinSpans();

  Config config();

  AgentScopeManager scopeManager();

  HttpCodec.Injector injector();

  HttpCodec.Extractor extractor();

  Sampler sampler();

  StatsDClient statsD();

  Writer writer();

  DDAgentApi api();

  @Component.Builder
  interface Builder {
    Builder config(ConfigModule config);

    Builder sampler(SamplerModule sampler);

    Builder statsD(StatsDModule statsD);

    Builder scopeManager(ScopeManagerModule scopeManager);

    Builder propagation(PropagationModule propagation);

    Builder writer(WriterModule writer);

    CoreComponent build();
  }
}
