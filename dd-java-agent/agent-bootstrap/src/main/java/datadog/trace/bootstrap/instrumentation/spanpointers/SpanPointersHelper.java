package datadog.trace.bootstrap.instrumentation.spanpointers;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class SpanPointersHelper {
  public static final String S3_PTR_KIND = "aws.s3.object";
  public static final String LINK_KIND = "span-pointer";

  // The pointer direction will always be down. The agent handles cases where the direction is up.
  public static final String DOWN_DIRECTION = "d";

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
    StringBuilder hex = new StringBuilder(32);
    for (int i = 0; i < 16; i++) {
      hex.append(String.format("%02x", fullHash[i]));
    }

    return hex.toString();
  }

  /**
   * Adds a span pointer to the given span, using the SHA-256 hash of the components.
   *
   * @param span The span to add the pointer to
   * @param kind Identifies which hashing rules to follow
   * @param components Array of strings to hash, following span pointer rules
   * @throws NoSuchAlgorithmException if unable to calculate hash
   * @see <a href="https://github.com/DataDog/dd-span-pointer-rules/tree/main">Span pointer
   *     rules</a>
   */
  public static void addSpanPointer(AgentSpan span, String kind, String[] components)
      throws NoSuchAlgorithmException {
    SpanAttributes attributes =
        (SpanAttributes)
            SpanAttributes.builder()
                .put("ptr.kind", kind)
                .put("ptr.dir", DOWN_DIRECTION)
                .put("ptr.hash", generatePointerHash(components))
                .put("link.kind", LINK_KIND)
                .build();

    AgentTracer.NoopContext zeroContext = AgentTracer.NoopContext.INSTANCE;
    span.addLink(SpanLink.from(zeroContext, AgentSpanLink.DEFAULT_FLAGS, "", attributes));
  }
}
