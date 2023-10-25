package datadog.trace.core.tagprocessor;

import datadog.trace.api.Config;
import java.util.ArrayList;
import java.util.List;

public final class TagsPostProcessorFactory {
  private static boolean addBaseService = true;

  private static class Lazy {
    private static TagsPostProcessor create() {
      final List<TagsPostProcessor> processors = new ArrayList<>(addBaseService ? 3 : 2);
      processors.add(new PeerServiceCalculator());
      if (addBaseService) {
        processors.add(new BaseServiceAdder(Config.get().getServiceName()));
      }
      processors.add(new QueryObfuscator(Config.get().getObfuscationQueryRegexp()));
      return new PostProcessorChain(
          processors.toArray(processors.toArray(new TagsPostProcessor[0])));
    }

    private static TagsPostProcessor instance = create();
  }

  public static TagsPostProcessor instance() {
    return Lazy.instance;
  }

  /**
   * Mostly used for test purposes.
   *
   * @param enabled if false, {@link BaseServiceAdder} is not put in the chain.
   */
  public static void withAddBaseService(boolean enabled) {
    addBaseService = enabled;
    Lazy.instance = Lazy.create();
  }

  /** Used for testing purposes. It reset the singleton and restore default options */
  public static void reset() {
    withAddBaseService(true);
    Lazy.instance = Lazy.create();
  }
}
