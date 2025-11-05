package datadog.trace.instrumentation.gradle;

import java.util.ArrayList;
import java.util.List;
import org.gradle.process.CommandLineArgumentProvider;

public class JavaCompilerPluginArgumentsProvider implements CommandLineArgumentProvider {
  private final String moduleName;

  public JavaCompilerPluginArgumentsProvider(String moduleName) {
    this.moduleName = moduleName;
  }

  @Override
  public Iterable<String> asArguments() {
    List<String> arguments = new ArrayList<>();
    arguments.add("-Xplugin:DatadogCompilerPlugin");

    // disable compiler warnings related to annotation processing,
    // since "fail-on-warning" linters might complain about the annotation that the compiler plugin
    // injects
    arguments.add("-Xlint:-processing");

    if (moduleName != null) {
      arguments.add("--add-reads");
      arguments.add(moduleName + "=ALL-UNNAMED");
    }

    return arguments;
  }
}
