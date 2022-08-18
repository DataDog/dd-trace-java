package datadog.trace.util;

/** Helper class for handling tags */
public final class TagsHelper {
  private static final int MAX_LENGTH = 200;

  /**
   * Taken from:
   * https://github.com/DataDog/logs-backend/blob/d0b3289ce2c63c1e8f961f03fc4e03318fb36b0f/processing/src/main/java/com/dd/logs/processing/common/Tags.java#L44
   *
   * <p>Sanitizes a tag, more or less following (but not exactly) as per recommended DataDog
   * guidelines.
   *
   * <p>See the exact guidelines:
   * https://docs.datadoghq.com/getting_started/tagging/#tags-best-practices
   *
   * <p>1. Tags must start with a letter, and after that may contain: - Alphanumerics - Underscores
   * - Minuses - Colons - Periods - Slashes Other special characters get converted to underscores.
   * Note: A tag cannot end with a colon (e.g., tag:)
   *
   * <p>2. Tags can be up to 200 characters long and support unicode.
   *
   * <p>3. Tags are converted to lowercase.
   *
   * <p>4. A tag can have a value or a key:value syntax: For optimal functionality, we recommend
   * constructing tags that use the key:value syntax. The key is always what precedes the first
   * colon of the global tag definition, e.g.: - role:database:mysql is parsed as key:role ,
   * value:database:mysql - role_database:mysql is parsed as key:role_database , value:mysql
   * Examples of commonly used metric tag keys are env, instance, name, and role.
   *
   * <p>5. device, host, and source are reserved tag keys and cannot be specified in the standard
   * way.
   *
   * <p>6. Tags shouldn't originate from unbounded sources, such as EPOCH timestamps or user IDs.
   * These tags may impact platform performance and billing.
   *
   * <p>Changes: we trim leading and trailing spaces.
   *
   * @param tag The tag to sanitize.
   * @return A sanitized tag, or null if the provided tag was null.
   */
  public static String sanitize(String tag) {
    if (tag == null) {
      return null;
    }
    String lower = tag.toLowerCase().trim();
    int length = Math.min(lower.length(), MAX_LENGTH);
    StringBuilder sanitized = new StringBuilder(length);
    for (int i = 0; i < length; ++i) {
      char c = lower.charAt(i);
      if (isValid(c)) {
        sanitized.append(c);
      } else {
        sanitized.append('_');
      }
    }
    return sanitized.toString();
  }

  static boolean isValid(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9')
        || c == '-'
        || c == '_'
        || c == '.'
        || c == '/'
        || c == ':';
  }
}
