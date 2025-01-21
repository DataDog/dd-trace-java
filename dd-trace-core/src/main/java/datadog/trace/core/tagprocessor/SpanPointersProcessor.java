package datadog.trace.core.tagprocessor;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import datadog.trace.core.DDSpanContext;
import datadog.trace.util.Strings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpanPointersProcessor implements TagsPostProcessor {
  private static final Logger log = LoggerFactory.getLogger(SpanPointersProcessor.class);

  // The pointer direction will always be down. The serverless agent handles cases where the
  // direction is up.
  private static final String DOWN_DIRECTION = "d";
  private static final String S3_PTR_KIND = "aws.s3.object";
  private static final String LINK_KIND = "span-pointer";
  private static final String ETAG_KEY = "s3.eTag";

  @Override
  public Map<String, Object> processTags(
      Map<String, Object> unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
    String eTag = asString(unsafeTags.remove(ETAG_KEY));
    if (eTag == null) {
      return unsafeTags;
    }
    String bucket = asString(unsafeTags.get(InstrumentationTags.AWS_BUCKET_NAME));
    String key = asString(unsafeTags.get(InstrumentationTags.AWS_OBJECT_KEY));
    if (bucket == null || key == null) {
      log.debug("Unable to calculate span pointer hash because could not find bucket or key tags.");
      return unsafeTags;
    }

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

      AgentTracer.NoopContext zeroContext = AgentTracer.NoopContext.INSTANCE;
      AgentSpanLink link = SpanLink.from(zeroContext, AgentSpanLink.DEFAULT_FLAGS, "", attributes);
      spanLinks.add(link);
    } catch (Exception e) {
      log.debug("Failed to add span pointer: {}", e.getMessage());
    }

    return unsafeTags;
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
