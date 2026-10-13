package datadog.trace.instrumentation.r2dbc;

import datadog.trace.instrumentation.r2dbc.shaded.proxy.callback.ProxyConfig;
import datadog.trace.instrumentation.r2dbc.shaded.proxy.callback.ProxyFactory;
import datadog.trace.instrumentation.r2dbc.shaded.proxy.callback.ProxyFactoryFactory;
import datadog.trace.instrumentation.r2dbc.shaded.proxy.callback.QueriesExecutionContext;
import datadog.trace.instrumentation.r2dbc.shaded.proxy.core.ConnectionInfo;
import datadog.trace.instrumentation.r2dbc.shaded.proxy.core.QueryExecutionInfo;
import datadog.trace.instrumentation.r2dbc.shaded.proxy.core.StatementInfo;
import io.r2dbc.spi.Batch;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Readable;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import io.r2dbc.spi.Statement;
import io.r2dbc.spi.Wrapped;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import org.reactivestreams.Publisher;

/** Keeps results returned by filter attached to the proxy's query lifecycle. */
public final class R2dbcProxyFactory implements ProxyFactory, ProxyFactoryFactory {
  private final ProxyFactory delegate;

  public R2dbcProxyFactory(ProxyFactory delegate) {
    this.delegate = delegate;
  }

  @Override
  public ProxyFactory create(ProxyConfig config) {
    return this;
  }

  @Override
  public ConnectionFactory wrapConnectionFactory(ConnectionFactory factory) {
    return delegate.wrapConnectionFactory(factory);
  }

  @Override
  public Connection wrapConnection(Connection connection, ConnectionInfo info) {
    return delegate.wrapConnection(connection, info);
  }

  @Override
  public Batch wrapBatch(Batch batch, ConnectionInfo info) {
    return delegate.wrapBatch(batch, info);
  }

  @Override
  public Statement wrapStatement(
      Statement statement, StatementInfo statementInfo, ConnectionInfo info) {
    return delegate.wrapStatement(statement, statementInfo, info);
  }

  @Override
  public Result wrapResult(
      Result result, QueryExecutionInfo info, QueriesExecutionContext context) {
    return new FilterAwareResult(delegate.wrapResult(result, info, context), this, info, context);
  }

  @Override
  public Row wrapRow(Row row, QueryExecutionInfo info) {
    return delegate.wrapRow(row, info);
  }

  @Override
  public Result.RowSegment wrapRowSegment(Result.RowSegment segment, QueryExecutionInfo info) {
    return delegate.wrapRowSegment(segment, info);
  }

  public static final class FilterAwareResult implements Result, Wrapped<Result> {
    private final Result delegate;
    private final R2dbcProxyFactory factory;
    private final QueryExecutionInfo info;
    private final QueriesExecutionContext context;

    public FilterAwareResult(
        Result delegate,
        R2dbcProxyFactory factory,
        QueryExecutionInfo info,
        QueriesExecutionContext context) {
      this.delegate = delegate;
      this.factory = factory;
      this.info = info;
      this.context = context;
    }

    @Override
    public Publisher<Long> getRowsUpdated() {
      return delegate.getRowsUpdated();
    }

    @Override
    public <T> Publisher<T> map(BiFunction<Row, RowMetadata, ? extends T> mapping) {
      return delegate.map(mapping);
    }

    @Override
    public <T> Publisher<T> map(Function<? super Readable, ? extends T> mapping) {
      return delegate.map(mapping);
    }

    @Override
    public Result filter(Predicate<Segment> predicate) {
      // r2dbc-proxy 1.1.6 returns the driver's filtered Result without wrapping it.
      // Reuse the execution context so consuming this result finishes the original query once.
      return factory.wrapResult(delegate.filter(predicate), info, context);
    }

    @Override
    public <T> Publisher<T> flatMap(Function<Segment, ? extends Publisher<? extends T>> mapping) {
      return delegate.flatMap(mapping);
    }

    @Override
    public Result unwrap() {
      return (Result) ((Wrapped<?>) delegate).unwrap();
    }

    @Override
    public <E> E unwrap(Class<E> type) {
      return ((Wrapped<?>) delegate).unwrap(type);
    }
  }
}
