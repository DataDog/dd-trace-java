package datadog.trace.core.tagprocessor;

import datadog.trace.api.Config;
import java.util.ArrayList;
import java.util.List;

public final class TagsPostProcessorFactory {
  private static boolean addBaseService = true;
  private static boolean addRemoteHostname = true;

  private static class Lazy {
    private static TagsPostProcessor create() {
      final List<TagsPostProcessor> processors = new ArrayList<>(7);
      processors.add(new PeerServiceCalculator());
      if (addBaseService) {
        processors.add(new BaseServiceAdder(Config.get().getServiceName()));
      }
      processors.add(new QueryObfuscator(Config.get().getObfuscationQueryRegexp()));
      if (addRemoteHostname) {
        processors.add(new RemoteHostnameAdder(Config.get().getHostNameSupplier()));
      }
      if (Config.get().isCloudPayloadTaggingEnabled()) {
        PayloadTagsProcessor ptp = PayloadTagsProcessor.create(Config.get());
        if (ptp != null) {
          processors.add(ptp);
        }
      }
      // today we only have aws as config key however we could have span pointers for different
      // integrations.
      // At that moment we should run the postprocessor for all the spans (and filter by component
      // to skip non-interesting ones)
      if (Config.get().isAddSpanPointers("aws")) {
        processors.add(new SpanPointersProcessor());
      }
      processors.add(new IntegrationAdder());
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

  /**
   * Mostly used for test purposes.
   *
   * @param enabled if false, {@link RemoteHostnameAdder} is not put in the chain.
   */
  public static void withAddRemoteHostname(boolean enabled) {
    addRemoteHostname = enabled;
    Lazy.instance = Lazy.create();
  }

  /** Used for testing purposes. It reset the singleton and restore default options */
  public static void reset() {
    withAddBaseService(true);
    withAddRemoteHostname(true);
  }
}
