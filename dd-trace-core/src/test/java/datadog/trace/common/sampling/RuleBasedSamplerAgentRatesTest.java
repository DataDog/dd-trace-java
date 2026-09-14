package datadog.trace.common.sampling;

import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_RULES;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.api.Config;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.RemoteResponseListener;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Agent published sampling rates must still apply to spans that match no sampling rule.
 *
 * <p>Only one sampler is registered to receive those rates, so a rule based sampler has to forward
 * them to the fallback it delegates to.
 */
class RuleBasedSamplerAgentRatesTest extends DDCoreJavaSpecification {

  private static final String RULE_MISSING_SERVICE =
      "[{\"service\": \"other-service\", \"sample_rate\": 1}]";

  @Test
  void ruleBasedSamplerForwardsAgentRatesToFallback() {
    Properties properties = new Properties();
    properties.setProperty(TRACE_SAMPLING_RULES, RULE_MISSING_SERVICE);
    Sampler sampler = Sampler.Builder.forConfig(properties);
    assertInstanceOf(RuleBasedTraceSampler.class, sampler);

    // The agent asks for everything to be dropped for this service.
    assertInstanceOf(RemoteResponseListener.class, sampler);
    ((RemoteResponseListener) sampler).onResponse("traces", rateByService("service", "bar", 0.0));

    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = buildSpan(tracer, "service");
      ((PrioritySampler) sampler).setSamplingPriority(span);

      assertEquals(SAMPLER_DROP, (int) span.getSamplingPriority());
      assertEquals(0.0, span.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
    } finally {
      tracer.close();
    }
  }

  @Test
  void agentRatesDoNotOverrideMatchedRules() {
    Properties properties = new Properties();
    properties.setProperty(
        TRACE_SAMPLING_RULES, "[{\"service\": \"service\", \"sample_rate\": 1}]");
    Sampler sampler = Sampler.Builder.forConfig(properties);

    ((RemoteResponseListener) sampler).onResponse("traces", rateByService("service", "bar", 0.0));

    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = buildSpan(tracer, "service");
      ((PrioritySampler) sampler).setSamplingPriority(span);

      assertEquals(USER_KEEP, (int) span.getSamplingPriority());
      assertEquals(1.0, span.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE));
      assertNull(span.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
    } finally {
      tracer.close();
    }
  }

  @Test
  void defaultRateKeepsAgentRatesUnused() {
    Properties properties = new Properties();
    properties.setProperty(TRACE_SAMPLING_RULES, RULE_MISSING_SERVICE);
    // A default rate matches every span, so the fallback is never reached.
    properties.setProperty(TRACE_SAMPLE_RATE, "1");
    Sampler sampler = Sampler.Builder.forConfig(properties);

    ((RemoteResponseListener) sampler).onResponse("traces", rateByService("service", "bar", 0.0));

    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = buildSpan(tracer, "service");
      ((PrioritySampler) sampler).setSamplingPriority(span);

      assertEquals(USER_KEEP, (int) span.getSamplingPriority());
      assertNull(span.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
    } finally {
      tracer.close();
    }
  }

  @Test
  void rulesKeepTheSuppliedAgentSampler() {
    RateByServiceTraceSampler agentSampler = new RateByServiceTraceSampler();
    Properties properties = new Properties();
    properties.setProperty(TRACE_SAMPLING_RULES, RULE_MISSING_SERVICE);

    Sampler sampler = Sampler.Builder.forConfig(Config.get(properties), null, agentSampler);

    assertInstanceOf(RuleBasedTraceSampler.class, sampler);
    assertSame(agentSampler, ((RuleBasedTraceSampler<?>) sampler).agentSampler());
  }

  @Test
  void noRulesReturnsTheSuppliedAgentSampler() {
    RateByServiceTraceSampler agentSampler = new RateByServiceTraceSampler();

    Sampler sampler = Sampler.Builder.forConfig(Config.get(new Properties()), null, agentSampler);

    assertSame(agentSampler, sampler);
  }

  @Test
  void ratesLearnedBeforeARebuildStillApplyAfterIt() {
    RateByServiceTraceSampler agentSampler = new RateByServiceTraceSampler();
    agentSampler.onResponse("traces", rateByService("service", "bar", 0.0));

    // Rebuilding the sampler with the same agent sampler keeps the rates it has already learned.
    Properties properties = new Properties();
    properties.setProperty(TRACE_SAMPLING_RULES, RULE_MISSING_SERVICE);
    Sampler sampler = Sampler.Builder.forConfig(Config.get(properties), null, agentSampler);

    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = buildSpan(tracer, "service");
      ((PrioritySampler) sampler).setSamplingPriority(span);

      assertEquals(SAMPLER_DROP, (int) span.getSamplingPriority());
    } finally {
      tracer.close();
    }
  }

  @Test
  void unknownServiceIsKeptUntilTheAgentReportsARate() {
    Properties properties = new Properties();
    properties.setProperty(TRACE_SAMPLING_RULES, RULE_MISSING_SERVICE);
    Sampler sampler = Sampler.Builder.forConfig(properties);

    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = buildSpan(tracer, "service");
      ((PrioritySampler) sampler).setSamplingPriority(span);

      assertEquals(SAMPLER_KEEP, (int) span.getSamplingPriority());
      assertEquals(1.0, span.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
    } finally {
      tracer.close();
    }
  }

  private static DDSpan buildSpan(CoreTracer tracer, String serviceName) {
    return (DDSpan)
        tracer
            .buildSpan("datadog", "operation")
            .withServiceName(serviceName)
            .withTag("env", "bar")
            .ignoreActiveSpan()
            .start();
  }

  private static Map<String, Map<String, Number>> rateByService(
      String service, String env, double rate) {
    Map<String, Number> byService = new HashMap<>();
    byService.put("service:" + service + ",env:" + env, rate);
    Map<String, Map<String, Number>> response = new HashMap<>();
    response.put("rate_by_service", byService);
    return response;
  }
}
