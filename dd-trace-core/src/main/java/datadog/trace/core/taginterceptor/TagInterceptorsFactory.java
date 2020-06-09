package datadog.trace.core.taginterceptor;

import datadog.trace.api.Config;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** Create DDSpanDecorators */
@Slf4j
public class TagInterceptorsFactory {
  public static List<AbstractTagInterceptor> createBuiltinDecorators() {

    final List<AbstractTagInterceptor> decorators = new ArrayList<>();

    for (final AbstractTagInterceptor decorator :
        Arrays.asList(
            new DBTypeTagInterceptor(),
            new ForceManualDropTagInterceptor(),
            new ForceManualKeepTagInterceptor(),
            new PeerServiceTagInterceptor(),
            new ServiceNameTagInterceptor(),
            new ServiceNameTagInterceptor("service", false),
            new ServletContextTagInterceptor())) {

      if (Config.get().isRuleEnabled(decorator.getClass().getSimpleName())) {
        decorators.add(decorator);
      } else {
        log.debug("{} disabled", decorator.getClass().getSimpleName());
      }
    }

    // SplitByTags purposely does not check for ServiceNameDecorator being enabled
    // This allows for ServiceNameDecorator to be disabled above while keeping SplitByTags
    // SplitByTags can be disable by removing SplitByTags config
    for (final String splitByTag : Config.get().getSplitByTags()) {
      decorators.add(new ServiceNameTagInterceptor(splitByTag, true));
    }

    return decorators;
  }
}
