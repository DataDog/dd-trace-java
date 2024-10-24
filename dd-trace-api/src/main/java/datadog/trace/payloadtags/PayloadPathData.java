package datadog.trace.payloadtags;

import java.util.ArrayList;
import java.util.List;

/** Used to pass payload path data from instrumentation to the tag post-processor. */
public class PayloadPathData {
  public static final String AWS_REQUEST_BODY = "aws.request.body";
  public static final String AWS_RESPONSE_BODY = "aws.response.body";

  private final List<PathCursor> cursors = new ArrayList<>();

  public PayloadPathData append(PathCursor cursor) {
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
