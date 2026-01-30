package datadog.flare;

import static datadog.common.version.VersionInfo.VERSION;
import static datadog.http.client.HttpRequest.CONTENT_TYPE;
import static datadog.trace.util.AgentThreadFactory.AgentThread.TRACER_FLARE;

import datadog.communication.http.HttpUtils;
import datadog.http.client.HttpClient;
import datadog.http.client.HttpRequest;
import datadog.http.client.HttpRequestBody;
import datadog.http.client.HttpResponse;
import datadog.http.client.HttpUrl;
import datadog.trace.api.Config;
import datadog.trace.api.flare.TracerFlare;
import datadog.trace.api.time.TimeUtils;
import datadog.trace.logging.GlobalLogLevelSwitcher;
import datadog.trace.logging.LogLevel;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.AgentTaskScheduler.Scheduled;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TracerFlareService {
  private static final Logger log = LoggerFactory.getLogger(TracerFlareService.class);

  private static final String FLARE_ENDPOINT = "tracer_flare/v1";

  private static final String REPORT_PREFIX = "dd-java-flare-";

  private static final String OCTET_STREAM = "application/octet-stream";

  private static final int MAX_LOGFILE_SIZE_MB = 15;

  private static final int MAX_LOGFILE_SIZE_BYTES = MAX_LOGFILE_SIZE_MB << 20;

  private final AgentTaskScheduler scheduler = new AgentTaskScheduler(TRACER_FLARE);

  private final Config config;
  private final HttpClient httpClient;
  private final HttpUrl flareUrl;

  private boolean logLevelOverridden;
  private volatile long flareStartMillis;

  private Scheduled<Runnable> scheduledCleanup;

  TracerFlareService(Config config, HttpClient httpClient, HttpUrl agentUrl) {
    this.config = config;
    this.httpClient = httpClient;
    this.flareUrl = agentUrl.newBuilder().addPathSegment(FLARE_ENDPOINT).build();

    applyTriageReportTrigger(config.getTriageReportTrigger());
  }

  private void applyTriageReportTrigger(String triageTrigger) {
    if (null != triageTrigger && !triageTrigger.isEmpty()) {
      long delay = TimeUtils.parseSimpleDelay(triageTrigger);
      if (delay < 0) {
        log.info("Unrecognized triage trigger {}", triageTrigger);
      } else {
        scheduleTriageReport(delay);
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
            long flareEndMillis = System.currentTimeMillis();
            Path reportPath = triagePath.resolve(getFlareName(flareEndMillis));
            log.info("Writing triage report to {}", reportPath);
            Files.write(reportPath, buildFlareZip(flareStartMillis, flareEndMillis, true));
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
      flareStartMillis = System.currentTimeMillis();
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
      flareStartMillis = 0;
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
      long flareEndMillis = System.currentTimeMillis();

      HttpRequestBody report =
          HttpRequestBody.of(buildFlareZip(flareStartMillis, flareEndMillis, dumpThreads));

      HttpRequestBody.MultipartBuilder multipartBuilder = HttpRequestBody.multipart();
      multipartBuilder.addFormDataPart("source", "tracer_java");
      multipartBuilder.addFormDataPart("case_id", caseId);
      multipartBuilder.addFormDataPart("email", email);
      multipartBuilder.addFormDataPart("hostname", hostname);
      multipartBuilder.addFormDataPart("flare_file", getFlareName(flareEndMillis), report);
      HttpRequestBody form = multipartBuilder.build();

      HttpRequest flareRequest =
          HttpUtils.prepareRequest(flareUrl, Collections.emptyMap())
              .header(CONTENT_TYPE, OCTET_STREAM)
              .post(form)
              .build();

      try (HttpResponse response = httpClient.execute(flareRequest)) {
        if (response.code() == 404) {
          log.debug("Tracer flare endpoint is disabled, ignoring request");
        } else if (!response.isSuccessful()) {
          log.warn("Tracer flare failed with: {}", response.code());
        } else {
          log.debug("Tracer flare sent successfully");
        }
      }

    } catch (IOException e) {
      log.warn("Tracer flare failed with exception: {}", e.toString());
    }
  }

  private String getFlareName(long endMillis) {
    return REPORT_PREFIX + config.getRuntimeId() + "-" + endMillis + ".zip";
  }

  private byte[] buildFlareZip(long startMillis, long endMillis, boolean dumpThreads)
      throws IOException {
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes)) {

      addPrelude(zip, startMillis, endMillis);
      addConfig(zip);
      addRuntime(zip);
      TracerFlare.addReportsToFlare(zip);
      if (dumpThreads) {
        addThreadDump(zip);
      }
      zip.finish();

      return bytes.toByteArray();
    }
  }

  private void addPrelude(ZipOutputStream zip, long startMillis, long endMillis)
      throws IOException {
    TracerFlare.addText(zip, "flare_info.txt", flareInfo(startMillis, endMillis));
    TracerFlare.addText(zip, "tracer_version.txt", VERSION);
  }

  private void addConfig(ZipOutputStream zip) throws IOException {
    TracerFlare.addText(zip, "initial_config.txt", config.toString());
  }

  private void addRuntime(ZipOutputStream zip) throws IOException {
    try {
      RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
      TracerFlare.addText(zip, "jvm_args.txt", String.join(" ", runtimeMXBean.getInputArguments()));
      TracerFlare.addText(zip, "classpath.txt", runtimeMXBean.getClassPath());
      TracerFlare.addText(zip, "library_path.txt", runtimeMXBean.getLibraryPath());
      if (runtimeMXBean.isBootClassPathSupported()) {
        TracerFlare.addText(zip, "boot_classpath.txt", runtimeMXBean.getBootClassPath());
      }
    } catch (RuntimeException e) {
      TracerFlare.addText(zip, "classpath.txt", System.getProperty("java.class.path"));
    }
  }

  private String flareInfo(long startMillis, long endMillis) {
    StringBuilder buf = new StringBuilder();
    if (startMillis > 0) {
      buf.append("Requested: ").append(toDateTime(startMillis)).append('\n');
    }
    return buf.append("Completed: ").append(toDateTime(endMillis)).toString();
  }

  private static ZonedDateTime toDateTime(long millis) {
    return ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
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
