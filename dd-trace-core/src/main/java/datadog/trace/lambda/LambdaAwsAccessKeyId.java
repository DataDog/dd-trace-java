package datadog.trace.lambda;

import datadog.trace.config.inversion.ConfigHelper;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The execution environment's AWS access key ID, which tracer AppSec puts on the invocation span so
 * that the AppSec signal can be joined to CloudTrail's {@code userIdentity.accessKeyId}.
 *
 * <p>Only {@code AWS_ACCESS_KEY_ID} is read: the secret access key and the session token are never
 * read. The variable is read through {@link ConfigHelper#env(String)} rather than {@code
 * Config.getEnv}, which would report the value to configuration telemetry.
 */
final class LambdaAwsAccessKeyId {

  private static final Logger log = LoggerFactory.getLogger(LambdaAwsAccessKeyId.class);

  static final String TAG = "aws.lambda.access_key_id";

  static final String ENV_ACCESS_KEY_ID = "AWS_ACCESS_KEY_ID";
  static final String ENV_INITIALIZATION_TYPE = "AWS_LAMBDA_INITIALIZATION_TYPE";

  /** Temporary (ASIA) or long-term (AKIA) key IDs, matched in full by {@code matches()}. */
  private static final Pattern FORMAT = Pattern.compile("(ASIA|AKIA)[A-Z0-9]{16}");

  private static final Object UNREAD = new Object();

  /**
   * The cached value: {@link #UNREAD}, a valid key ID, or {@code null} when the variable is missing
   * or invalid. Written once with an immutable value, so concurrent first invocations on Lambda
   * Managed Instances at worst both read the variable and store the same result.
   */
  private static volatile Object cached = UNREAD;

  private LambdaAwsAccessKeyId() {}

  /**
   * Returns the key ID, reading it at the first call, which is the first invocation AppSec
   * monitors. On SnapStart it is never read: see {@link #read(Function)}.
   *
   * @return the key ID, or null if it is missing, invalid, or the function runs on SnapStart
   */
  static String get() {
    return get(ConfigHelper::env);
  }

  /** Same as {@link #get()}, reading the environment through {@code env}. */
  static String get(Function<String, String> env) {
    Object value = cached;
    if (value == UNREAD) {
      value = read(env);
      cached = value;
    }
    return (String) value;
  }

  /**
   * Reads and validates {@code AWS_ACCESS_KEY_ID}.
   *
   * <p>On SnapStart the variable is not read: the JVM's environment is the one captured before the
   * snapshot, so it is either empty or a key that every restored execution environment shares.
   *
   * @return the key ID if it is usable, otherwise null
   */
  private static String read(Function<String, String> env) {
    if ("snap-start".equals(env.apply(ENV_INITIALIZATION_TYPE))) {
      log.debug("SnapStart function, AWS access key ID tag disabled");
      return null;
    }
    String accessKeyId = env.apply(ENV_ACCESS_KEY_ID);
    if (accessKeyId == null || !FORMAT.matcher(accessKeyId).matches()) {
      // never log the value itself
      log.debug("AWS access key ID is missing or malformed, no tag");
      return null;
    }
    return accessKeyId;
  }

  /** Forgets the cached value. Package-private for use in tests only. */
  static void reset() {
    cached = UNREAD;
  }
}
