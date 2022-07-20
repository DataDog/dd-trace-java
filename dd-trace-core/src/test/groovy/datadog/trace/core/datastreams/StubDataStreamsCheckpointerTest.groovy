package datadog.trace.core.datastreams

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.trace.api.WellKnownTags
import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.PathwayContext
import datadog.trace.bootstrap.instrumentation.api.StatsPoint
import datadog.trace.common.metrics.EventListener
import datadog.trace.common.metrics.Sink
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Requires
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeUnit

import static datadog.trace.core.datastreams.DefaultDataStreamsCheckpointer.DEFAULT_BUCKET_DURATION_NANOS
import static datadog.trace.core.datastreams.DefaultDataStreamsCheckpointer.FEATURE_CHECK_INTERVAL_NANOS
import static java.util.concurrent.TimeUnit.SECONDS

class StubDataStreamsCheckpointerTest extends DDCoreSpecification {
  def "extractPathway calls returns NoopPathwayContext"() {
    when:
    def checkpointer = new StubDataStreamsCheckpointer()

    then:
    checkpointer.extractPathwayContext(null, null) == AgentTracer.NoopPathwayContext.INSTANCE;
    checkpointer.extractBinaryPathwayContext(null, null) == AgentTracer.NoopPathwayContext.INSTANCE;
  }
}
