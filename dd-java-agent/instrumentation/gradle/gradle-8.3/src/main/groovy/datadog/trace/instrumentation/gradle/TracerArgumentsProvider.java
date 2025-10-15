package datadog.trace.instrumentation.gradle;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.provider.Property;
import org.gradle.api.services.ServiceReference;
import org.gradle.process.CommandLineArgumentProvider;

public abstract class TracerArgumentsProvider implements CommandLineArgumentProvider {
  private static final Pattern PROJECT_PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)\\}");

  private final String taskPath;
  private final Map<String, String> projectProperties;

  @Inject
  public TracerArgumentsProvider(String taskPath, Map<String, String> projectProperties) {
    this.taskPath = taskPath;
    this.projectProperties = projectProperties;
  }

  @ServiceReference
  abstract Property<CiVisibilityService> getCiVisibilityService();

  @Override
  public Iterable<String> asArguments() {
    Collection<String> tracerJvmArgs = getCiVisibilityService().get().getTracerJvmArgs(taskPath);
    return tracerJvmArgs.stream().map(this::replaceProjectProperties).collect(Collectors.toList());
  }

  private String replaceProjectProperties(String s) {
    StringBuffer output = new StringBuffer();
    Matcher matcher = PROJECT_PROPERTY_REFERENCE.matcher(s);
    while (matcher.find()) {
      String propertyName = matcher.group(1);
      Object propertyValue = projectProperties.get(propertyName);
      matcher.appendReplacement(output, Matcher.quoteReplacement(String.valueOf(propertyValue)));
    }
    matcher.appendTail(output);
    return output.toString();
  }
}
