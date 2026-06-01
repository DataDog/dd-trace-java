package datadog.trace.junit.utils.tabletest;

import datadog.trace.api.sampling.PrioritySampling;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;

public class PrioritySamplingConverter implements ArgumentConverter {

  @Override
  public Object convert(Object source, ParameterContext context)
      throws ArgumentConversionException {
    if (source == null) {
      return null;
    }
    if (source.toString().startsWith("PrioritySampling.")) {
      switch (source.toString()) {
        case "PrioritySampling.UNSET":
          return PrioritySampling.UNSET;
        case "PrioritySampling.SAMPLER_KEEP":
          return PrioritySampling.SAMPLER_KEEP;
        case "PrioritySampling.SAMPLER_DROP":
          return PrioritySampling.SAMPLER_DROP;
        case "PrioritySampling.USER_DROP":
          return PrioritySampling.USER_DROP;
        case "PrioritySampling.USER_KEEP":
          return PrioritySampling.USER_KEEP;
        default:
          throw new ArgumentConversionException("Cannot convert " + source);
      }
    }
    return source;
  }
}
