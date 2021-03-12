package datadog.trace.bootstrap.instrumentation.ci.git;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.TimeZone;

public class PersonInfo {

  public static final PersonInfo NOOP = new PersonInfo();

  private static final String DATE_FORMAT_ISO8601 = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";

  private final String name;
  private final String email;
  private final long when;
  private final int tzOffset;

  public PersonInfo() {
    this(null, null, 0, 0);
  }

  public PersonInfo(String name, String email, long when, int tzOffset) {
    this.name = name;
    this.email = email;
    this.when = when;
    this.tzOffset = tzOffset;
  }

  public String getISO8601Date() {
    if (when <= 0) {
      return null;
    }

    final SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT_ISO8601);
    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
    return sdf.format(new Date(when));
  }

  public String getName() {
    return name;
  }

  public String getEmail() {
    return email;
  }

  public long getWhen() {
    return when;
  }

  public int getTzOffset() {
    return tzOffset;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PersonInfo that = (PersonInfo) o;
    return when == that.when
        && tzOffset == that.tzOffset
        && Objects.equals(name, that.name)
        && Objects.equals(email, that.email);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, email, when, tzOffset);
  }

  @Override
  public String toString() {
    return "PersonInfo{"
        + "name='"
        + name
        + '\''
        + ", email='"
        + email
        + '\''
        + ", when="
        + when
        + ", tzOffset="
        + tzOffset
        + '}';
  }
}
