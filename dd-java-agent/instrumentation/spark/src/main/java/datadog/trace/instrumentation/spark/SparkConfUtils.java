package datadog.trace.instrumentation.spark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.trace.api.Config;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import org.apache.spark.SparkConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SparkConfUtils {
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final Logger log = LoggerFactory.getLogger(SparkConfUtils.class);

  public static boolean getIsRunningOnDatabricks(SparkConf sparkConf) {
    return sparkConf.contains("spark.databricks.sparkContextId");
  }

  public static String getDatabricksClusterName(SparkConf sparkConf) {
    return sparkConf.get("spark.databricks.clusterUsageTags.clusterName", null);
  }

  public static String getDatabricksServiceName(SparkConf conf, String databricksClusterName) {
    if (Config.get().isServiceNameSetByUser()) {
      return null;
    }

    String serviceName = null;
    String runName = getDatabricksRunName(conf);
    if (runName != null) {
      serviceName = "databricks.job-cluster." + runName;
    } else if (databricksClusterName != null) {
      serviceName = "databricks.all-purpose-cluster." + databricksClusterName;
    }

    return serviceName;
  }

  public static String getSparkServiceName(SparkConf conf, boolean isRunningOnDatabricks) {
    // If config is not set or running on databricks, not changing the service name
    if (!Config.get().useSparkAppNameAsService() || isRunningOnDatabricks) {
      return null;
    }

    // Keep service set by user, except if it is only "spark" or "hadoop" that can be set by USM
    String serviceName = Config.get().getServiceName();
    if (Config.get().isServiceNameSetByUser()
        && !"spark".equals(serviceName)
        && !"hadoop".equals(serviceName)) {
      log.debug("Service '{}' explicitly set by user, not using the application name", serviceName);
      return null;
    }

    String sparkAppName = conf.get("spark.app.name", null);
    if (sparkAppName != null) {
      log.info("Using Spark application name '{}' as the Datadog service name", sparkAppName);
    }

    return sparkAppName;
  }

  public static String getServiceNameOverride(SparkConf conf) {
    boolean isRunningOnDatabricks = getIsRunningOnDatabricks(conf);
    String databricksClusterName = getDatabricksClusterName(conf);
    String databricksServiceName = getDatabricksServiceName(conf, databricksClusterName);
    String sparkServiceName = getSparkServiceName(conf, isRunningOnDatabricks);

    return databricksServiceName != null ? databricksServiceName : sparkServiceName;
  }

  private static String getDatabricksRunName(SparkConf conf) {
    String allTags = conf.get("spark.databricks.clusterUsageTags.clusterAllTags", null);
    System.out.println("### AllTags: " + allTags);
    if (allTags == null) {
      return null;
    }

    try {
      // Using the jackson JSON lib used by spark
      // https://mvnrepository.com/artifact/org.apache.spark/spark-core_2.12/3.5.0
      JsonNode jsonNode = objectMapper.readTree(allTags);

      for (JsonNode node : jsonNode) {
        String key = node.get("key").asText();
        System.out.println("### Key node: " + key + ", value: " + node.get("value").asText());
        if ("RunName".equals(key)) {
          System.out.println("### Key value: " + node.get("value").asText());
          // Databricks jobs launched by Azure Data Factory have an uuid at the end of the name
          return removeUuidFromEndOfString(node.get("value").asText());
        }
      }
    } catch (Exception e) {
      System.out.println("### Failed to parse databricks run tags - " + e.getMessage());
    }

    return null;
  }

  @SuppressForbidden // called at most once per spark application
  private static String removeUuidFromEndOfString(String input) {
    return input.replaceAll(
        "_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", "");
  }
}
