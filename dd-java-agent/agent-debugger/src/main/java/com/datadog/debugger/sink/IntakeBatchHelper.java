package com.datadog.debugger.sink;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helper class for batching requests and handling size limit to the intake */
public class IntakeBatchHelper {

  private static final Logger log = LoggerFactory.getLogger(IntakeBatchHelper.class);

  private static final int MAX_PAYLOAD_SIZE = 5 * 1024 * 1024;

  private IntakeBatchHelper() {}

  public static List<byte[]> createBatches(List<String> payloads) {
    List<byte[]> batches = new ArrayList<>();
    int start = 0;
    int count = payloads.size();
    do {
      StringBuilder sb = new StringBuilder();
      int includedElements = buildPayloadBatch(payloads, start, count, sb);
      byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);
      if (includedElements == 0) {
        // skip the first message because too large
        start++;
        count--;
        continue;
      }
      // serialization into UTF-8 can result in a larger payload because of multi-byte character
      // (non-7bit-ascii), need to check again
      if (payload.length >= MAX_PAYLOAD_SIZE) {
        // Remove the last element and retry
        count = includedElements - 1;
        if (count == 0) {
          // only one snapshot was included and actually too large once converted to bytes
          logSkippedPayload(payloads.get(start));
        }
        continue;
      }
      batches.add(payload);
      // adjust start and count to handle the remaining snapshots into the batch
      start = start + includedElements;
      count = count - includedElements;
    } while (count > 0);
    return batches;
  }

  /**
   * Try to serialize as string as many payloads as possible up to the maxPayloadSize
   *
   * @param payloads list of elements that we try to include into the StringBuilder
   * @param start index of the first element into the payloads list that we want to include
   * @param count number of elements that we want ot include
   * @param sb StringBuilder that receives serialized elements
   * @return the number of included elements into the StringBuilder
   */
  private static int buildPayloadBatch(
      List<String> payloads, int start, int count, StringBuilder sb) {
    int totalSize = 0;
    int elementCount = 0;
    sb.append("[");
    for (int i = start; i < start + count; i++) {
      String jsonStr = payloads.get(i);
      totalSize += jsonStr.length();
      if (totalSize >= MAX_PAYLOAD_SIZE) {
        // the projected total size is too large, we stop here and do not append the
        // last serialized snapshot
        break;
      }
      sb.append(jsonStr);
      sb.append(",");
      elementCount++;
    }
    if (elementCount > 0) {
      sb.delete(sb.lastIndexOf(","), sb.length());
    }
    sb.append("]");
    return elementCount;
  }

  private static void logSkippedPayload(String payload) {
    log.warn(
        "Payload ({}mb) exceeding max payload size {}mb, skipping.",
        payload.getBytes(StandardCharsets.UTF_8).length / 1024 / 1024,
        MAX_PAYLOAD_SIZE / 1024 / 1024);
  }
}
