package datadog.flare;

import java.io.IOException;

/**
 * MBean interface for managing and accessing tracer flare data.
 *
 * <p>This interface provides JMX operations to inspect flare data sources and generate flare data
 * either for individual sources or as a complete ZIP archive. Sources include both registered
 * reporters and built-in data (config, runtime, flare prelude, etc.).
 */
public interface TracerFlareManagerMBean {
  /**
   * Lists all available flare files from all sources.
   *
   * <p>Returns a newline-separated string where each line is formatted as "&lt;source&gt;
   * &lt;file&gt;". This format makes it easy to pass the source and filename to {@link
   * #getFlareFile(String, String)}.
   *
   * <p>Example output:
   *
   * <pre>
   * config initial_config.txt
   * ...
   * datadog.trace.agent.core.CoreTracer tracer_health.txt
   * ...
   * </pre>
   *
   * @return newline-separated string listing all available files and their source name
   * @throws IOException if an error occurs
   */
  String listFlareFiles() throws IOException;

  /**
   * Returns a specific flare file by source name and filename.
   *
   * <p>If the file is text, it is returned as plain text; if binary, it is returned base64-encoded.
   *
   * @param sourceName the name of the source (reporter class name or built-in source name)
   * @param filename the name of the file to retrieve
   * @return the file content (plain text or base64-encoded binary)
   * @throws IOException if an error occurs while generating or extracting the data
   */
  String getFlareFile(String sourceName, String filename) throws IOException;

  /**
   * Generates a complete tracer flare as a ZIP file.
   *
   * @return base64-encoded ZIP file containing the complete flare data
   * @throws IOException if an error occurs while generating the flare ZIP
   */
  String generateFullFlareZip() throws IOException;
}
