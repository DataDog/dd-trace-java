package datadog.trace.ci;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.StatsDClient;
import datadog.trace.ci.metrics.CIStatsDClientFactory;
import datadog.trace.ci.writer.CIWriterFactory;
import datadog.trace.common.sampling.ForcePrioritySampler;
import datadog.trace.common.sampling.PrioritySampling;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.writer.Writer;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;

/**
 * Tracer Factory for CI Visibility It creates the tracer with the correct configuration to be used
 * in the CI Visibility mode.
 */
public class CITracerFactory {

  public static CITracer createCITracer(
      final Config config, SharedCommunicationObjects commObjects) {
    final StatsDClient ciStatsDClient = CIStatsDClientFactory.createCiStatsDClient(config);
    final Writer ciAgentWriter =
        CIWriterFactory.createCIAgentWriter(config, commObjects, ciStatsDClient);
    final Sampler<DDSpan> ciSampler = new ForcePrioritySampler<>(PrioritySampling.SAMPLER_KEEP);

    final CoreTracer coreTracer =
        CoreTracer.builder()
            .sharedCommunicationObjects(commObjects)
            .writer(ciAgentWriter)
            .statsDClient(ciStatsDClient)
            .sampler(ciSampler)
            .build();

    return new CITracer(coreTracer);
  }

  private CITracerFactory() {}
}
