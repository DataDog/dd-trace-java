package datadog.trace.junit.utils.tabletest;

import datadog.trace.api.sampling.SamplingMechanism;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;

public class SamplingMechanismConverter implements ArgumentConverter {

  @Override
  public Object convert(Object source, ParameterContext context)
      throws ArgumentConversionException {
    if (source == null) {
      return null;
    }
    if (source.toString().startsWith("SamplingMechanism.")) {
      switch (source.toString()) {
        case "SamplingMechanism.UNKNOWN":
          return SamplingMechanism.UNKNOWN;
        case "SamplingMechanism.DEFAULT":
          return SamplingMechanism.DEFAULT;
        case "SamplingMechanism.AGENT_RATE":
          return SamplingMechanism.AGENT_RATE;
        case "SamplingMechanism.REMOTE_AUTO_RATE":
          return SamplingMechanism.REMOTE_AUTO_RATE;
        case "SamplingMechanism.LOCAL_USER_RULE":
          return SamplingMechanism.LOCAL_USER_RULE;
        case "SamplingMechanism.MANUAL":
          return SamplingMechanism.MANUAL;
        case "SamplingMechanism.APPSEC":
          return SamplingMechanism.APPSEC;
        case "SamplingMechanism.REMOTE_USER_RATE":
          return SamplingMechanism.REMOTE_USER_RATE;
        case "SamplingMechanism.SPAN_SAMPLING_RATE":
          return SamplingMechanism.SPAN_SAMPLING_RATE;
        case "SamplingMechanism.DATA_JOBS":
          return SamplingMechanism.DATA_JOBS;
        case "SamplingMechanism.REMOTE_USER_RULE":
          return SamplingMechanism.REMOTE_USER_RULE;
        case "SamplingMechanism.REMOTE_ADAPTIVE_RULE":
          return SamplingMechanism.REMOTE_ADAPTIVE_RULE;
        case "SamplingMechanism.AI_GUARD":
          return SamplingMechanism.AI_GUARD;
        case "SamplingMechanism.EXTERNAL_OVERRIDE":
          return SamplingMechanism.EXTERNAL_OVERRIDE;
        default:
          throw new ArgumentConversionException("Cannot convert " + source);
      }
    }
    return source;
  }
}
