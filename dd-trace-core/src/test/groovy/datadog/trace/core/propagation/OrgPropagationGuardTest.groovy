package datadog.trace.core.propagation

import datadog.trace.api.Config
import datadog.trace.api.DynamicConfig
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.test.util.DDSpecification

import static datadog.trace.core.propagation.DatadogHttpCodec.OPM_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.ORIGIN_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.SAMPLING_PRIORITY_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.SPAN_ID_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.TRACE_ID_KEY

/**
 * Tests for the Org Propagation Guard (OPG) feature.
 *
 * The guard detects cross-org trace stitching by comparing the inbound OPM (received via
 * x-dd-opm header) with the local OPM (received from the agent /info endpoint).
 * When a mismatch is detected, sampling priority, origin and propagation tags are cleared
 * while trace ID and parent span ID are preserved for trace continuity.
 */
class OrgPropagationGuardTest extends DDSpecification {

  private static final String LOCAL_OPM  = "local-org-abc123"
  private static final String REMOTE_OPM = "remote-org-xyz789"
  private static final String TRACE_ID   = "1234567890"
  private static final String SPAN_ID    = "9876543210"

  private DynamicConfig dynamicConfig
  private HttpCodec.Extractor extractor

  void setup() {
    dynamicConfig = DynamicConfig.create().apply()
    OrgPropagationMarker.setLocalOpm(null)
  }

  void cleanup() {
    OrgPropagationMarker.setLocalOpm(null)
    extractor?.cleanup()
  }

  private HttpCodec.Extractor createExtractor(Config config) {
    DatadogHttpCodec.newExtractor(config, { dynamicConfig.captureTraceConfig() })
  }

  // -------------------------------------------------------------------------
  // No enforcement when config disabled
  // -------------------------------------------------------------------------

  def "guard disabled: cross-org OPM is NOT cleared when enforce=false"() {
    setup:
    injectSysConfig("trace.org.guard.enforce", "false")
    OrgPropagationMarker.setLocalOpm(LOCAL_OPM)
    extractor = createExtractor(Config.get())
    def headers = [
      (TRACE_ID_KEY)          : TRACE_ID,
      (SPAN_ID_KEY)           : SPAN_ID,
      (SAMPLING_PRIORITY_KEY) : "1",
      (ORIGIN_KEY)            : "synthetics",
      (OPM_KEY)               : REMOTE_OPM,
    ]

    when:
    ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then: "sampling priority is preserved because guard is disabled"
    context != null
    context.samplingPriority == PrioritySampling.SAMPLER_KEEP
    context.origin == "synthetics"
  }

  // -------------------------------------------------------------------------
  // No action when local OPM is not known
  // -------------------------------------------------------------------------

  def "guard enabled but local OPM unknown: inbound context is passed through unchanged"() {
    setup:
    injectSysConfig("trace.org.guard.enforce", "true")
    // OrgPropagationMarker.localOpm is null (not set)
    extractor = createExtractor(Config.get())
    def headers = [
      (TRACE_ID_KEY)          : TRACE_ID,
      (SPAN_ID_KEY)           : SPAN_ID,
      (SAMPLING_PRIORITY_KEY) : "1",
      (ORIGIN_KEY)            : "synthetics",
      (OPM_KEY)               : REMOTE_OPM,
    ]

    when:
    ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then: "context is unchanged when local OPM is unknown"
    context != null
    context.samplingPriority == PrioritySampling.SAMPLER_KEEP
    context.origin == "synthetics"
  }

  // -------------------------------------------------------------------------
  // No action when inbound has no OPM
  // -------------------------------------------------------------------------

  def "guard enabled, local OPM known, but no inbound OPM: context passed through"() {
    setup:
    injectSysConfig("trace.org.guard.enforce", "true")
    OrgPropagationMarker.setLocalOpm(LOCAL_OPM)
    extractor = createExtractor(Config.get())
    def headers = [
      (TRACE_ID_KEY)          : TRACE_ID,
      (SPAN_ID_KEY)           : SPAN_ID,
      (SAMPLING_PRIORITY_KEY) : "1",
      (ORIGIN_KEY)            : "synthetics",
      // no OPM_KEY header
    ]

    when:
    ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then: "context is unchanged when inbound has no OPM"
    context != null
    context.samplingPriority == PrioritySampling.SAMPLER_KEEP
    context.origin == "synthetics"
  }

  // -------------------------------------------------------------------------
  // Mismatch: clear sampling + tags, keep traceId + spanId
  // -------------------------------------------------------------------------

  def "cross-org OPM mismatch clears sampling priority, origin, but preserves trace and span IDs"() {
    setup:
    injectSysConfig("trace.org.guard.enforce", "true")
    OrgPropagationMarker.setLocalOpm(LOCAL_OPM)
    extractor = createExtractor(Config.get())
    def headers = [
      (TRACE_ID_KEY)          : TRACE_ID,
      (SPAN_ID_KEY)           : SPAN_ID,
      (SAMPLING_PRIORITY_KEY) : "1",
      (ORIGIN_KEY)            : "synthetics",
      (OPM_KEY)               : REMOTE_OPM,
    ]

    when:
    ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then: "trace and span IDs preserved"
    context != null
    context.traceId.toString() == TRACE_ID
    context.spanId.toString() == SPAN_ID

    and: "sampling priority and origin cleared"
    context.samplingPriority == PrioritySampling.UNSET
    context.origin == null
  }

  // -------------------------------------------------------------------------
  // Same-org: pass through normally
  // -------------------------------------------------------------------------

  def "same-org OPM match: context passed through unchanged"() {
    setup:
    injectSysConfig("trace.org.guard.enforce", "true")
    OrgPropagationMarker.setLocalOpm(LOCAL_OPM)
    extractor = createExtractor(Config.get())
    def headers = [
      (TRACE_ID_KEY)          : TRACE_ID,
      (SPAN_ID_KEY)           : SPAN_ID,
      (SAMPLING_PRIORITY_KEY) : "1",
      (ORIGIN_KEY)            : "synthetics",
      (OPM_KEY)               : LOCAL_OPM,   // same as local → trusted
    ]

    when:
    ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then: "all fields preserved for same-org request"
    context != null
    context.samplingPriority == PrioritySampling.SAMPLER_KEEP
    context.origin == "synthetics"
  }

  // -------------------------------------------------------------------------
  // Trusted list: allow listed foreign OPM
  // -------------------------------------------------------------------------

  def "trusted OPM in allowed list: context passed through unchanged"() {
    setup:
    injectSysConfig("trace.org.guard.enforce", "true")
    injectSysConfig("trace.org.guard.trusted.opm", REMOTE_OPM)
    OrgPropagationMarker.setLocalOpm(LOCAL_OPM)
    extractor = createExtractor(Config.get())
    def headers = [
      (TRACE_ID_KEY)          : TRACE_ID,
      (SPAN_ID_KEY)           : SPAN_ID,
      (SAMPLING_PRIORITY_KEY) : "1",
      (ORIGIN_KEY)            : "synthetics",
      (OPM_KEY)               : REMOTE_OPM,  // different but in trusted list
    ]

    when:
    ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then: "context preserved because OPM is in trusted list"
    context != null
    context.samplingPriority == PrioritySampling.SAMPLER_KEEP
    context.origin == "synthetics"
  }
}
