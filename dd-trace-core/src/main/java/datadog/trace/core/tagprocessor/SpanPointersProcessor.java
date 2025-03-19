package datadog.trace.core.tagprocessor;

import static datadog.trace.bootstrap.instrumentation.api.AgentSpanLink.DEFAULT_FLAGS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AWS_BUCKET_NAME;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AWS_OBJECT_KEY;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.S3_ETAG;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import datadog.trace.core.DDSpanContext;
import datadog.trace.util.Strings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SpanPointersProcessor extends TagsPostProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(SpanPointersProcessor.class);

  // The pointer direction will always be down. The serverless agent handles cases where the
  // direction is up.
  static final String DOWN_DIRECTION = "d";
  static final String S3_PTR_KIND = "aws.s3.object";
  static final String LINK_KIND = "span-pointer";

  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
    TagMap.Entry eTagEntry = unsafeTags.removeEntry(S3_ETAG);
    if (eTagEntry == null) {
      return;
    }
    String bucket = unsafeTags.getString(AWS_BUCKET_NAME);
    String key = unsafeTags.getString(AWS_OBJECT_KEY);
    if (bucket == null || key == null) {
      LOG.debug("Unable to calculate span pointer hash because could not find bucket or key tags.");
      return;
    }

    String eTag = eTagEntry.stringValue();

    // Hash calculation rules:
    // https://github.com/DataDog/dd-span-pointer-rules/blob/main/AWS/S3/Object/README.md
    if (!eTag.isEmpty() && eTag.charAt(0) == '"' && eTag.charAt(eTag.length() - 1) == '"') {
      eTag = eTag.substring(1, eTag.length() - 1);
    }
    String[] components = new String[] {bucket, key, eTag};
    try {
      SpanAttributes attributes =
          SpanAttributes.builder()
              .put("ptr.kind", S3_PTR_KIND)
              .put("ptr.dir", DOWN_DIRECTION)
              .put("ptr.hash", generatePointerHash(components))
              .put("link.kind", LINK_KIND)
              .build();

      AgentSpanLink link = SpanLink.from(noopSpanContext(), DEFAULT_FLAGS, "", attributes);
      spanLinks.add(link);
    } catch (Exception e) {
      LOG.debug("Failed to add span pointer: {}", e.getMessage());
    }
  }

  private static String asString(Object o) {
    return o == null ? null : o.toString();
  }

  /**
   * Generates a unique hash from an array of strings by joining them with | before hashing. Used to
   * uniquely identify AWS requests for span pointers.
   *
   * @param components Array of strings to hash
   * @return A 32-character hash uniquely identifying the components
   * @throws NoSuchAlgorithmException this should never happen; but should be handled just in case.
   */
  private static String generatePointerHash(String[] components) throws NoSuchAlgorithmException {
    MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");

    // Update the digest incrementally for each component.
    boolean first = true;
    for (String component : components) {
      if (!first) {
        messageDigest.update((byte) '|');
      } else {
        first = false;
      }
      messageDigest.update(component.getBytes(StandardCharsets.UTF_8));
    }

    byte[] fullHash = messageDigest.digest();
    // Only take first 16 bytes of the hash and convert to hex
    byte[] truncatedHash = Arrays.copyOf(fullHash, 16);
    return Strings.toHexString(truncatedHash);
  }
}
