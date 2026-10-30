package datadog.trace.instrumentation.cassandra4;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.metadata.EndPoint;
import com.datastax.oss.driver.api.core.metadata.Node;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Optional;

public final class ContactPointsUtil {

  private ContactPointsUtil() {}

  public static String getContactPoints(final CqlSession session) {
    try {
      Collection<Node> nodes = session.getMetadata().getNodes().values();
      if (nodes.isEmpty()) {
        return null;
      }
      StringBuilder sb = new StringBuilder();
      for (Node node : nodes) {
        if (sb.length() > 0) {
          sb.append(",");
        }
        EndPoint endPoint = node.getEndPoint();
        if (endPoint != null) {
          SocketAddress socketAddress = endPoint.resolve();
          if (socketAddress instanceof InetSocketAddress) {
            InetSocketAddress inetSocketAddress = (InetSocketAddress) socketAddress;
            sb.append(inetSocketAddress.getHostString())
                .append(":")
                .append(inetSocketAddress.getPort());
          }
        }
      }
      return sb.length() > 0 ? sb.toString() : null;
    } catch (Throwable ignored) {
      return null;
    }
  }

  public static String getFirstHost(final CqlSession session) {
    try {
      Collection<Node> nodes = session.getMetadata().getNodes().values();
      for (Node node : nodes) {
        EndPoint endPoint = node.getEndPoint();
        if (endPoint != null) {
          SocketAddress socketAddress = endPoint.resolve();
          if (socketAddress instanceof InetSocketAddress) {
            return ((InetSocketAddress) socketAddress).getHostString();
          }
        }
      }
    } catch (Throwable ignored) {
      // Connection metadata may not be available
    }
    return null;
  }

  public static String getKeyspace(final CqlSession session) {
    try {
      Optional<CqlIdentifier> keyspace = session.getKeyspace();
      if (keyspace.isPresent()) {
        return keyspace.get().asCql(false);
      }
    } catch (Throwable ignored) {
      // Keyspace may not be available
    }
    return null;
  }

  public static String getQuery(final Statement<?> statement) {
    String query = null;
    if (statement instanceof com.datastax.oss.driver.api.core.cql.SimpleStatement) {
      query = ((com.datastax.oss.driver.api.core.cql.SimpleStatement) statement).getQuery();
    } else if (statement instanceof BoundStatement) {
      query = ((BoundStatement) statement).getPreparedStatement().getQuery();
    }
    return query == null ? "" : query;
  }
}
