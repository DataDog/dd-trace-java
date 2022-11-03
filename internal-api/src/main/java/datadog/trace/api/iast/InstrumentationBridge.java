package datadog.trace.api.iast;

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

  private static void onUnexpectedException(final String message, final Throwable error) {
    LOG.warn(message, error);
  }
}
