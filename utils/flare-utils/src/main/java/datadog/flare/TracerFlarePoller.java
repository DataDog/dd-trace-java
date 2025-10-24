package datadog.flare;

import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.state.ConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import okio.Okio;

public final class TracerFlarePoller {
  private static final String FLARE_LOG_LEVEL = "flare-log-level";

  private Runnable stopPreparer;
  private Runnable stopSubmitter;

  private TracerFlareService tracerFlareService;

  private final Map<String, String> configAction = new HashMap<>();
  private static TracerFlarePoller INSTANCE;

  public static void start(SharedCommunicationObjects sco) {
    if (null == INSTANCE) {
      INSTANCE = new TracerFlarePoller();
    }
    INSTANCE.doStart(sco);
  }

  public static void stop() {
    if (null != INSTANCE) {
      INSTANCE.doStop();
    }
  }

  private void doStart(SharedCommunicationObjects sco) {
    Config config = Config.get();
    stopPreparer = new Preparer().register(config, sco);
    stopSubmitter = new Submitter().register(config, sco);
    tracerFlareService = new TracerFlareService(config, sco.agentHttpClient, sco.agentUrl);
  }

  private void doStop() {
    if (null != stopPreparer) {
      stopPreparer.run();
    }
    if (null != stopSubmitter) {
      stopSubmitter.run();
    }
    if (null != tracerFlareService) {
      tracerFlareService.close();
    }
  }

  final class Preparer implements ProductListener {
    private final JsonAdapter<AgentConfigLayer> AGENT_CONFIG_LAYER_ADAPTER;

    {
      Moshi MOSHI = new Moshi.Builder().build();
      AGENT_CONFIG_LAYER_ADAPTER = MOSHI.adapter(AgentConfigLayer.class);
    }

    public Runnable register(Config config, SharedCommunicationObjects sco) {
      ConfigurationPoller poller = sco.configurationPoller(config);
      if (null != poller) {
        poller.addListener(Product.AGENT_CONFIG, this);
        return poller::stop;
      } else {
        return null;
      }
    }

    @Override
    public void accept(ConfigKey configKey, byte[] content, PollingRateHinter hinter)
        throws IOException {
      AgentConfigLayer agentConfigLayer =
          AGENT_CONFIG_LAYER_ADAPTER.fromJson(
              Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
      if (null != agentConfigLayer
          && null != agentConfigLayer.config
          && null != agentConfigLayer.config.logLevel) {
        configAction.put(configKey.getConfigId(), FLARE_LOG_LEVEL);
        prepareForFlare(agentConfigLayer.config.logLevel);
      } else {
        cleanupAfterFlare();
      }
    }

    @Override
    public void remove(ConfigKey configKey, PollingRateHinter hinter) {
      if (configAction.remove(configKey.getConfigId(), FLARE_LOG_LEVEL)) {
        cleanupAfterFlare();
      }
    }

    @Override
    public void commit(PollingRateHinter hinter) {}
  }

  final class Submitter implements ProductListener {
    private final JsonAdapter<AgentTask> AGENT_TASK_ADAPTER;

    {
      Moshi MOSHI = new Moshi.Builder().build();
      AGENT_TASK_ADAPTER = MOSHI.adapter(AgentTask.class);
    }

    public Runnable register(Config config, SharedCommunicationObjects sco) {
      ConfigurationPoller poller = sco.configurationPoller(config);
      if (null != poller) {
        poller.addListener(Product.AGENT_TASK, this);
        return poller::stop;
      } else {
        return null;
      }
    }

    @Override
    public void accept(ConfigKey configKey, byte[] content, PollingRateHinter hinter)
        throws IOException {
      AgentTask agentTask =
          AGENT_TASK_ADAPTER.fromJson(Okio.buffer(Okio.source(new ByteArrayInputStream(content))));

      if (null != agentTask
          && null != agentTask.args
          && "tracer_flare".equals(agentTask.taskType)) {
        sendFlare(agentTask.args);
      }
    }

    @Override
    public void remove(ConfigKey configKey, PollingRateHinter hinter) {}

    @Override
    public void commit(PollingRateHinter hinter) {}
  }

  void prepareForFlare(String logLevel) {
    tracerFlareService.prepareForFlare(logLevel);
  }

  void cleanupAfterFlare() {
    tracerFlareService.cleanupAfterFlare();
  }

  void sendFlare(AgentTaskArgs args) {
    tracerFlareService.sendFlare(args.caseId, args.userHandle, args.hostname);
  }

  static final class AgentConfigLayer {
    @Json(name = "name")
    public String name;

    @Json(name = "config")
    public AgentConfig config;
  }

  static final class AgentConfig {
    @Json(name = "log_level")
    public String logLevel;
  }

  static final class AgentTask {
    @Json(name = "task_type")
    public String taskType;

    @Json(name = "args")
    public AgentTaskArgs args;
  }

  static final class AgentTaskArgs {
    @Json(name = "case_id")
    public String caseId;

    @Json(name = "user_handle")
    public String userHandle;

    @Json(name = "hostname")
    public String hostname;
  }
}
