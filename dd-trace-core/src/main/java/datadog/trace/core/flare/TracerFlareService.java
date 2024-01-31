package datadog.trace.core.flare;

import static datadog.trace.util.AgentThreadFactory.AgentThread.TRACER_FLARE;

import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.flare.TracerFlare;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDTraceCoreInfo;
import datadog.trace.logging.GlobalLogLevelSwitcher;
import datadog.trace.logging.LogLevel;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.AgentTaskScheduler.Scheduled;
import datadog.trace.util.Strings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipOutputStream;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TracerFlareService {
  private static final Logger log = LoggerFactory.getLogger(TracerFlareService.class);

  private static final String FLARE_ENDPOINT = "tracer_flare/v1";

  private static final String REPORT_PREFIX = "dd-java-flare-";

  private static final MediaType OCTET_STREAM = MediaType.get("application/octet-stream");

  private static final Pattern DELAY_TRIGGER = Pattern.compile("(\\d+)([HhMmSs]?)");

  private final AgentTaskScheduler scheduler = new AgentTaskScheduler(TRACER_FLARE);

  private final Config config;
  private final DynamicConfig<?> dynamicConfig;
  private final OkHttpClient okHttpClient;
  private final HttpUrl flareUrl;
  private final CoreTracer tracer;

  private boolean logLevelOverridden;

  private Scheduled<Runnable> scheduledCleanup;

  TracerFlareService(
      Config config,
      DynamicConfig<?> dynamicConfig,
      OkHttpClient okHttpClient,
      HttpUrl agentUrl,
      CoreTracer tracer) {
    this.config = config;
    this.dynamicConfig = dynamicConfig;
    this.okHttpClient = okHttpClient;
    this.flareUrl = agentUrl.newBuilder().addPathSegments(FLARE_ENDPOINT).build();
    this.tracer = tracer;

    applyTriageReportTrigger(config.getTriageReportTrigger());
  }

  private void applyTriageReportTrigger(String triageTrigger) {
    if (null != triageTrigger && !triageTrigger.isEmpty()) {
      Matcher delayMatcher = DELAY_TRIGGER.matcher(triageTrigger);
      if (delayMatcher.matches()) {
        long delay = Integer.parseInt(delayMatcher.group(1));
        String unit = delayMatcher.group(2);
        if ("H".equalsIgnoreCase(unit)) {
          delay = TimeUnit.HOURS.toSeconds(delay);
        } else if ("M".equalsIgnoreCase(unit)) {
          delay = TimeUnit.MINUTES.toSeconds(delay);
        } else {
          // already in seconds
        }
        scheduleTriageReport(delay);
      } else {
        log.info("Unrecognized triage trigger {}", triageTrigger);
      }
    }
  }

  private void scheduleTriageReport(long delayInSeconds) {
    Path triagePath = Paths.get(config.getTriageReportDir());
    // prepare at most 10 minutes before collection of the report, to match remote flare behaviour
    scheduler.schedule(() -> prepareForFlare("triage"), delayInSeconds - 600, TimeUnit.SECONDS);
    scheduler.schedule(
        () -> {
          try {
            if (!Files.isDirectory(triagePath)) {
              Files.createDirectories(triagePath);
            }
            Path reportPath = triagePath.resolve(getFlareName());
            log.info("Writing triage report to {}", reportPath);
            Files.write(reportPath, buildFlareZip(true));
          } catch (Throwable e) {
            log.info("Problem writing triage report", e);
          } finally {
            cleanupAfterFlare();
          }
        },
        delayInSeconds,
        TimeUnit.SECONDS);
  }

  public synchronized void prepareForFlare(String logLevel) {
    // allow turning on debug even part way through preparation
    if (!log.isDebugEnabled() && "debug".equalsIgnoreCase(logLevel)) {
      GlobalLogLevelSwitcher.get().switchLevel(LogLevel.DEBUG);
      logLevelOverridden = true;
    }
    Scheduled<Runnable> oldSchedule = scheduledCleanup;
    if (null != oldSchedule) {
      // already preparing, just cancel old schedule in favour of new one
      oldSchedule.cancel();
    } else {
      log.debug("Preparing for tracer flare, logLevel={}", logLevel);
      TracerFlare.prepareForFlare();
    }
    // always schedule fresh clean-up 20 minutes from last prepare request
    scheduledCleanup = scheduler.schedule(new CleanupTask(), 20, TimeUnit.MINUTES);
  }

  public void cleanupAfterFlare() {
    doCleanup(null);
  }

  synchronized void doCleanup(CleanupTask fromTask) {
    Scheduled<Runnable> oldSchedule = scheduledCleanup;
    if (null != oldSchedule) {
      if (null == fromTask) {
        // explicit clean-up request, cancel current schedule
        oldSchedule.cancel();
      } else if (oldSchedule.get() != fromTask) {
        // ignore clean-up requests from tasks which aren't currently scheduled
        return;
      }
      scheduledCleanup = null;
      log.debug("Cleaning up after tracer flare");
      TracerFlare.cleanupAfterFlare();
      if (logLevelOverridden) {
        GlobalLogLevelSwitcher.get().restore();
        logLevelOverridden = false;
      }
    }
  }

  public void sendFlare(String caseId, String email, String hostname) {
    boolean dumpThreads = config.isTriageEnabled() || log.isDebugEnabled();
    scheduler.execute(() -> doSend(caseId, email, hostname, dumpThreads));
  }

  void doSend(String caseId, String email, String hostname, boolean dumpThreads) {
    log.debug("Sending tracer flare");
    try {
      RequestBody report = RequestBody.create(OCTET_STREAM, buildFlareZip(dumpThreads));

      RequestBody form =
          new MultipartBody.Builder()
              .setType(MultipartBody.FORM)
              .addFormDataPart("source", "tracer_java")
              .addFormDataPart("case_id", caseId)
              .addFormDataPart("email", email)
              .addFormDataPart("hostname", hostname)
              .addFormDataPart("flare_file", getFlareName(), report)
              .build();

      Request flareRequest =
          OkHttpUtils.prepareRequest(flareUrl, Collections.emptyMap()).post(form).build();

      try (Response response = okHttpClient.newCall(flareRequest).execute()) {
        if (response.code() == 404) {
          log.debug("Tracer flare endpoint is disabled, ignoring request");
        } else if (!response.isSuccessful()) {
          log.warn("Tracer flare failed with: {} {}", response.code(), response.message());
        } else {
          log.debug("Tracer flare sent successfully");
        }
      }

    } catch (IOException e) {
      log.warn("Tracer flare failed with exception: {}", e.toString());
    }
  }

  private String getFlareName() {
    return REPORT_PREFIX + config.getRuntimeId() + "-" + System.currentTimeMillis() + ".zip";
  }

  private byte[] buildFlareZip(boolean dumpThreads) throws IOException {
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes)) {

      addConfig(zip);
      addRuntime(zip);
      tracer.addTracerReportToFlare(zip);
      TracerFlare.addReportsToFlare(zip);
      if (dumpThreads) {
        addThreadDump(zip);
      }
      zip.finish();

      return bytes.toByteArray();
    }
  }

  private void addConfig(ZipOutputStream zip) throws IOException {
    TracerFlare.addText(zip, "tracer_version.txt", DDTraceCoreInfo.VERSION);
    TracerFlare.addText(zip, "initial_config.txt", config.toString());
    TracerFlare.addText(zip, "dynamic_config.txt", dynamicConfig.toString());
  }

  private void addRuntime(ZipOutputStream zip) throws IOException {
    try {
      RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
      TracerFlare.addText(
          zip, "jvm_args.txt", Strings.join(" ", runtimeMXBean.getInputArguments()));
      TracerFlare.addText(zip, "classpath.txt", runtimeMXBean.getClassPath());
      TracerFlare.addText(zip, "library_path.txt", runtimeMXBean.getLibraryPath());
      if (runtimeMXBean.isBootClassPathSupported()) {
        TracerFlare.addText(zip, "boot_classpath.txt", runtimeMXBean.getBootClassPath());
      }
    } catch (RuntimeException e) {
      TracerFlare.addText(zip, "classpath.txt", System.getProperty("java.class.path"));
    }
  }

  private void addThreadDump(ZipOutputStream zip) throws IOException {
    StringBuilder buf = new StringBuilder();
    try {
      ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
      for (ThreadInfo threadInfo :
          threadMXBean.dumpAllThreads(
              threadMXBean.isObjectMonitorUsageSupported(),
              threadMXBean.isSynchronizerUsageSupported())) {
        buf.append(threadInfo);
      }
    } catch (RuntimeException e) {
      buf.append("Problem collecting thread dump: ").append(e);
    }
    TracerFlare.addText(zip, "threads.txt", buf.toString());
  }

  final class CleanupTask implements Runnable {
    @Override
    public void run() {
      doCleanup(this);
    }
  }
}
