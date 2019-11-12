package datadog.trace.instrumentation.elasticsearch5;

import static datadog.trace.instrumentation.elasticsearch.ElasticsearchTransportClientDecorator.DECORATE;

import com.google.common.base.Joiner;
import datadog.trace.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.api.Tags;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.DocumentRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.bulk.BulkShardResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.broadcast.BroadcastResponse;
import org.elasticsearch.action.support.nodes.BaseNodesResponse;
import org.elasticsearch.action.support.replication.ReplicationResponse;

public class TransportActionListener<T extends ActionResponse> implements ActionListener<T> {

  private final ActionListener<T> listener;
  private final AgentSpan span;

  public TransportActionListener(
      final ActionRequest actionRequest, final ActionListener<T> listener, final AgentSpan span) {
    this.listener = listener;
    this.span = span;
    onRequest(actionRequest);
  }

  private void onRequest(final ActionRequest request) {
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
    if (request instanceof DocumentRequest) {
      final DocumentRequest req = (DocumentRequest) request;
      span.setTag("elasticsearch.request.write.type", req.type());
      span.setTag("elasticsearch.request.write.routing", req.routing());
    }
  }

  @Override
  public void onResponse(final T response) {
    if (response.remoteAddress() != null) {
      span.setTag(Tags.PEER_HOSTNAME, response.remoteAddress().getHost());
      span.setTag(Tags.PEER_HOST_IPV4, response.remoteAddress().getAddress());
      span.setTag(Tags.PEER_PORT, response.remoteAddress().getPort());
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
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  @Override
  public void onFailure(final Exception e) {
    DECORATE.onError(span, e);

    try {
      listener.onFailure(e);
    } finally {
      span.finish();
    }
  }
}
