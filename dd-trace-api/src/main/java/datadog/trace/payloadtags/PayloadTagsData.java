package datadog.trace.payloadtags;

import java.util.ArrayList;
import java.util.List;

/** Used to pass payload tags data from instrumentation to the PayloadTagsProcessor. */
public class PayloadTagsData {
  /**
   * Known tag name to pass pre-extracted payload data from the instrumentation code to the tag
   * processors, as well as to prefix extracted tags, and also to find associated default/configured
   * redaction rules.
   */
  public interface KnownPayloadTags {
    String AWS_REQUEST_BODY = "aws.request.body";
    String AWS_RESPONSE_BODY = "aws.response.body";
  }

  private final List<PathCursor> cursors = new ArrayList<>();

  public PayloadTagsData append(PathCursor cursor) {
    cursors.add(cursor);
    return this;
  }

  public List<PathCursor> all() {
    return cursors;
  }

  public int size() {
    return cursors.size();
  }
}
