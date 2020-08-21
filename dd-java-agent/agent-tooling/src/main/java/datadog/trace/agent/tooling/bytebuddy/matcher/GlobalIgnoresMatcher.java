package datadog.trace.agent.tooling.bytebuddy.matcher;

import java.util.regex.Pattern;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Global ignores matcher used by the agent.
 *
 * <p>This matcher services two main purposes:
 * <li>
 *
 *     <ul>
 *       Ignore classes that are unsafe or pointless to transform. 'System' level classes like jvm
 *       classes or groovy classes, other tracers, debuggers, etc.
 * </ul>
 *
 * <ul>
 *   Uses {@link AdditionalLibraryIgnoresMatcher} to also ignore additional classes to minimize
 *   number of classes we apply expensive matchers to.
 * </ul>
 */
public class GlobalIgnoresMatcher<T extends TypeDescription>
    extends ElementMatcher.Junction.AbstractBase<T> {

  private static final Pattern COM_MCHANGE_PROXY =
      Pattern.compile("^com\\.mchange\\.v2\\.c3p0\\..*Proxy$");

  public static <T extends TypeDescription> ElementMatcher.Junction<T> globalIgnoresMatcher(
      final boolean skipAdditionalLibraryMatcher) {
    return new GlobalIgnoresMatcher<>(skipAdditionalLibraryMatcher);
  }

  private final ElementMatcher<T> additionalLibraryIgnoreMatcher =
      AdditionalLibraryIgnoresMatcher.additionalLibraryIgnoresMatcher();
  private final boolean skipAdditionalLibraryMatcher;

  private GlobalIgnoresMatcher(final boolean skipAdditionalLibraryMatcher) {
    this.skipAdditionalLibraryMatcher = skipAdditionalLibraryMatcher;
  }

  /**
   * Be very careful about the types of matchers used in this section as they are called on every
   * class load, so they must be fast. Generally speaking try to only use name matchers as they
   * don't have to load additional info.
   */
  @Override
  public boolean matches(final T target) {
    final String name = target.getActualName();
    switch (name.charAt(0) - 'a') {
        // starting at zero to get a tableswitch from javac, though it looks horrendous
      case 'a' - 'a':
        break;
      case 'b' - 'a':
        break;
      case 'c' - 'a':
        if (name.startsWith("com.")) {
          if (name.startsWith("com.p6spy.")
              || name.startsWith("com.newrelic.")
              || name.startsWith("com.dynatrace.")
              || name.startsWith("com.jloadtrace.")
              || name.startsWith("com.appdynamics.")
              || name.startsWith("com.singularity.")
              || name.startsWith("com.jinspired.")
              || name.startsWith("com.intellij.rt.debugger.")) {
            return true;
          }
          if (name.startsWith("com.sun.")) {
            return !name.startsWith("com.sun.messaging.")
                && !name.startsWith("com.sun.jersey.api.client");
          }
          if (COM_MCHANGE_PROXY.matcher(name).matches()) {
            return true;
          }
        }
        if (name.startsWith("clojure.")) {
          return true;
        }
        break;
      case 'd' - 'a':
        if (name.startsWith("datadog.")) {
          if (name.startsWith("datadog.opentracing.")
              || name.startsWith("datadog.trace.core.")
              || name.startsWith("datadog.slf4j.")) {
            return true;
          }
          if (name.startsWith("datadog.trace.")) {
            // FIXME: We should remove this once
            // https://github.com/raphw/byte-buddy/issues/558 is fixed
            return !name.equals(
                    "datadog.trace.bootstrap.instrumentation.java.concurrent.RunnableWrapper")
                && !name.equals(
                    "datadog.trace.bootstrap.instrumentation.java.concurrent.CallableWrapper");
          }
        }
        break;
      case 'e' - 'a':
        break;
      case 'f' - 'a':
        break;
      case 'g' - 'a':
        break;
      case 'h' - 'a':
        break;
      case 'i' - 'a':
        if (name.startsWith("io.micronaut.tracing.") || name.startsWith("io.micrometer.")) {
          return true;
        }
        break;
      case 'j' - 'a':
        if (name.startsWith("jdk.")) {
          return true;
        }
        if (name.startsWith("java.")) {
          // allow exception profiling instrumentation
          if (name.equals("java.lang.Throwable")) {
            return false;
          }
          if (name.equals("java.net.URL") || name.equals("java.net.HttpURLConnection")) {
            return false;
          }
          if (name.startsWith("java.rmi.") || name.startsWith("java.util.concurrent.")) {
            return false;
          }
          // Concurrent instrumentation modifies the structure of
          // Cleaner class incompatibly with java9+ modules.
          // Working around until a long-term fix for modules can be
          // put in place.
          return !name.startsWith("java.util.logging.")
              || name.equals("java.util.logging.LogManager$Cleaner");
        }
        break;
      case 'k' - 'a':
        break;
      case 'l' - 'a':
        break;
      case 'm' - 'a':
        break;
      case 'n' - 'a':
        if (name.startsWith("net.bytebuddy.")) {
          return true;
        }
        break;
      case 'o' - 'a':
        if (name.startsWith("org.")) {
          if (name.startsWith("org.aspectj.") || name.startsWith("org.jinspired.")) {
            return true;
          }
          // groovy
          if (name.startsWith("org.groovy.") || name.startsWith("org.apache.groovy.")) {
            return true;
          }
          if (name.startsWith("org.codehaus.groovy.")) {
            // We seem to instrument some classes in runtime
            return !name.startsWith("org.codehaus.groovy.runtime.");
          }
          if (name.startsWith("org.apache.log4j.")) {
            return !name.equals("org.apache.log4j.MDC");
          }
        }
        break;
      case 'p' - 'a':
        break;
      case 'q' - 'a':
        break;
      case 'r' - 'a':
        break;
      case 's' - 'a':
        if (name.startsWith("sun.")) {
          return !name.startsWith("sun.net.www.protocol.")
              && !name.startsWith("sun.rmi.server")
              && !name.startsWith("sun.rmi.transport")
              && !name.equals("sun.net.www.http.HttpClient");
        }
        break;
      default:
    }

    final int firstDollar = name.indexOf('$');
    if (firstDollar > -1) {
      // clojure class patterns
      if (name.startsWith("loader__", firstDollar + 1)) {
        return true;
      }
      int dollar = firstDollar;
      while (dollar > -1) {
        if (name.startsWith("fn__", dollar + 1) || name.startsWith("reify__", dollar + 1)) {
          return true;
        }
        dollar = name.indexOf('$', dollar + 1);
      }

      if (name.contains("$JaxbAccessor")
          || name.contains("CGLIB$$")
          || name.contains("$__sisu")
          || name.contains("$$EnhancerByProxool$$")
          || name.startsWith("org.springframework.core.$Proxy")) {
        return true;
      }
    }
    if (name.contains("javassist") || name.contains(".asm.")) {
      return true;
    }

    return !skipAdditionalLibraryMatcher && additionalLibraryIgnoreMatcher.matches(target);
  }

  @Override
  public String toString() {
    return "globalIgnoresMatcher(" + additionalLibraryIgnoreMatcher.toString() + ")";
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    } else if (other == null) {
      return false;
    } else if (getClass() != other.getClass()) {
      return false;
    } else {
      return additionalLibraryIgnoreMatcher.equals(
          ((GlobalIgnoresMatcher) other).additionalLibraryIgnoreMatcher);
    }
  }

  @Override
  public int hashCode() {
    return 17 * 31 + additionalLibraryIgnoreMatcher.hashCode();
  }
}
