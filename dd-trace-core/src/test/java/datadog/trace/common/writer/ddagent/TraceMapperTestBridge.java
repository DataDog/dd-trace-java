package datadog.trace.common.writer.ddagent;

import datadog.communication.serialization.GrowableBuffer;
import java.util.Map;

/**
 * Bridge class to allow tests to access package-private method exposed by the {@code TraceMapper}
 */
public class TraceMapperTestBridge {
  public static GrowableBuffer getDictionary(TraceMapperV0_5 traceMapperV05) {
    return traceMapperV05.getDictionary();
  }

  public static Map<Object, Integer> getEncoding(TraceMapperV0_5 traceMapperV05) {
    return traceMapperV05.getEncoding();
  }
}
