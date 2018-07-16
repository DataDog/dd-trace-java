package datadog.trace.instrumentation.elasticsearch6;

import static io.opentracing.log.Fields.ERROR_OBJECT;

import com.google.common.base.Joiner;
import io.opentracing.Span;
import io.opentracing.tag.Tags;
import java.util.Collections;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.bulk.BulkShardResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.broadcast.BroadcastResponse;
import org.elasticsearch.action.support.nodes.BaseNodesResponse;
import org.elasticsearch.action.support.replication.ReplicationResponse;

/**
 * Most of this class is identical to version 5's instrumentation, but they changed an interface to
 * an abstract class, so the bytecode isn't directly compatible.
 */
public class TransportActionListener<T extends ActionResponse> implements ActionListener<T> {

  private final ActionListener<T> listener;
  private final Span span;

  public TransportActionListener(
      final ActionRequest actionRequest, final ActionListener<T> listener, final Span span) {
    this.listener = listener;
    this.span = span;
    onRequest(actionRequest);
  }

  private void onRequest(final ActionRequest request) {
    if (request != null) {
      span.setTag("elasticsearch.request.description", request.getDescription());
    }
    if (request instanceof IndicesRequest) {
      final IndicesRequest req = (IndicesRequest) request;
      if (req.indices() != null) {
        span.setTag("elasticsearch.request.indices", Joiner.on(",").join(req.indices()));
      }
    }
    if (request instanceof SearchRequest) {
      final SearchRequest req = (SearchRequest) request;
      span.setTag("elasticsearch.request.search.types", Joiner.on(",").join(req.types()));
    }
    if (request instanceof DocWriteRequest) {
      final DocWriteRequest req = (DocWriteRequest) request;
      span.setTag("elasticsearch.request.write.type", req.type());
      span.setTag("elasticsearch.request.write.routing", req.routing());
      span.setTag("elasticsearch.request.write.version", req.version());
    }
  }

  @Override
  public void onResponse(final T response) {
    if (response.remoteAddress() != null) {
      Tags.PEER_HOSTNAME.set(span, response.remoteAddress().address().getHostName());
      Tags.PEER_HOST_IPV4.set(span, response.remoteAddress().getAddress());
      Tags.PEER_PORT.set(span, response.remoteAddress().getPort());
    }

    if (response instanceof GetResponse) {
      final GetResponse resp = (GetResponse) response;
      span.setTag("elasticsearch.type", resp.getType());
      span.setTag("elasticsearch.id", resp.getId());
      span.setTag("elasticsearch.version", resp.getVersion());
    }

    if (response instanceof BroadcastResponse) {
      final BroadcastResponse resp = (BroadcastResponse) response;
      span.setTag("elasticsearch.shard.broadcast.total", resp.getTotalShards());
      span.setTag("elasticsearch.shard.broadcast.successful", resp.getSuccessfulShards());
      span.setTag("elasticsearch.shard.broadcast.failed", resp.getFailedShards());
    }

    if (response instanceof ReplicationResponse) {
      final ReplicationResponse resp = (ReplicationResponse) response;
      span.setTag("elasticsearch.shard.replication.total", resp.getShardInfo().getTotal());
      span.setTag(
          "elasticsearch.shard.replication.successful", resp.getShardInfo().getSuccessful());
      span.setTag("elasticsearch.shard.replication.failed", resp.getShardInfo().getFailed());
    }

    if (response instanceof IndexResponse) {
      span.setTag("elasticsearch.response.status", ((IndexResponse) response).status().getStatus());
    }

    if (response instanceof BulkShardResponse) {
      final BulkShardResponse resp = (BulkShardResponse) response;
      span.setTag("elasticsearch.shard.bulk.id", resp.getShardId().getId());
      span.setTag("elasticsearch.shard.bulk.index", resp.getShardId().getIndexName());
    }

    if (response instanceof BaseNodesResponse) {
      final BaseNodesResponse resp = (BaseNodesResponse) response;
      if (resp.hasFailures()) {
        span.setTag("elasticsearch.node.failures", resp.failures().size());
      }
      span.setTag("elasticsearch.node.cluster.name", resp.getClusterName().value());
    }

    try {
      listener.onResponse(response);
    } finally {
      span.finish();
    }
  }

  @Override
  public void onFailure(final Exception e) {
    Tags.ERROR.set(span, true);
    span.log(Collections.singletonMap(ERROR_OBJECT, e));

    try {
      listener.onFailure(e);
    } finally {
      span.finish();
    }
  }
}
