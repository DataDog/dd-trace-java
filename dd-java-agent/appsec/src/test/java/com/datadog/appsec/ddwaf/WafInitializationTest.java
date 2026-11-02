package com.datadog.appsec.ddwaf;

import static ch.qos.logback.classic.Level.ERROR;
import static ch.qos.logback.classic.Level.WARN;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.datadog.ddwaf.Waf;
import datadog.environment.SystemProperties;
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.ExceptionMethod;
import net.bytebuddy.implementation.MethodCall;
import org.slf4j.LoggerFactory;
import org.tabletest.junit.TableTest;

public class WafInitializationTest {
  private static final ThreadLocal<List<String>> SCANNER_ARGUMENTS = new ThreadLocal<>();

  @TableTest({
    "Scenario          | Scanner factory | Expected libc",
    "access denied     | deniedScanner   | unknown      ",
    "maps file missing | missingScanner  | unknown      ",
    "musl detected     | muslScanner     | musl         "
  })
  void libcDetectionKeepsWafOfflineAndPreservesStartupLogs(
      String scannerFactory, String expectedLibc) throws Exception {
    // Simulate a denied WAF load without loading the real native library.
    DynamicType.Unloaded<?> failingWaf =
        new ByteBuddy()
            .subclass(Object.class)
            .name(Waf.class.getName())
            .defineMethod("initialize", void.class, Modifier.PUBLIC | Modifier.STATIC)
            .withParameters(boolean.class)
            .intercept(ExceptionMethod.throwing(SecurityException.class, "WAF loading denied"))
            .make();

    // Exercise the Linux detection path on every host, without changing global properties.
    DynamicType.Unloaded<?> linuxProperties =
        new ByteBuddy()
            .redefine(SystemProperties.class)
            .method(named("get").and(takesArguments(String.class)))
            .intercept(
                MethodCall.invoke(
                        WafInitializationTest.class.getMethod("linuxProperty", String.class))
                    .withAllArguments())
            .make();

    Logger logger = (Logger) LoggerFactory.getLogger(WafInitialization.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    List<String> scannerArguments = new ArrayList<>();
    SCANNER_ARGUMENTS.set(scannerArguments);
    try {
      // Run the real getLibc() body with a controlled Scanner construction.
      // A fresh classloader isolates the static ONLINE field from other tests.
      Class<?> initialization =
          new ByteBuddy()
              .redefine(WafInitialization.class)
              .visit(
                  MemberSubstitution.strict()
                      .constructor(
                          isDeclaredBy(Scanner.class).and(takesArguments(File.class, String.class)))
                      .replaceWith(
                          WafInitializationTest.class.getMethod(
                              scannerFactory, File.class, String.class))
                      .on(named("getLibc")))
              .make()
              .include(failingWaf, linuxProperties)
              .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
              .getLoaded();

      Field online = initialization.getField("ONLINE");
      assertFalse(online.getBoolean(null), "WAF initialization must degrade to offline");
      assertFalse(online.getBoolean(null), "Later accesses must not throw NoClassDefFoundError");

      assertEquals(
          Arrays.asList(new File("/proc/self/maps").getPath(), "ISO-8859-1"), scannerArguments);
      assertEquals(2, logs.list.size());
      ILoggingEvent wafFailure = logs.list.get(0);
      assertEquals(WARN, wafFailure.getLevel());
      assertEquals("Error initializing WAF library", wafFailure.getFormattedMessage());
      assertNotNull(wafFailure.getThrowableProxy());
      assertEquals(
          SecurityException.class.getName(), wafFailure.getThrowableProxy().getClassName());
      assertEquals("WAF loading denied", wafFailure.getThrowableProxy().getMessage());

      ILoggingEvent startupFailure = logs.list.get(1);
      assertEquals(ERROR, startupFailure.getLevel());
      assertNotNull(startupFailure.getMarker());
      assertEquals("CRITICAL", startupFailure.getMarker().getName());
      assertTrue(startupFailure.getFormattedMessage().contains("AppSec could not start"));
      assertTrue(startupFailure.getFormattedMessage().contains("libc: " + expectedLibc + ","));
    } finally {
      SCANNER_ARGUMENTS.remove();
      logger.detachAppender(logs);
      logs.stop();
    }
  }

  public static String linuxProperty(String property) {
    return "os.name".equals(property) ? "Linux" : SystemProperties.get(property);
  }

  public static Scanner muslScanner(File file, String charsetName) {
    recordScannerArguments(file, charsetName);
    return new Scanner("7f000000-7f001000 r-xp 00000000 00:00 0 /lib/ld-musl-x86_64.so.1\n");
  }

  private static void recordScannerArguments(File file, String charsetName) {
    SCANNER_ARGUMENTS.get().add(file.getPath());
    SCANNER_ARGUMENTS.get().add(charsetName);
  }

  public static Scanner missingScanner(File file, String charsetName) throws FileNotFoundException {
    recordScannerArguments(file, charsetName);
    throw new FileNotFoundException("Libc maps file missing");
  }

  public static Scanner deniedScanner(File file, String charsetName) {
    recordScannerArguments(file, charsetName);
    throw new SecurityException("Libc detection denied");
  }
}
