package datadog.flare;

import datadog.trace.api.Config;
import datadog.trace.api.flare.TracerFlare;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MBean implementation for managing and accessing tracer flare data.
 *
 * <p>This class provides JMX operations to list flare data sources and retrieve flare data either
 * for individual sources or a complete flare archive. See {@link TracerFlareManagerMBean} for
 * documentation on the exposed operations.
 */
public class TracerFlareManager implements TracerFlareManagerMBean {
  private static final Logger LOGGER = LoggerFactory.getLogger(TracerFlareManager.class);

  private final TracerFlareService flareService;
  protected ObjectName mbeanName;

  public TracerFlareManager(TracerFlareService flareService) {
    this.flareService = flareService;
  }

  @Override
  public String generateFullFlareZip() throws IOException {
    TracerFlare.prepareForFlare();

    long currentMillis = System.currentTimeMillis();
    boolean dumpThreads = Config.get().isTriageEnabled() || LOGGER.isDebugEnabled();
    byte[] zipBytes = flareService.buildFlareZip(currentMillis, currentMillis, dumpThreads);
    return Base64.getEncoder().encodeToString(zipBytes);
  }

  @Override
  public String listFlareFiles() throws IOException {
    TracerFlare.prepareForFlare();

    StringBuilder result = new StringBuilder();

    for (Map.Entry<String, String[]> entry : TracerFlareService.BUILT_IN_SOURCES.entrySet()) {
      String sourceName = entry.getKey();
      String[] files = entry.getValue();

      for (String filename : files) {
        result.append(sourceName).append(" ").append(filename).append("\n");
      }
    }

    for (TracerFlare.Reporter reporter : TracerFlare.getReporters()) {
      try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          ZipOutputStream zip = new ZipOutputStream(bytes)) {
        reporter.addReportToFlare(zip);
        zip.finish();

        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes.toByteArray());
            ZipInputStream zis = new ZipInputStream(bais)) {
          ZipEntry entry;
          while ((entry = zis.getNextEntry()) != null) {
            result
                .append(reporter.getClass().getName())
                .append(" ")
                .append(entry.getName())
                .append("\n");
            zis.closeEntry();
          }
        }
      } catch (IOException e) {
        LOGGER.debug("Failed to inspect reporter {}", reporter.getClass().getName(), e);
      }
    }

    return result.toString();
  }

  @Override
  public String getFlareFile(String sourceName, String filename) throws IOException {
    final byte[] zipBytes;
    if (isBuiltInSource(sourceName)) {
      zipBytes = flareService.getBuiltInSourceZip(sourceName);
    } else {
      zipBytes = getReporterFile(sourceName);
    }
    return extractFileFromZip(zipBytes, filename);
  }

  private boolean isBuiltInSource(String sourceName) {
    return TracerFlareService.BUILT_IN_SOURCES.containsKey(sourceName);
  }

  /**
   * Generates flare data for a specific reporter.
   *
   * <p>The reporter's data is generated as a ZIP file, and the specified filename is extracted. If
   * the file is text, it is returned as plain text; if binary, it is returned base64-encoded.
   *
   * @param reporterClassName the fully qualified class name of the reporter
   * @return the zip file containing the reporter's content
   * @throws IOException if an error occurs while generating the flare
   */
  private byte[] getReporterFile(String reporterClassName) throws IOException {
    TracerFlare.Reporter reporter = TracerFlare.getReporter(reporterClassName);
    if (reporter == null) {
      throw new IOException("Error: Reporter not found: " + reporterClassName);
    }

    reporter.prepareForFlare();

    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes)) {
      reporter.addReportToFlare(zip);
      zip.finish();
      return bytes.toByteArray();
    }
  }

  /**
   * Extracts a specific file from a ZIP archive.
   *
   * <p>Searches through the ZIP entries for the specified filename and returns its content. If the
   * file name ends in ".txt", it is returned as plain text; if binary, it is returned
   * base64-encoded.
   *
   * @param zipBytes the ZIP file bytes
   * @param filename the name of the file to extract
   * @return the file content (plain text or base64-encoded binary)
   * @throws IOException if an error occurs while reading the ZIP
   */
  private String extractFileFromZip(byte[] zipBytes, String filename) throws IOException {
    try (ByteArrayInputStream bais = new ByteArrayInputStream(zipBytes);
        ZipInputStream zis = new ZipInputStream(bais)) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (entry.getName().equals(filename)) {
          ByteArrayOutputStream content = new ByteArrayOutputStream();
          byte[] buffer = new byte[8192];
          int bytesRead;
          while ((bytesRead = zis.read(buffer)) != -1) {
            content.write(buffer, 0, bytesRead);
          }
          zis.closeEntry();

          byte[] contentBytes = content.toByteArray();
          if (entry.getName().endsWith(".txt")) {
            return new String(contentBytes, StandardCharsets.UTF_8);
          } else {
            return Base64.getEncoder().encodeToString(contentBytes);
          }
        }
        zis.closeEntry();
      }

      throw new IOException("Failed to extract file: " + filename);
    }
  }

  void registerMBean() {
    MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();

    try {
      mbeanName = new ObjectName("datadog.flare:type=TracerFlare");
      mbs.registerMBean(this, mbeanName);
      LOGGER.info("Registered TracerFlare MBean at {}", mbeanName);
    } catch (MalformedObjectNameException
        | InstanceAlreadyExistsException
        | MBeanRegistrationException
        | NotCompliantMBeanException e) {
      LOGGER.warn("Failed to register TracerFlare MBean", e);
      mbeanName = null;
    }
  }

  void unregisterMBean() {
    if (mbeanName != null) {
      MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
      try {
        mbs.unregisterMBean(mbeanName);
        LOGGER.debug("Unregistered TracerFlare MBean");
      } catch (Exception e) {
        LOGGER.warn("Failed to unregister TracerFlare MBean", e);
      }
    }
  }
}
