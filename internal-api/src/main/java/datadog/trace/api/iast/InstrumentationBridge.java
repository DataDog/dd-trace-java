package datadog.trace.api.iast;

import java.io.File;
import java.net.URI;
import java.util.List;
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

  private static volatile IastModule MODULE;

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
      onUnexpectedException("Callback for onParameterName threw.", t);
    }
  }

  public static void onParameterValue(final String paramName, final String paramValue) {
    try {
      if (MODULE != null) {
        MODULE.onParameterValue(paramName, paramValue);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onParameterValue threw.", t);
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
        MODULE.onStringConcatFactory(result, arguments, recipe, constants, recipeOffsets);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onStringConcatFactory threw.", t);
    }
    return result;
  }

  public static void onJdbcQuery(String query) {
    try {
      if (MODULE != null) {
        MODULE.onJdbcQuery(query);
      }
    } catch (Throwable t) {
      onUnexpectedException("Callback for onJdbcQuery threw.", t);
    }
  }

  public static void onRuntimeExec(@Nonnull final String... command) {
    try {
      if (MODULE != null) {
        MODULE.onRuntimeExec(command);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onRuntimeExec threw.", t);
    }
  }

  public static void onProcessBuilderStart(@Nonnull final List<String> command) {
    try {
      if (MODULE != null) {
        MODULE.onProcessBuilderStart(command);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onProcessBuilderStart threw.", t);
    }
  }

  public static void onPathTraversal(@Nonnull final String path) {
    try {
      if (MODULE != null) {
        MODULE.onPathTraversal(path);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onPathTraversal threw.", t);
    }
  }

  public static void onPathTraversal(@Nullable final String parent, @Nonnull final String child) {
    try {
      if (MODULE != null) {
        MODULE.onPathTraversal(parent, child);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onPathTraversal threw.", t);
    }
  }

  public static void onPathTraversal(@Nonnull final String first, @Nonnull final String[] more) {
    try {
      if (MODULE != null) {
        MODULE.onPathTraversal(first, more);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onPathTraversal threw.", t);
    }
  }

  public static void onPathTraversal(@Nullable final File parent, @Nonnull final String path) {
    try {
      if (MODULE != null) {
        MODULE.onPathTraversal(parent, path);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onPathTraversal threw.", t);
    }
  }

  public static void onPathTraversal(@Nonnull final URI uri) {
    try {
      if (MODULE != null) {
        MODULE.onPathTraversal(uri);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onPathTraversal threw.", t);
    }
  }

  public static void onStringTrim(@Nullable String self, @Nullable String result) {
    try {
      if (MODULE != null) {
        MODULE.onStringTrim(self, result);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onStringTrim threw.", t);
    }
  }

  public static void onDirContextSearch(String name, String filterExpr, Object[] filterArgs) {
    LOG.debug("Start onDirContextSearch");
    try {
      if (MODULE != null) {
        MODULE.onDirContextSearch(name, filterExpr, filterArgs);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onProcessBuilderStart threw.", t);
    }
    LOG.debug("End onDirContextSearch");
  }

  private static void onUnexpectedException(final String message, final Throwable error) {
    LOG.warn(message, error);
  }
}
