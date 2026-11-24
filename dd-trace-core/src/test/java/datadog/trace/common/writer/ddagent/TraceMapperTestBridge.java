package datadog.trace.common.writer.ddagent;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Bridge class to allow tests to access package-private method exposed by the {@code TraceMapper}
 */
public class TraceMapperTestBridge {
  /** The dictionary's completed entries as one buffer. */
  public static ByteBuffer getDictionaryBytes(TraceMapperV0_5 traceMapperV05) {
    return traceMapperV05.dictionaryBytes();
  }

  public static Map<Object, Integer> getEncoding(TraceMapperV0_5 traceMapperV05) {
    return traceMapperV05.getEncoding();
  }
}
