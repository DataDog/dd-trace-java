package datadog.trace.test.junit.utils.converter;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;

import java.util.HashMap;
import java.util.Map;

public class PrioritySamplingConverter extends AbstractClassConstantConvertor<Byte> {
  private static final Map<String, Byte> MAPPING;

  static {
    MAPPING = new HashMap<>();
    MAPPING.put("UNSET", UNSET);
    MAPPING.put("SAMPLER_KEEP", SAMPLER_KEEP);
    MAPPING.put("SAMPLER_DROP", SAMPLER_DROP);
    MAPPING.put("USER_DROP", USER_DROP);
    MAPPING.put("USER_KEEP", USER_KEEP);
  }

  @Override
  protected String className() {
    return "PrioritySampling";
  }

  @Override
  protected Map<String, Byte> mapping() {
    return MAPPING;
  }
}
