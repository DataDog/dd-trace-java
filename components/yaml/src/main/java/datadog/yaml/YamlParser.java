package datadog.yaml;

import datadog.cli.CLIHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.yaml.snakeyaml.Yaml;

public class YamlParser {
  // Supports clazz == null for default yaml parsing
  public static <T> T parse(String filePath, Class<T> clazz) throws IOException {
    Yaml yaml = new Yaml();
    String content = new String(Files.readAllBytes(Paths.get(filePath)));
    String processedContent = processTemplate(content);

    if (clazz == null) {
      return yaml.load(processedContent);
    } else {
      return yaml.loadAs(processedContent, clazz);
    }
  }

  /**
   * Processes a YAML template by replacing all template variables with their corresponding values.
   * Template variables are enclosed in double curly braces, e.g. {{variable}}. Returns the
   * processed content with all template variables resolved.
   */
  static String processTemplate(String content) throws IOException {
    StringBuilder result = new StringBuilder(content.length());
    String rest = content;

    while (true) {
      int openIndex = rest.indexOf("{{");
      if (openIndex == -1) {
        result.append(rest);
        break;
      }

      // Add everything before the template
      result.append(rest.substring(0, openIndex));

      // Find the closing braces
      int closeIndex = rest.indexOf("}}", openIndex);
      if (closeIndex == -1) {
        throw new IOException("Unterminated template in config");
      }

      // Extract the template variable
      String templateVar = rest.substring(openIndex + 2, closeIndex).trim();

      // Process the template variable and get its value
      String value = processTemplateVar(templateVar);

      // Add the processed value
      result.append(value);

      // Continue with the rest of the string
      rest = rest.substring(closeIndex + 2);
    }

    return result.toString();
  }

  /**
   * Processes a template variable by extracting its value from either environment variables or VM
   * arguments. Template variables should be in the format "{{environment_variables[VAR_NAME]}}" or
   * "{{process_arguments[-ARG_NAME]}}". Returns "UNDEFINED" if the variable is not found or empty.
   */
  static String processTemplateVar(String templateVar) throws IOException {
    if (templateVar.startsWith("environment_variables['") && templateVar.endsWith("']")) {
      String envVar =
          templateVar
              .substring("environment_variables['".length(), templateVar.length() - 2)
              .trim();
      if (envVar.isEmpty()) {
        throw new IOException("Empty environment variable name in template");
      }
      String value = System.getenv(envVar.toUpperCase());
      if (value == null || value.isEmpty()) {
        return "UNDEFINED";
      }
      return value;
    } else if (templateVar.startsWith("process_arguments['") && templateVar.endsWith("']")) {
      String processArg =
          templateVar.substring("process_arguments['".length(), templateVar.length() - 2).trim();
      if (processArg.isEmpty()) {
        throw new IOException("Empty process argument in template");
      }
      String value = CLIHelper.getArgValue(processArg);
      if (value == null || value.isEmpty()) {
        return "UNDEFINED";
      }
      return value;
    }
    return "UNDEFINED";
  }
}
