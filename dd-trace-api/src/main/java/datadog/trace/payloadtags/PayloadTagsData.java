package datadog.trace.payloadtags;

/** Used to pass payload tags data from instrumentation to the PayloadTagsProcessor. */
public class PayloadTagsData {

  public static final class PathAndValue {
    public final Object[] path;
    public final Object value;

    public PathAndValue(Object[] path, Object value) {
      this.path = path;
      this.value = value;
    }
  }

  public final PathAndValue[] pathAndValues;

  public PayloadTagsData(PathAndValue[] pathAndValues) {
    this.pathAndValues = pathAndValues;
  }
}
