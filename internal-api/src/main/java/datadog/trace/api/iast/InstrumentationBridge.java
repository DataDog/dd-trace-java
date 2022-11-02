package datadog.trace.api.iast;

import java.util.List;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridge between instrumentations and {@link IastModule} that contains the business logic relative
 * to vulnerability detection. The class contains a list of {@code public static} methods that will
 * be injected into the bytecode via {@code invokestatic} instructions. It's important that all
 * methods are protected from exception leakage.
 */
public abstract class InstrumentationBridge {

  private static final Logger LOG = LoggerFactory.getLogger(InstrumentationBridge.class);

  private static IastModule MODULE;

  private InstrumentationBridge() {}

  public static void registerIastModule(final IastModule module) {
    MODULE = module;
  }

  /**
   * Executed when access to a cryptographic cipher is detected
   *
   * <p>{@link javax.crypto.Cipher#getInstance(String)}
   */
  public static void onCipherGetInstance(@Nonnull final String algorithm) {
    try {
      if (MODULE != null) {
        MODULE.onCipherAlgorithm(algorithm);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onCipher threw.", t);
    }
  }

  /**
   * Executed when access to a message digest algorithm is detected
   *
   * <p>{@link java.security.MessageDigest#getInstance(String)}
   */
  public static void onMessageDigestGetInstance(@Nonnull final String algorithm) {
    try {
      if (MODULE != null) {
        MODULE.onHashingAlgorithm(algorithm);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onHash threw.", t);
    }
  }

  public static void onParameterName(final String parameterName) {
    try {
      if (MODULE != null) {
        MODULE.onParameterName(parameterName);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onHash threw.", t);
    }
  }

  public static void onParameterValue(final String paramName, final String paramValue) {
    try {
      if (MODULE != null) {
        MODULE.onParameterValue(paramName, paramValue);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onHash threw.", t);
    }
  }

  public static void onStringConcat(
      @Nonnull final String self, @Nullable final String param, @Nonnull final String result) {
    try {
      if (MODULE != null) {
        MODULE.onStringConcat(self, param, result);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onStringConcat threw.", t);
    }
  }

  public static void onStringConstructor(CharSequence param, String result) {
    try {
      if (MODULE != null) {
        MODULE.onStringConstructor(param, result);
      }
    } catch (Throwable t) {
      onUnexpectedException("Callback for onStringConstructor has thrown", t);
    }
  }

  public static void onStringBuilderInit(
      @Nonnull final StringBuilder self, @Nullable final CharSequence param) {
    try {
      if (MODULE != null) {
        MODULE.onStringBuilderInit(self, param);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onStringBuilderInit threw.", t);
    }
  }

  public static void onStringBuilderAppend(
      @Nonnull final StringBuilder self, @Nullable final CharSequence param) {
    try {
      if (MODULE != null) {
        MODULE.onStringBuilderAppend(self, param);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onStringBuilderAppend threw.", t);
    }
  }

  public static void onStringBuilderToString(
      @Nonnull final StringBuilder self, @Nonnull final String result) {
    try {
      if (MODULE != null) {
        MODULE.onStringBuilderToString(self, result);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onStringBuilderToString threw.", t);
    }
  }

  public static String onStringConcatFactory(
      final String result,
      final String[] arguments,
      final String recipe,
      final Object[] constants,
      final int[] recipeOffsets) {
    try {
      if (MODULE != null) {
        MODULE.onStringConcatFactory(arguments, result, recipe, constants, recipeOffsets);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onStringConcatFactory threw.", t);
    }
    return result;
  }

  public static void onRuntimeExec(@Nullable final String... command) {
    try {
      if (MODULE != null) {
        MODULE.onRuntimeExec(command);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onRuntimeExec threw.", t);
    }
  }

  public static void onProcessBuilderStart(@Nullable final List<String> command) {
    try {
      if (MODULE != null) {
        MODULE.onProcessBuilderStart(command);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onProcessBuilderStart threw.", t);
    }
  }

  public static String onStringFormat(Locale l, String fmt, Object[] args) {
    try {
      if (MODULE != null) {
        return MODULE.onStringFormat(l, fmt, args);
      }
    } catch (RealCallThrowable t) {
      t.rethrow();
    } catch (Throwable t) {
      onUnexpectedException("Callback for onStringBuilderToString threw.", t);
      return String.format(l, fmt, args);
    }
    return null; // unreachable
  }

  private static void onUnexpectedException(final String message, final Throwable error) {
    LOG.warn(message, error);
  }
}
