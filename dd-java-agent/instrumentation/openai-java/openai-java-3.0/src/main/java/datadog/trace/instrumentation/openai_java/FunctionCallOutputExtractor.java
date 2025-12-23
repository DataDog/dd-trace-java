package datadog.trace.instrumentation.openai_java;

import com.openai.models.responses.ResponseInputItem;
import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class to handle FunctionCallOutput.output() method changes between openai-java versions.
 *
 * <p>In version 3.x: output() returns String In version 4.0+: output() returns Output)
 */
public class FunctionCallOutputExtractor {
  private static final Logger log = LoggerFactory.getLogger(FunctionCallOutputExtractor.class);

  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(ResponseInputItem.FunctionCallOutput.class.getClassLoader());

  private static final MethodHandle OUTPUT_METHOD;
  private static final MethodHandle IS_STRING_METHOD;
  private static final MethodHandle AS_STRING_METHOD;

  static {
    OUTPUT_METHOD = METHOD_HANDLES.method(ResponseInputItem.FunctionCallOutput.class, "output");

    Class<?> outputClass = null;
    try {
      outputClass =
          ResponseInputItem.FunctionCallOutput.class
              .getClassLoader()
              .loadClass("com.openai.models.responses.ResponseInputItem$FunctionCallOutput$Output");
    } catch (ClassNotFoundException e) {
      // Output class not found, assuming openai-java version 3.x
    }

    if (outputClass != null) {
      IS_STRING_METHOD = METHOD_HANDLES.method(outputClass, "isString");
      AS_STRING_METHOD = METHOD_HANDLES.method(outputClass, "asString");
    } else {
      IS_STRING_METHOD = null;
      AS_STRING_METHOD = null;
    }
  }

  public static String getOutputAsString(ResponseInputItem.FunctionCallOutput functionCallOutput) {
    try {
      Object output = METHOD_HANDLES.invoke(OUTPUT_METHOD, functionCallOutput);

      if (output == null) {
        return null;
      }

      // In v3.x, output() returns String directly
      if (output instanceof String) {
        return (String) output;
      }

      // In v4.0+, output() returns an Output object
      if (IS_STRING_METHOD != null && AS_STRING_METHOD != null) {
        Boolean isString = METHOD_HANDLES.invoke(IS_STRING_METHOD, output);
        if (Boolean.TRUE.equals(isString)) {
          return METHOD_HANDLES.invoke(AS_STRING_METHOD, output);
        } else {
          log.debug("FunctionCallOutput.output() returned non-string Output type, skipping");
          return null;
        }
      }

      log.debug(
          "Unable to extract string from FunctionCallOutput.output(): unexpected return type {}",
          output.getClass().getName());
      return null;

    } catch (Exception e) {
      log.debug("Error extracting output from FunctionCallOutput", e);
      return null;
    }
  }
}
