package datadog.trace.instrumentation.couchbase_31.client;

import com.couchbase.client.core.env.SeedNode;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ClusterHelper {

  public static final String PARAM_INFERRED_PEER_SERVICE = "ddTracing.peerService";
  private static final Pattern REPLACER =
      Pattern.compile("(?<=[&?])" + Pattern.quote(PARAM_INFERRED_PEER_SERVICE) + "(=[^&]*)?(&|$)");

  public static String seedNodesToString(final Set<SeedNode> seedNodes) {
    return seedNodes.stream()
        .map(SeedNode::address)
        .sorted()
        .distinct()
        .collect(Collectors.joining(","));
  }

  public static String removeDdTracingFromConnectionString(final String connectionString) {
    if (connectionString == null) {
      return null;
    }
    return REPLACER.matcher(connectionString).replaceAll("");
  }
}
