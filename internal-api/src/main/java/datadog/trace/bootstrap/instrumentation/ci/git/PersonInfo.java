package datadog.trace.bootstrap.instrumentation.ci.git;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@Builder
@EqualsAndHashCode
@ToString
public class PersonInfo {

  public static final PersonInfo NOOP = PersonInfo.builder().build();

  private static final String DATE_FORMAT_ISO8601 = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";

  private final String name;
  private final String email;
  private final long when;
  private final int tzOffset;

  public String getISO8601Date() {
    if (when <= 0) {
      return null;
    }

    final SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT_ISO8601);
    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
    return sdf.format(new Date(when));
  }
}
