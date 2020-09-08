package datadog.trace.core.taginterceptor;

import datadog.trace.api.Config;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TagInterceptorsFactory {
  public static List<AbstractTagInterceptor> createTagInterceptors() {

    final List<AbstractTagInterceptor> interceptors = new ArrayList<>();

    for (final AbstractTagInterceptor interceptor :
        Arrays.asList(
            new ForceManualDropTagInterceptor(),
            new ForceManualKeepTagInterceptor(),
            new ForceManualSamplerKeepTagInterceptor(),
            new ForceManualSamplerDropTagInterceptor(),
            new PeerServiceTagInterceptor(),
            new ServiceNameTagInterceptor(),
            new ServiceNameTagInterceptor("service", false),
            new ServletContextTagInterceptor())) {

      if (Config.get().isRuleEnabled(interceptor.getClass().getSimpleName())) {
        interceptors.add(interceptor);
      } else {
        log.debug("{} disabled", interceptor.getClass().getSimpleName());
      }
    }

    // SplitByTags purposely does not check for ServiceNameTagInterceptor being enabled
    // This allows for ServiceNameTagInterceptor to be disabled above while keeping SplitByTags
    // SplitByTags can be disable by removing SplitByTags config
    for (final String splitByTag : Config.get().getSplitByTags()) {
      interceptors.add(new ServiceNameTagInterceptor(splitByTag, true));
    }

    return interceptors;
  }
}
