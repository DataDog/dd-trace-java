package datadog.trace.core.processor.rule;

import com.google.common.io.BaseEncoding;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.core.DDSpan;
import datadog.trace.core.processor.TraceProcessor;

public class MethodLevelTracingDataRule implements TraceProcessor.Rule {
  private static final String TAG_PREFIX = InstrumentationTags.DD_MLT + ".";

  // https://github.com/DataDog/datadog-agent/blob/master/pkg/trace/agent/truncator.go
  private static final int TAG_LENGTH_LIMIT = 5000;

  // 4n / 3 bytes in -> base64 encoding where "n" is the byte array length
  // Flipping the equation, you get max bytes for a base64 length.
  private static final int BYTES_LIMIT_PER_TAG = TAG_LENGTH_LIMIT * 3 / 4;

  // Guava encoder because the built-in Base64 encoder wasn't added until JDK8
  private static final BaseEncoding ENCODER = BaseEncoding.base64();

  @Override
  public String[] aliases() {
    return new String[0];
  }

  @Override
  public void processSpan(final DDSpan span) {
    final Object mltDataObject = span.getAndRemoveTag(InstrumentationTags.DD_MLT);

    if (mltDataObject instanceof byte[]) {
      final byte[] mltData = (byte[]) mltDataObject;
      int tagIndex = 0;
      int dataOffset = 0;

      while (dataOffset < mltData.length) {
        final int dataLength = Math.min(mltData.length - dataOffset, BYTES_LIMIT_PER_TAG);

        final String tag = TAG_PREFIX + tagIndex;
        final String value = ENCODER.encode(mltData, dataOffset, dataLength);

        span.setTag(tag, value);

        tagIndex++;
        dataOffset += dataLength;
      }
    }
  }
}
