package datadog.trace.instrumentation.azure.cosmos;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.azure.cosmos.CosmosResourcePaths.databaseAndContainer;
import static datadog.trace.instrumentation.azure.cosmos.CosmosResourcePaths.idFromBody;
import static datadog.trace.instrumentation.azure.cosmos.CosmosResourcePaths.normalize;

import com.azure.cosmos.CosmosException;
import com.azure.cosmos.implementation.OperationType;
import com.azure.cosmos.implementation.PathsHelper;
import com.azure.cosmos.implementation.ResourceType;
import com.azure.cosmos.implementation.RxDocumentServiceRequest;
import com.azure.cosmos.implementation.RxDocumentServiceResponse;
import datadog.trace.api.Pair;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import java.util.Map;

public class AzureCosmosDecorator
    extends DBTypeProcessingDatabaseClientDecorator<CosmosClientInfo> {
  private static final String DB_TYPE = "cosmosdb";
  private static final String INSTRUMENTATION_NAME = "azure-cosmos";
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().database().service(DB_TYPE);

  public static final UTF8BytesString COMPONENT = UTF8BytesString.create(INSTRUMENTATION_NAME);
  public static final UTF8BytesString SPAN_TYPE = UTF8BytesString.create(DB_TYPE);
  public static final UTF8BytesString OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().database().operation(DB_TYPE));

  static final String CONNECTION_MODE = "cosmosdb.connection.mode";
  static final String CONTAINER = "cosmosdb.container";
  static final String SUB_STATUS_CODE = "cosmosdb.response.sub_status_code";
  static final String STATUS_CODE = "db.response.status_code";
  private static final String SUB_STATUS_HEADER = "x-ms-substatus";
  private static final Pair<String, String> NO_NAMES = Pair.of(null, null);
  private static final int NOT_FOUND = 404;
  private static final int GONE = 410;
  private static final int TOO_MANY_REQUESTS = 429;
  private static final int RETRY_WITH = 449;
  private static final int PARTITION_KEY_RANGE_GONE = 1002;

  public static final AzureCosmosDecorator DECORATE = new AzureCosmosDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {INSTRUMENTATION_NAME};
  }

  @Override
  protected String service() {
    return SERVICE_NAME;
  }

  @Override
  protected CharSequence component() {
    return COMPONENT;
  }

  @Override
  protected CharSequence spanType() {
    return SPAN_TYPE;
  }

  @Override
  protected String dbType() {
    return DB_TYPE;
  }

  @Override
  protected String dbUser(CosmosClientInfo info) {
    return null;
  }

  @Override
  protected String dbInstance(CosmosClientInfo info) {
    // the database is per request, see onRequest
    return null;
  }

  @Override
  protected CharSequence dbHostname(CosmosClientInfo info) {
    return info.host;
  }

  public AgentSpan startRequestSpan(RxDocumentServiceRequest request, CosmosClientInfo info) {
    AgentSpan span = startSpan(INSTRUMENTATION_NAME, OPERATION_NAME);
    try {
      afterStart(span);
      onRequest(span, request, info);
    } catch (Throwable ignored) {
      // tags are best effort, the span must still be finished by the subscriber
    }
    return span;
  }

  void onRequest(AgentSpan span, RxDocumentServiceRequest request, CosmosClientInfo info) {
    OperationType operationType = request.getOperationType();
    String path = requestPath(request);
    span.setResourceName(
        (operationType == null ? "" : operationType.name()) + " " + normalize(path));

    // the SDK addresses some of its own metadata requests, such as partition key range reads, by
    // internal resource ids rather than by name, so only name based paths carry the names
    Pair<String, String> databaseAndContainer =
        request.getIsNameBased() ? databaseAndContainer(path) : NO_NAMES;
    String database = databaseAndContainer.getLeft();
    if (database == null
        && operationType == OperationType.Create
        && request.getResourceType() == ResourceType.Database) {
      database = idFromBody(request.getContentAsByteArray());
    }
    onInstance(span, database);
    if (databaseAndContainer.hasRight()) {
      span.setTag(CONTAINER, databaseAndContainer.getRight());
    }

    if (info != null) {
      onConnection(span, info);
      setPeerPort(span, info.port);
      span.setTag(CONNECTION_MODE, info.connectionMode);
      span.setTag(Tags.HTTP_USER_AGENT, info.userAgent());
    }
  }

  public void onResponse(AgentSpan span, RxDocumentServiceResponse response) {
    if (response == null) {
      return;
    }
    span.setTag(STATUS_CODE, Integer.toString(response.getStatusCode()));
    Map<String, String> headers = response.getResponseHeaders();
    if (headers != null) {
      String subStatus = headers.get(SUB_STATUS_HEADER);
      if (subStatus != null && !subStatus.isEmpty() && !"0".equals(subStatus)) {
        span.setTag(SUB_STATUS_CODE, subStatus);
      }
    }
  }

  @Override
  protected void doOnError(AgentSpan span, Throwable throwable, byte errorPriority) {
    if (!(throwable instanceof CosmosException)) {
      super.doOnError(span, throwable, errorPriority);
      return;
    }
    CosmosException cosmosException = (CosmosException) throwable;
    int status = cosmosException.getStatusCode();
    int subStatus = cosmosException.getSubStatusCode();
    try {
      if (!isExpected(status, subStatus)) {
        super.doOnError(span, throwable, errorPriority);
      }
    } finally {
      if (subStatus != 0) {
        span.setTag(SUB_STATUS_CODE, Integer.toString(subStatus));
      }
      span.setTag(STATUS_CODE, Integer.toString(status));
    }
  }

  /**
   * Statuses that don't mark the span as an error: the SDK retries throttling (429), retry-with
   * (449) and partition key range gone (410 with sub-status 1002) responses, and a 404 is often an
   * expected result. The span is per request, so a retried attempt would otherwise show as an error
   * even when the operation succeeds.
   */
  static boolean isExpected(int status, int subStatus) {
    switch (status) {
      case TOO_MANY_REQUESTS:
      case RETRY_WITH:
      case NOT_FOUND:
        return true;
      case GONE:
        return subStatus == PARTITION_KEY_RANGE_GONE;
      default:
        return false;
    }
  }

  /**
   * The path the gateway sends the request to. Direct mode requests are resolved to the same path
   * so both modes produce the same resources.
   */
  static String requestPath(RxDocumentServiceRequest request) {
    String path;
    try {
      path = PathsHelper.generatePath(request.getResourceType(), request, request.isFeed);
    } catch (RuntimeException e) {
      path = null;
    }
    if (path == null) {
      path = request.getResourceAddress();
    }
    if (path == null) {
      return "";
    }
    return path.startsWith("/") ? path : "/" + path;
  }
}
