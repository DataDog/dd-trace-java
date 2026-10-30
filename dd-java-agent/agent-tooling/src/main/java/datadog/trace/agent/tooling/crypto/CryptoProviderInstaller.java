package datadog.trace.agent.tooling.crypto;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.environment.SystemProperties;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.security.Security;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Experimental: inserts the Amazon Corretto Crypto Provider (ACCP) at position 1 of the JCA
 * provider list, so {@code Cipher}/{@code Signature}/{@code Mac}/{@code MessageDigest}/{@code
 * KeyAgreement} lookups that name no provider (including the ones JSSE makes during TLS handshakes)
 * resolve to its native AWS-LC implementations.
 *
 * <p>The POC does not ship ACCP: it only uses ACCP when it is visible to the system class loader
 * (plain {@code -cp} or Jib-style flat class paths). It does nothing when:
 *
 * <ul>
 *   <li>the host or JDK looks FIPS-configured (kernel FIPS flag, a provider whose name contains
 *       "FIPS", BouncyCastle approved-only mode, or a FIPS build of ACCP),
 *   <li>the security configuration is customised ({@code -Djava.security.properties}, or a provider
 *       that is not part of the JDK is already registered),
 *   <li>ACCP is already registered,
 *   <li>ACCP's native library failed to load or its self-tests did not pass.
 * </ul>
 *
 * <p>Must not run on the premain thread: initialising ACCP uses {@code java.util.logging} and
 * {@code java.nio.file} (it extracts its native library to a temp directory).
 */
public final class CryptoProviderInstaller {
  private static final Logger log = LoggerFactory.getLogger(CryptoProviderInstaller.class);

  static final String ACCP_CLASS_NAME =
      "com.amazon.corretto.crypto.provider.AmazonCorrettoCryptoProvider";

  static final File KERNEL_FIPS_FLAG = new File("/proc/sys/crypto/fips_enabled");

  /** What {@link #install} did. */
  public enum Outcome {
    INSTALLED,
    ALREADY_PRESENT,
    NOT_ON_CLASSPATH,
    SKIPPED_FIPS,
    SKIPPED_CUSTOM_SECURITY_PROPERTIES,
    SKIPPED_NON_JDK_PROVIDER,
    UNHEALTHY,
    FAILED
  }

  private CryptoProviderInstaller() {}

  /** Called reflectively from {@code Agent} on a dedicated agent thread. */
  public static void install() {
    long start = System.nanoTime();
    Outcome outcome;
    try {
      outcome = install(ACCP_CLASS_NAME, ClassLoader.getSystemClassLoader(), KERNEL_FIPS_FLAG);
    } catch (Throwable t) {
      log.debug("Experimental crypto provider: unexpected failure", t);
      outcome = Outcome.FAILED;
    }
    long tookMs = NANOSECONDS.toMillis(System.nanoTime() - start);
    if (outcome == Outcome.INSTALLED) {
      log.debug(
          "Experimental crypto provider: installed {} at position 1 in {} ms",
          Security.getProviders()[0].getInfo(),
          tookMs);
    } else {
      log.debug("Experimental crypto provider: not installed ({}) after {} ms", outcome, tookMs);
    }
  }

  static synchronized Outcome install(String className, ClassLoader loader, File kernelFipsFlag) {
    if (isFipsEnvironment(kernelFipsFlag)) {
      return Outcome.SKIPPED_FIPS;
    }
    if (SystemProperties.get("java.security.properties") != null) {
      return Outcome.SKIPPED_CUSTOM_SECURITY_PROPERTIES;
    }
    for (Provider existing : Security.getProviders()) {
      if (className.equals(existing.getClass().getName())) {
        return Outcome.ALREADY_PRESENT;
      }
    }
    for (Provider existing : Security.getProviders()) {
      if (!isJdkProvider(existing)) {
        return Outcome.SKIPPED_NON_JDK_PROVIDER;
      }
    }

    Class<?> providerClass;
    try {
      // initialising the class loads and self-checks the native library
      providerClass = Class.forName(className, true, loader);
    } catch (ClassNotFoundException e) {
      return Outcome.NOT_ON_CLASSPATH;
    } catch (LinkageError e) {
      log.debug("Experimental crypto provider: {} failed to initialise", className, e);
      return Outcome.UNHEALTHY;
    }

    Provider provider;
    try {
      provider = providerInstance(providerClass);
      if (invoke(provider, "getLoadingError") != null) {
        return Outcome.UNHEALTHY;
      }
      if (Boolean.TRUE.equals(invoke(provider, "isFips"))) {
        return Outcome.SKIPPED_FIPS;
      }
      invoke(provider, "assertHealthy"); // throws if the native self-tests did not pass
    } catch (InvocationTargetException e) {
      log.debug("Experimental crypto provider: health check failed", e.getCause());
      return Outcome.UNHEALTHY;
    } catch (ReflectiveOperationException | RuntimeException e) {
      log.debug("Experimental crypto provider: unexpected provider shape", e);
      return Outcome.FAILED;
    }

    // insertProviderAt returns -1 if a provider with the same name is already registered
    int position = Security.insertProviderAt(provider, 1);
    return position == 1 ? Outcome.INSTALLED : Outcome.ALREADY_PRESENT;
  }

  static boolean isFipsEnvironment(File kernelFipsFlag) {
    if ("1".equals(readFirstLine(kernelFipsFlag))) {
      return true;
    }
    if (Boolean.parseBoolean(SystemProperties.get("org.bouncycastle.fips.approved_only"))) {
      return true;
    }
    for (Provider existing : Security.getProviders()) {
      if (existing.getName().toUpperCase(Locale.ROOT).contains("FIPS")) {
        return true;
      }
    }
    return false;
  }

  /** JDK providers live in the boot or platform (Java 9+) / extension (Java 8) class loader. */
  static boolean isJdkProvider(Provider provider) {
    ClassLoader providerLoader = provider.getClass().getClassLoader();
    return providerLoader == null
        || providerLoader == ClassLoader.getSystemClassLoader().getParent();
  }

  private static Provider providerInstance(Class<?> providerClass)
      throws ReflectiveOperationException {
    try {
      Field instance = providerClass.getField("INSTANCE");
      if (Modifier.isStatic(instance.getModifiers())
          && Provider.class.isAssignableFrom(instance.getType())) {
        return (Provider) instance.get(null);
      }
    } catch (NoSuchFieldException ignored) {
      // fall through to the no-arg constructor
    }
    return (Provider) providerClass.getConstructor().newInstance();
  }

  private static Object invoke(Provider provider, String methodName)
      throws ReflectiveOperationException {
    Method method = provider.getClass().getMethod(methodName);
    return method.invoke(provider);
  }

  private static String readFirstLine(File file) {
    if (!file.isFile()) {
      return null;
    }
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.US_ASCII))) {
      String line = reader.readLine();
      return line == null ? null : line.trim();
    } catch (Exception e) {
      return null;
    }
  }
}
