package com.datadog.debugger.exception;

import static com.datadog.debugger.util.ExceptionHelper.getInnerMostThrowable;

import datadog.trace.bootstrap.debugger.DebuggerContext.ClassNameFilter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Computes a fingerprint of an exception based on its stacktrace and exception type. */
public class Fingerprinter {
  private static final Logger LOGGER = LoggerFactory.getLogger(Fingerprinter.class);

  // compute fingerprint of the Throwable based on the stacktrace and exception type
  public static String fingerprint(Throwable t, ClassNameFilter classNameFiltering) {
    t = getInnerMostThrowable(t);
    if (t == null) {
      LOGGER.debug("Unable to find root cause of exception");
      return null;
    }
    Class<? extends Throwable> clazz = t.getClass();
    MessageDigest digest;
    try {
      // need to create a new instance each time to make it thread safe
      // Regarding performance, see the results of micro-benchmarks at the end of the file
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      LOGGER.debug("Unable to find digest algorithm SHA-256", e);
      return null;
    }
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
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(element.toString().getBytes());
      return bytesToHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      LOGGER.debug("Unable to find digest algorithm SHA-256", e);
      return null;
    }
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

/*
 Micro benchmark results:
  jdk8 arm64:
  Benchmark                                   Mode  Cnt  Score   Error  Units
  MessageDigestBenchmark.md5NewInstance       avgt    5  4.974 ± 0.069  us/op
  MessageDigestBenchmark.md5ReuseInstance     avgt    5  4.787 ± 0.137  us/op
  MessageDigestBenchmark.sha1NewInstance      avgt    5  6.131 ± 0.038  us/op
  MessageDigestBenchmark.sha1ReuseInstance    avgt    5  6.088 ± 0.016  us/op
  MessageDigestBenchmark.sha256NewInstance    avgt    5  7.090 ± 0.091  us/op
  MessageDigestBenchmark.sha256ReuseInstance  avgt    5  7.048 ± 0.132  us/op

  jdk11 arm64:
  Benchmark                                   Mode  Cnt  Score   Error  Units
  MessageDigestBenchmark.md5NewInstance       avgt    5  5.258 ± 0.166  us/op
  MessageDigestBenchmark.md5ReuseInstance     avgt    5  5.196 ± 0.053  us/op
  MessageDigestBenchmark.sha1NewInstance      avgt    5  6.639 ± 0.136  us/op
  MessageDigestBenchmark.sha1ReuseInstance    avgt    5  6.522 ± 0.096  us/op
  MessageDigestBenchmark.sha256NewInstance    avgt    5  7.600 ± 0.130  us/op
  MessageDigestBenchmark.sha256ReuseInstance  avgt    5  7.605 ± 0.115  us/op

  jdk17 arm64:
  Benchmark                                   Mode  Cnt  Score   Error  Units
  MessageDigestBenchmark.md5NewInstance       avgt    5  3.749 ± 0.054  us/op
  MessageDigestBenchmark.md5ReuseInstance     avgt    5  3.713 ± 0.061  us/op
  MessageDigestBenchmark.sha1NewInstance      avgt    5  5.708 ± 0.079  us/op
  MessageDigestBenchmark.sha1ReuseInstance    avgt    5  5.646 ± 0.070  us/op
  MessageDigestBenchmark.sha256NewInstance    avgt    5  8.668 ± 0.073  us/op
  MessageDigestBenchmark.sha256ReuseInstance  avgt    5  8.651 ± 0.131  us/op

  jdk21 arm64:
  Benchmark                                   Mode  Cnt  Score   Error  Units
  MessageDigestBenchmark.md5NewInstance       avgt    5  2.702 ± 0.023  us/op
  MessageDigestBenchmark.md5ReuseInstance     avgt    5  2.675 ± 0.018  us/op
  MessageDigestBenchmark.sha1NewInstance      avgt    5  0.818 ± 0.005  us/op
  MessageDigestBenchmark.sha1ReuseInstance    avgt    5  0.798 ± 0.035  us/op
  MessageDigestBenchmark.sha256NewInstance    avgt    5  0.814 ± 0.018  us/op
  MessageDigestBenchmark.sha256ReuseInstance  avgt    5  0.792 ± 0.017  us/op

  jdk21 arm64 -XX:+UnlockDiagnosticVMOptions -XX:-UseSHA1Intrinsics -XX:-UseSHA256Intrinsics
  Benchmark                                   Mode  Cnt  Score   Error  Units
  MessageDigestBenchmark.sha1NewInstance      avgt    5  5.030 ± 0.041  us/op
  MessageDigestBenchmark.sha1ReuseInstance    avgt    5  5.121 ± 0.090  us/op
  MessageDigestBenchmark.sha256NewInstance    avgt    5  7.677 ± 0.089  us/op
  MessageDigestBenchmark.sha256ReuseInstance  avgt    5  7.627 ± 0.065  us/op

  jdk8 x86_64:
  Benchmark                                        Mode  Cnt  Score   Error  Units
  MessageDigestBenchmark.md5NewInstance            avgt    5  4.435 ± 0.166  us/op
  MessageDigestBenchmark.md5ReuseInstance          avgt    5  4.364 ± 0.206  us/op
  MessageDigestBenchmark.sha1NewInstance           avgt    5  5.966 ± 0.224  us/op
  MessageDigestBenchmark.sha1ReuseInstance         avgt    5  5.901 ± 0.349  us/op
  MessageDigestBenchmark.sha256NewInstance         avgt    5  9.220 ± 0.667  us/op
  MessageDigestBenchmark.sha256ReuseInstance       avgt    5  9.162 ± 0.860  us/op

  jdk11 x86_64:
  Benchmark                                        Mode  Cnt  Score   Error  Units
  MessageDigestBenchmark.md5NewInstance            avgt    5  4.716 ± 0.233  us/op
  MessageDigestBenchmark.md5ReuseInstance          avgt    5  4.662 ± 0.258  us/op
  MessageDigestBenchmark.sha1NewInstance           avgt    5  1.444 ± 0.066  us/op
  MessageDigestBenchmark.sha1ReuseInstance         avgt    5  1.396 ± 0.060  us/op
  MessageDigestBenchmark.sha256NewInstance         avgt    5  1.533 ± 0.054  us/op
  MessageDigestBenchmark.sha256ReuseInstance       avgt    5  1.494 ± 0.052  us/op

  jdk17 x86_64:
  Benchmark                                        Mode  Cnt  Score   Error  Units
  MessageDigestBenchmark.md5NewInstance            avgt    5  2.852 ± 0.191  us/op
  MessageDigestBenchmark.md5ReuseInstance          avgt    5  2.816 ± 0.086  us/op
  MessageDigestBenchmark.sha1NewInstance           avgt    5  1.329 ± 0.051  us/op
  MessageDigestBenchmark.sha1ReuseInstance         avgt    5  1.363 ± 0.238  us/op
  MessageDigestBenchmark.sha256NewInstance         avgt    5  1.397 ± 0.133  us/op
  MessageDigestBenchmark.sha256ReuseInstance       avgt    5  1.391 ± 0.047  us/op

  jdk22 x86_64:
  Benchmark                                        Mode  Cnt  Score   Error  Units
  MessageDigestBenchmark.md5NewInstance            avgt    5  4.757 ± 0.179  us/op
  MessageDigestBenchmark.md5ReuseInstance          avgt    5  4.689 ± 0.300  us/op
  MessageDigestBenchmark.sha1NewInstance           avgt    5  1.460 ± 0.049  us/op
  MessageDigestBenchmark.sha1ReuseInstance         avgt    5  1.423 ± 0.066  us/op
  MessageDigestBenchmark.sha256NewInstance         avgt    5  1.567 ± 0.123  us/op
  MessageDigestBenchmark.sha256ReuseInstance       avgt    5  1.521 ± 0.084  us/op
*/
