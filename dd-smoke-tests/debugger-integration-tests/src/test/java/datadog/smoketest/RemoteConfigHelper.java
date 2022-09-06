package datadog.smoketest;

import datadog.trace.util.Strings;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public class RemoteConfigHelper {
  public static String encode(String config, String serviceName) {
    String hashStr = null;
    try {
      hashStr = Strings.sha256(config);
    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
    }
    String targetsStr =
        String.format(
            "{\"signed\":\n"
                + "  { \"_type\":\"targets\",\n"
                + "    \"spec_version\": \"1.0\",\n"
                + "    \"version\": \"2\",\n"
                + "    \"targets\":\n"
                + "     { \"datadog/2/LIVE_DEBUGGING/%s/config\":{"
                + "           \"length\": %d,\n"
                + "           \"hashes\":\n"
                + "            {\n"
                + "               \"sha256\": \"%s\"\n"
                + "            }"
                + "         }"
                + "     }"
                + "  }"
                + "}",
            serviceName, config.length(), hashStr);
    String targetsEncoding = new String(Base64.getEncoder().encode(targetsStr.getBytes()));
    String encodedConfig = new String(Base64.getEncoder().encode(config.getBytes()));
    return String.format(
        "{\n"
            + "\"targets\": \"%s\",\n"
            + "\"target_files\": [\n"
            + "    {\n"
            + "      \"path\": \"datadog/2/LIVE_DEBUGGING/%s/config\",\n"
            + "      \"raw\": \"%s\"\n"
            + "}],"
            + "\"client_configs\": [\n"
            + "                        \"datadog/2/LIVE_DEBUGGING/%s/config\"\n"
            + "                ]"
            + "}",
        targetsEncoding, serviceName, encodedConfig, serviceName);
  }
}
