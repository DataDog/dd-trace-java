package datadog.trace.core.tagprocessor;

import static datadog.trace.bootstrap.instrumentation.api.AgentSpanLink.DEFAULT_FLAGS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AWS_BUCKET_NAME;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AWS_OBJECT_KEY;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AWS_TABLE_NAME;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DYNAMO_PRIMARY_KEY_1;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DYNAMO_PRIMARY_KEY_1_VALUE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DYNAMO_PRIMARY_KEY_2;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DYNAMO_PRIMARY_KEY_2_VALUE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.S3_ETAG;

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
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpanPointersProcessor implements TagsPostProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(SpanPointersProcessor.class);

  // The pointer direction will always be down. The serverless agent handles cases where the
  // direction is up.
  public static final String DOWN_DIRECTION = "d";
  public static final String DYNAMODB_PTR_KIND = "aws.dynamodb.item";
  public static final String S3_PTR_KIND = "aws.s3.object";
  public static final String LINK_KIND = "span-pointer";

  @Override
  public Map<String, Object> processTags(
      Map<String, Object> unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
    // DQH - TODO - There's a lot room to optimize this using TagMap's capabilities
    AgentSpanLink s3Link = handleS3SpanPointer(unsafeTags);
    if (s3Link != null) {
      spanLinks.add(s3Link);
    }

    AgentSpanLink dynamoDbLink = handleDynamoDbSpanPointer(unsafeTags);
    if (dynamoDbLink != null) {
      spanLinks.add(dynamoDbLink);
    }
    return unsafeTags;
  }

  private static AgentSpanLink handleS3SpanPointer(Map<String, Object> unsafeTags) {
    String eTag = asString(unsafeTags.remove(S3_ETAG));
    if (eTag == null) {
      return null;
    }
    String bucket = asString(unsafeTags.get(AWS_BUCKET_NAME));
    String key = asString(unsafeTags.get(AWS_OBJECT_KEY));
    if (bucket == null || key == null) {
      // This might be from an S3 operation not supported by span pointers, so we skip without
      // logging anything.
      return null;
    }

    // Hash calculation rules:
    // https://github.com/DataDog/dd-span-pointer-rules/blob/main/AWS/S3/Object/README.md
    if (!eTag.isEmpty() && eTag.charAt(0) == '"' && eTag.charAt(eTag.length() - 1) == '"') {
      eTag = eTag.substring(1, eTag.length() - 1);
    }
    String[] components = new String[] {bucket, key, eTag};
    try {
      String hash = generatePointerHash(components);
      return buildSpanPointer(hash, S3_PTR_KIND);
    } catch (Exception e) {
      LOG.debug("Failed to add span pointer: {}", e.getMessage());
      return null;
    }
  }

  private static AgentSpanLink handleDynamoDbSpanPointer(Map<String, Object> unsafeTags) {
    // Hash calculation rules:
    // https://github.com/DataDog/dd-span-pointer-rules/blob/main/AWS/DynamoDB/Item/README.md
    String tableName = asString(unsafeTags.get(AWS_TABLE_NAME));
    if (tableName == null) {
      return null;
    }
    String primaryKey1Name = asString(unsafeTags.remove(DYNAMO_PRIMARY_KEY_1));
    String primaryKey1Value = asString(unsafeTags.remove(DYNAMO_PRIMARY_KEY_1_VALUE));
    if (primaryKey1Name == null || primaryKey1Value == null) {
      // This might be from a DynamoDB operation not supported by span pointers, so we skip without
      // logging anything.
      return null;
    }

    // If these don't exist, the user has a table with only partition key but no sort key.
    // Then, we set them to empty strings when calculating the hash.
    String primaryKey2Name = asString(unsafeTags.remove(DYNAMO_PRIMARY_KEY_2));
    String primaryKey2Value = asString(unsafeTags.remove(DYNAMO_PRIMARY_KEY_2_VALUE));
    if (primaryKey2Name == null) {
      primaryKey2Name = "";
    }
    if (primaryKey2Value == null) {
      primaryKey2Value = "";
    }

    String[] components =
        new String[] {
          tableName, primaryKey1Name, primaryKey1Value, primaryKey2Name, primaryKey2Value
        };
    try {
      String hash = generatePointerHash(components);
      return buildSpanPointer(hash, DYNAMODB_PTR_KIND);
    } catch (Exception e) {
      LOG.debug("Failed to add span pointer: {}", e.getMessage());
      return null;
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

  private static AgentSpanLink buildSpanPointer(String hash, String ptrKind) {
    SpanAttributes attributes =
        SpanAttributes.builder()
            .put("ptr.kind", ptrKind)
            .put("ptr.dir", DOWN_DIRECTION)
            .put("ptr.hash", hash)
            .put("link.kind", LINK_KIND)
            .build();

    return SpanLink.from(noopSpanContext(), DEFAULT_FLAGS, "", attributes);
  }
}
