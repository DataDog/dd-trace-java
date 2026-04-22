package datadog.trace.api.git;

import datadog.trace.util.Strings;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.TimeZone;

public final class PersonInfo {
  public static final PersonInfo NOOP = new PersonInfo();

  private static final String DATE_FORMAT_ISO8601 = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";

  private final String name;
  private final String email;
  private final String iso8601Date;

  public PersonInfo() {
    this(null, null, 0, 0);
  }

  public PersonInfo(final String name, final String email) {
    this(name, email, 0, 0);
  }

  public PersonInfo(final String name, final String email, final String iso8601date) {
    this.name = name;
    this.email = email;
    iso8601Date = iso8601date;
  }

  public PersonInfo(String name, String email, long when, int tzOffset) {
    this.name = name;
    this.email = email;
    iso8601Date = buildISO8601Date(when);
  }

  public String getName() {
    return name;
  }

  public String getEmail() {
    return email;
  }

  public String getIso8601Date() {
    return iso8601Date;
  }

  public boolean isEmpty() {
    return Strings.isBlank(name) && Strings.isBlank(email) && Strings.isBlank(iso8601Date);
  }

  public boolean isComplete() {
    return Strings.isNotBlank(name) && Strings.isNotBlank(email) && Strings.isNotBlank(iso8601Date);
  }

  /**
   * Combine infos by completing the empty information fields in {@code first} with {@code second}'s
   *
   * @param first Base person info
   * @param second Fallback person info
   * @return Combined person info
   */
  public static PersonInfo coalesce(final PersonInfo first, final PersonInfo second) {
    return new PersonInfo(
        Strings.coalesce(first.name, second.name),
        Strings.coalesce(first.email, second.email),
        Strings.coalesce(first.iso8601Date, second.iso8601Date));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    PersonInfo that = (PersonInfo) o;
    return Objects.equals(name, that.name)
        && Objects.equals(email, that.email)
        && Objects.equals(iso8601Date, that.iso8601Date);
  }

  @Override
  public int hashCode() {
    int hash = 1;
    hash = 31 * hash + (name == null ? 0 : name.hashCode());
    hash = 31 * hash + (email == null ? 0 : email.hashCode());
    hash = 31 * hash + (iso8601Date == null ? 0 : iso8601Date.hashCode());
    return hash;
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
        + ", ISO8601Date='"
        + iso8601Date
        + '\''
        + '}';
  }

  private static String buildISO8601Date(final long when) {
    if (when <= 0) {
      return null;
    }

    final SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT_ISO8601);
    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
    return sdf.format(new Date(when));
  }
}
