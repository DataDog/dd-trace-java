package datadog.trace.instrumentation.jbossmodules;

import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.modules.ModuleClassLoader;

public class ModuleNameHelper {
  private ModuleNameHelper() {}

  private static final Pattern SUBDEPLOYMENT_MATCH =
      Pattern.compile("deployment(?>.+\\.ear)?\\.(.+)\\.[j|w]ar");
  public static final Function<ClassLoader, Supplier<String>> NAME_EXTRACTOR =
      classLoader -> {
        final Matcher matcher =
            SUBDEPLOYMENT_MATCH.matcher(
                ((ModuleClassLoader) classLoader).getModule().getIdentifier().getName());
        if (matcher.matches()) {
          final String result = matcher.group(1);
          return () -> result;
        }
        return () -> null;
      };
}
