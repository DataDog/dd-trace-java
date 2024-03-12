package test

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLWarning
import java.sql.Statement

class TestStatement implements Statement {
  final Connection connection

  TestStatement(Connection connection) {
    this.connection = connection
  }

  @Override
  ResultSet executeQuery(String sql) throws SQLException {
    return null
  }

  @Override
  int executeUpdate(String sql) throws SQLException {
    return 0
  }

  @Override
  void close() throws SQLException {
  }

  @Override
  int getMaxFieldSize() throws SQLException {
    return 0
  }

  @Override
  void setMaxFieldSize(int max) throws SQLException {
  }

  @Override
  int getMaxRows() throws SQLException {
    return 0
  }

  @Override
  void setMaxRows(int max) throws SQLException {
  }

  @Override
  void setEscapeProcessing(boolean enable) throws SQLException {
  }

  @Override
  int getQueryTimeout() throws SQLException {
    return 0
  }

  @Override
  void setQueryTimeout(int seconds) throws SQLException {
  }

  @Override
  void cancel() throws SQLException {
  }

  @Override
  SQLWarning getWarnings() throws SQLException {
    return null
  }

  @Override
  void clearWarnings() throws SQLException {
  }

  @Override
  void setCursorName(String name) throws SQLException {
  }

  @Override
  boolean execute(String sql) throws SQLException {
    return false
  }

  @Override
  ResultSet getResultSet() throws SQLException {
    return null
  }

  @Override
  int getUpdateCount() throws SQLException {
    return 0
  }

  @Override
  boolean getMoreResults() throws SQLException {
    return false
  }

  @Override
  void setFetchDirection(int direction) throws SQLException {
  }

  @Override
  int getFetchDirection() throws SQLException {
    return 0
  }

  @Override
  void setFetchSize(int rows) throws SQLException {
  }

  @Override
  int getFetchSize() throws SQLException {
    return 0
  }

  @Override
  int getResultSetConcurrency() throws SQLException {
    return 0
  }

  @Override
  int getResultSetType() throws SQLException {
    return 0
  }

  @Override
  void addBatch(String sql) throws SQLException {
  }

  @Override
  void clearBatch() throws SQLException {
  }

  @Override
  int[] executeBatch() throws SQLException {
    return new int[0]
  }

  @Override
  Connection getConnection() throws SQLException {
    return connection
  }

  @Override
  boolean getMoreResults(int current) throws SQLException {
    return false
  }

  @Override
  ResultSet getGeneratedKeys() throws SQLException {
    return null
  }

  @Override
  int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    return 0
  }

  @Override
  int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
    return 0
  }

  @Override
  int executeUpdate(String sql, String[] columnNames) throws SQLException {
    return 0
  }

  @Override
  boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
    return false
  }

  @Override
  boolean execute(String sql, int[] columnIndexes) throws SQLException {
    return false
  }

  @Override
  boolean execute(String sql, String[] columnNames) throws SQLException {
    return false
  }

  @Override
  int getResultSetHoldability() throws SQLException {
    return 0
  }

  @Override
  boolean isClosed() throws SQLException {
    return false
  }

  @Override
  void setPoolable(boolean poolable) throws SQLException {
  }

  @Override
  boolean isPoolable() throws SQLException {
    return false
  }

  @Override
  void closeOnCompletion() throws SQLException {
  }

  @Override
  boolean isCloseOnCompletion() throws SQLException {
    return false
  }

  @Override
  def <T> T unwrap(Class<T> iface) throws SQLException {
    return null
  }

  @Override
  boolean isWrapperFor(Class<?> iface) throws SQLException {
    return false
  }
}