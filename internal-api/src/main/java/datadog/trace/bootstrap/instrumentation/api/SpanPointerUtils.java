package datadog.trace.bootstrap.instrumentation.api;

import static datadog.trace.bootstrap.instrumentation.api.AgentSpanLink.DEFAULT_FLAGS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpanContext;

import datadog.trace.util.Strings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility class for creating span pointer links for AWS resources (S3, DynamoDB, etc.). */
public class SpanPointerUtils {
  private static final Logger LOG = LoggerFactory.getLogger(SpanPointerUtils.class);

  // The pointer direction will always be down. The serverless agent handles cases where the
  // direction is up.
  public static final String DOWN_DIRECTION = "d";
  public static final String DYNAMODB_PTR_KIND = "aws.dynamodb.item";
  public static final String S3_PTR_KIND = "aws.s3.object";
  public static final String LINK_KIND = "span-pointer";

  /**
   * Generates a unique hash from an array of strings by joining them with | before hashing. Used to
   * uniquely identify AWS requests for span pointers.
   *
   * @param components Array of strings to hash
   * @return A 32-character hex hash uniquely identifying the components
   * @throws NoSuchAlgorithmException this should never happen; but should be handled just in case.
   */
  public static String generatePointerHash(String... components) throws NoSuchAlgorithmException {
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

  /**
   * Builds a span pointer link with the given hash and pointer kind.
   *
   * @param hash The 32-character hex hash identifying the resource
   * @param ptrKind The kind of pointer (e.g. "aws.s3.object", "aws.dynamodb.item")
   * @return An AgentSpanLink representing the span pointer
   */
  public static AgentSpanLink buildSpanPointer(String hash, String ptrKind) {
    SpanAttributes attributes =
        SpanAttributes.builder()
            .put("ptr.kind", ptrKind)
            .put("ptr.dir", DOWN_DIRECTION)
            .put("ptr.hash", hash)
            .put("link.kind", LINK_KIND)
            .build();

    return SpanLink.from(noopSpanContext(), DEFAULT_FLAGS, "", attributes);
  }

  /**
   * Creates and adds an S3 span pointer link to the given span.
   *
   * @param span The span to add the pointer link to
   * @param bucket The S3 bucket name
   * @param key The S3 object key
   * @param eTag The S3 object ETag (quotes will be stripped if present)
   */
  public static void addS3SpanPointer(AgentSpan span, String bucket, String key, String eTag) {
    if (bucket == null || key == null || eTag == null) {
      return;
    }
    // Strip surrounding quotes from eTag
    // https://github.com/DataDog/dd-span-pointer-rules/blob/main/AWS/S3/Object/README.md
    if (!eTag.isEmpty() && eTag.charAt(0) == '"' && eTag.charAt(eTag.length() - 1) == '"') {
      eTag = eTag.substring(1, eTag.length() - 1);
    }
    try {
      String hash = generatePointerHash(bucket, key, eTag);
      span.addLink(buildSpanPointer(hash, S3_PTR_KIND));
    } catch (Exception e) {
      LOG.debug("Failed to add S3 span pointer: {}", e.getMessage());
    }
  }

  /**
   * Creates and adds a DynamoDB span pointer link to the given span.
   *
   * @param span The span to add the pointer link to
   * @param tableName The DynamoDB table name
   * @param primaryKey1Name The partition key name
   * @param primaryKey1Value The partition key value
   * @param primaryKey2Name The sort key name (empty string if no sort key)
   * @param primaryKey2Value The sort key value (empty string if no sort key)
   */
  public static void addDynamoDbSpanPointer(
      AgentSpan span,
      String tableName,
      String primaryKey1Name,
      String primaryKey1Value,
      String primaryKey2Name,
      String primaryKey2Value) {
    if (tableName == null || primaryKey1Name == null || primaryKey1Value == null) {
      return;
    }
    if (primaryKey2Name == null) {
      primaryKey2Name = "";
    }
    if (primaryKey2Value == null) {
      primaryKey2Value = "";
    }
    // https://github.com/DataDog/dd-span-pointer-rules/blob/main/AWS/DynamoDB/Item/README.md
    try {
      String hash =
          generatePointerHash(
              tableName, primaryKey1Name, primaryKey1Value, primaryKey2Name, primaryKey2Value);
      span.addLink(buildSpanPointer(hash, DYNAMODB_PTR_KIND));
    } catch (Exception e) {
      LOG.debug("Failed to add DynamoDB span pointer: {}", e.getMessage());
    }
  }
}
