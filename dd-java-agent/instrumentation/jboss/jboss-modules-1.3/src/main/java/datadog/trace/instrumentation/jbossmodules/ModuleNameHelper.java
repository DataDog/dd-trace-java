package datadog.trace.instrumentation.jbossmodules;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import org.jboss.modules.ModuleClassLoader;

public class ModuleNameHelper {
  private ModuleNameHelper() {}

  private static final Pattern SUBDEPLOYMENT_MATCH =
      Pattern.compile("deployment(?>.+\\.ear)?\\.(.+)\\.[j|w]ar");

  public static String extractDeploymentName(@Nonnull final ModuleClassLoader classLoader) {
    final Matcher matcher =
        SUBDEPLOYMENT_MATCH.matcher(classLoader.getModule().getIdentifier().getName());
    if (matcher.matches()) {
      return matcher.group(1);
    }
    return null;
  }
}
