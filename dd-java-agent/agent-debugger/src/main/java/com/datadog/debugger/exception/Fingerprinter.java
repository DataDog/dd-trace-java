package com.datadog.debugger.exception;

import static com.datadog.debugger.util.ExceptionHelper.getInnerMostThrowable;

import com.datadog.debugger.util.ClassNameFiltering;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Computes a fingerprint of an exception based on its stacktrace and exception type. */
public class Fingerprinter {
  private static final Logger LOGGER = LoggerFactory.getLogger(Fingerprinter.class);
  private static MessageDigest digest;

  static {
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (Throwable e) {
      LOGGER.debug("Unable to find digest algorithm SHA-256", e);
    }
  }

  // compute fingerprint of the Throwable based on the stacktrace and exception type
  public static String fingerprint(Throwable t, ClassNameFiltering classNameFiltering) {
    if (digest == null) {
      return null;
    }
    t = getInnerMostThrowable(t);
    if (t == null) {
      LOGGER.debug("Unable to find root cause of exception");
      return null;
    }
    Class<? extends Throwable> clazz = t.getClass();
    String typeName = clazz.getTypeName();
    digest.update(typeName.getBytes());
    StackTraceElement[] stackTrace = t.getStackTrace();
    for (StackTraceElement stackTraceElement : stackTrace) {
      String className = stackTraceElement.getClassName();
      if (classNameFiltering.isExcluded(className)) {
        continue;
      }
      digest.update(stackTraceElement.toString().getBytes());
    }
    return bytesToHex(digest.digest());
  }

  public static String fingerprint(StackTraceElement element) {
    if (digest == null) {
      return null;
    }
    digest.update(element.toString().getBytes());
    return bytesToHex(digest.digest());
  }

  // convert byte[] to hex string
  private static String bytesToHex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      result.append(Integer.toHexString((b & 0xff)));
    }
    return result.toString();
  }
}
