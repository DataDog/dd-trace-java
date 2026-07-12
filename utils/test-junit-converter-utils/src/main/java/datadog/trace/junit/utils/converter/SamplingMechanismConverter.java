package datadog.trace.junit.utils.converter;

import static datadog.trace.api.sampling.SamplingMechanism.AGENT_RATE;
import static datadog.trace.api.sampling.SamplingMechanism.AI_GUARD;
import static datadog.trace.api.sampling.SamplingMechanism.APPSEC;
import static datadog.trace.api.sampling.SamplingMechanism.DATA_JOBS;
import static datadog.trace.api.sampling.SamplingMechanism.DEFAULT;
import static datadog.trace.api.sampling.SamplingMechanism.EXTERNAL_OVERRIDE;
import static datadog.trace.api.sampling.SamplingMechanism.LOCAL_USER_RULE;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static datadog.trace.api.sampling.SamplingMechanism.REMOTE_ADAPTIVE_RULE;
import static datadog.trace.api.sampling.SamplingMechanism.REMOTE_AUTO_RATE;
import static datadog.trace.api.sampling.SamplingMechanism.REMOTE_USER_RATE;
import static datadog.trace.api.sampling.SamplingMechanism.REMOTE_USER_RULE;
import static datadog.trace.api.sampling.SamplingMechanism.SPAN_SAMPLING_RATE;
import static datadog.trace.api.sampling.SamplingMechanism.UNKNOWN;

import java.util.HashMap;
import java.util.Map;

public class SamplingMechanismConverter extends AbstractClassConstantConvertor<Byte> {
  private static final Map<String, Byte> MAPPING;

  static {
    MAPPING = new HashMap<>();
    MAPPING.put("UNKNOWN", UNKNOWN);
    MAPPING.put("DEFAULT", DEFAULT);
    MAPPING.put("AGENT_RATE", AGENT_RATE);
    MAPPING.put("REMOTE_AUTO_RATE", REMOTE_AUTO_RATE);
    MAPPING.put("LOCAL_USER_RULE", LOCAL_USER_RULE);
    MAPPING.put("MANUAL", MANUAL);
    MAPPING.put("APPSEC", APPSEC);
    MAPPING.put("REMOTE_USER_RATE", REMOTE_USER_RATE);
    MAPPING.put("SPAN_SAMPLING_RATE", SPAN_SAMPLING_RATE);
    MAPPING.put("DATA_JOBS", DATA_JOBS);
    MAPPING.put("REMOTE_USER_RULE", REMOTE_USER_RULE);
    MAPPING.put("REMOTE_ADAPTIVE_RULE", REMOTE_ADAPTIVE_RULE);
    MAPPING.put("AI_GUARD", AI_GUARD);
    MAPPING.put("EXTERNAL_OVERRIDE", EXTERNAL_OVERRIDE);
  }

  @Override
  protected String className() {
    return "SamplingMechanism";
  }

  @Override
  protected Map<String, Byte> mapping() {
    return MAPPING;
  }
}
