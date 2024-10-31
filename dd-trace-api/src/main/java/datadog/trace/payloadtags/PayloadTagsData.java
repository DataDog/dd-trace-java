package datadog.trace.payloadtags;

import java.util.ArrayList;
import java.util.List;

/** Used to pass payload tags data from instrumentation to the PayloadTagsProcessor. */
public class PayloadTagsData {
  /**
   * Known tag names to pass pre-extracted payload data from the instrumentation code to the tag
   * processors, as well as to prefix extracted tags, and also to find associated default/configured
   * redaction rules.
   */
  public interface KnownPayloadTags {
    String AWS_REQUEST_BODY = "aws.request.body";
    String AWS_RESPONSE_BODY = "aws.response.body";
  }

  public static final class PathAndValue {
    // either a name (String) or an index (Integer)
    public final Object[] path;
    public final Object value;

    public PathAndValue(Object[] path, Object value) {
      this.path = path;
      this.value = value;
    }
  }

  private final List<PathAndValue> pathAndValues = new ArrayList<>();

  public PayloadTagsData add(Object[] path, Object value) {
    pathAndValues.add(new PathAndValue(path, value));
    return this;
  }

  public List<PathAndValue> all() {
    return pathAndValues;
  }

  public int size() {
    return pathAndValues.size();
  }
}
