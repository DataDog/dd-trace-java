package datadog.smoketest;

import datadog.trace.util.Strings;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class RemoteConfigHelper {
  public static class RemoteConfig {
    public final String product;
    public final String config;
    public final String configId;

    public RemoteConfig(String product, String config, String configId) {
      this.product = product;
      this.config = config;
      this.configId = configId;
    }
  }

  public static String encode(List<RemoteConfig> remoteConfigs) {
    List<String> hashes = buildHashes(remoteConfigs);
    String targetsStr = buildTargets(hashes, remoteConfigs);
    String targetsEncoded = new String(Base64.getEncoder().encode(targetsStr.getBytes()));
    String targetFiles = buildTargetFiles(remoteConfigs);
    String clientConfigs = buildClientConfigs(remoteConfigs);
    return String.format(
        "{\n"
            + "\"targets\": \"%s\",\n"
            + "\"target_files\": [\n"
            + "                    %s\n"
            + "],"
            + "\"client_configs\": [\n"
            + "                      %s\n"
            + "                ]"
            + "}",
        targetsEncoded, targetFiles, clientConfigs);
  }

  private static String buildClientConfigs(List<RemoteConfig> remoteConfigs) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < remoteConfigs.size(); i++) {
      RemoteConfig rc = remoteConfigs.get(i);
      if (i > 0) {
        sb.append(",\n");
      }
      sb.append(
          String.format(
              "                        \"datadog/2/%s/%s/config\"\n", rc.product, rc.configId));
    }
    return sb.toString();
  }

  private static String buildTargetFiles(List<RemoteConfig> remoteConfigs) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < remoteConfigs.size(); i++) {
      RemoteConfig rc = remoteConfigs.get(i);
      String encodedConfig = new String(Base64.getEncoder().encode(rc.config.getBytes()));
      if (i > 0) {
        sb.append(",\n");
      }
      sb.append(
          String.format(
              "    {\n"
                  + "      \"path\": \"datadog/2/%s/%s/config\",\n"
                  + "      \"raw\": \"%s\"\n"
                  + "    }\n",
              rc.product, rc.configId, encodedConfig));
    }
    return sb.toString();
  }

  private static List<String> buildHashes(List<RemoteConfig> remoteConfigs) {
    List<String> hashes = new ArrayList<>();
    for (RemoteConfig rc : remoteConfigs) {
      try {
        hashes.add(Strings.sha256(rc.config));
      } catch (NoSuchAlgorithmException e) {
        e.printStackTrace();
      }
    }
    return hashes;
  }

  private static String buildTargets(List<String> hashes, List<RemoteConfig> remoteConfigs) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < remoteConfigs.size(); i++) {
      RemoteConfig rc = remoteConfigs.get(i);
      if (i > 0) {
        sb.append(",\n");
      }
      sb.append(
          String.format(
              "     \"datadog/2/%s/%s/config\":{"
                  + "           \"length\": %d,\n"
                  + "           \"custom\": { \"v\": 123 },\n"
                  + "           \"hashes\":\n"
                  + "            {\n"
                  + "               \"sha256\": \"%s\"\n"
                  + "            }"
                  + "         }",
              rc.product, rc.configId, rc.config.length(), hashes.get(i)));
    }
    String targets = sb.toString();
    return String.format(
        "{\"signed\":\n"
            + "  { \"_type\":\"targets\",\n"
            + "    \"spec_version\": \"1.0\",\n"
            + "    \"version\": \"2\",\n"
            + "    \"custom\": { \"opaque_backend_state\": \"opaque\" },\n"
            + "    \"targets\":\n"
            + "     { %s }"
            + "  }"
            + "}",
        targets);
  }
}
