package datadog.trace.instrumentation.couchbase_32.client;

import com.couchbase.client.core.env.SeedNode;
import java.util.Set;
import java.util.stream.Collectors;

public class SeedNodeHelper {
  public static String toStringForm(final Set<SeedNode> seedNodes) {
    return seedNodes.stream()
        .map(SeedNode::address)
        .sorted()
        .distinct()
        .collect(Collectors.joining(","));
  }
}
