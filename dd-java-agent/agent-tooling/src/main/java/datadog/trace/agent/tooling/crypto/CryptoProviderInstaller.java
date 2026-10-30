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

/** Experimental: inserts ACCP at JCA position 1 unless JCA looks customised or FIPS. */
public final class CryptoProviderInstaller {
  private static final Logger log = LoggerFactory.getLogger(CryptoProviderInstaller.class);

  static final String ACCP_CLASS_NAME =
      "com.amazon.corretto.crypto.provider.AmazonCorrettoCryptoProvider";

  static final File KERNEL_FIPS_FLAG = new File("/proc/sys/crypto/fips_enabled");

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
    if (loader.getResource(className.replace('.', '/') + ".class") == null) {
      return Outcome.NOT_ON_CLASSPATH;
    }
    if (isKernelFipsMode(kernelFipsFlag)
        || Boolean.parseBoolean(SystemProperties.get("org.bouncycastle.fips.approved_only"))) {
      return Outcome.SKIPPED_FIPS;
    }
    if (SystemProperties.get("java.security.properties") != null) {
      return Outcome.SKIPPED_CUSTOM_SECURITY_PROPERTIES;
    }
    Outcome blocked = checkRegisteredProviders(className);
    if (blocked != null) {
      return blocked;
    }

    Class<?> providerClass;
    try {
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
      invoke(provider, "assertHealthy");
    } catch (InvocationTargetException e) {
      log.debug("Experimental crypto provider: health check failed", e.getCause());
      return Outcome.UNHEALTHY;
    } catch (ReflectiveOperationException | RuntimeException e) {
      log.debug("Experimental crypto provider: unexpected provider shape", e);
      return Outcome.FAILED;
    }

    synchronized (Security.class) {
      blocked = checkRegisteredProviders(className);
      if (blocked != null) {
        return blocked;
      }
      return Security.insertProviderAt(provider, 1) == 1
          ? Outcome.INSTALLED
          : Outcome.ALREADY_PRESENT;
    }
  }

  static Outcome checkRegisteredProviders(String className) {
    boolean present = false;
    boolean nonJdk = false;
    for (Provider existing : Security.getProviders()) {
      if (isFipsProvider(existing)) {
        return Outcome.SKIPPED_FIPS;
      }
      present |= className.equals(existing.getClass().getName());
      nonJdk |= !isJdkProvider(existing);
    }
    if (present) {
      return Outcome.ALREADY_PRESENT;
    }
    return nonJdk ? Outcome.SKIPPED_NON_JDK_PROVIDER : null;
  }

  static boolean isKernelFipsMode(File kernelFipsFlag) {
    return "1".equals(readFirstLine(kernelFipsFlag));
  }

  static boolean isFipsProvider(Provider provider) {
    return provider.getName().toUpperCase(Locale.ROOT).contains("FIPS");
  }

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
