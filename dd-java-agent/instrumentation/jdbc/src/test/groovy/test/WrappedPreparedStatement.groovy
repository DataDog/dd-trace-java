package test

import java.sql.Array
import java.sql.Blob
import java.sql.Clob
import java.sql.Connection
import java.sql.Date
import java.sql.NClob
import java.sql.ParameterMetaData
import java.sql.PreparedStatement
import java.sql.Ref
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.RowId
import java.sql.SQLException
import java.sql.SQLWarning
import java.sql.SQLXML
import java.sql.Time
import java.sql.Timestamp

class WrappedPreparedStatement implements PreparedStatement {
  PreparedStatement delegate

  WrappedPreparedStatement(PreparedStatement delegate) {
    this.delegate = delegate
  }

  def <T> T unwrap(Class<T> iface) throws SQLException {
    if (!isWrapperFor(iface)) {
      throw new SQLException("Not a wrapper for interface")
    }
    return delegate as T
  }

  boolean isWrapperFor(Class<?> iface) throws SQLException {
    return PreparedStatement == iface
  }

  ResultSet executeQuery() throws SQLException {
    return delegate.executeQuery()
  }

  int executeUpdate() throws SQLException {
    return delegate.executeUpdate()
  }

  void setNull(int parameterIndex, int sqlType) throws SQLException {
    delegate.setNull(parameterIndex, sqlType)
  }

  void setBoolean(int parameterIndex, boolean x) throws SQLException {
    delegate.setBoolean(parameterIndex, x)
  }

  void setByte(int parameterIndex, byte x) throws SQLException {
    delegate.setByte(parameterIndex, x)
  }

  void setShort(int parameterIndex, short x) throws SQLException {
    delegate.setShort(parameterIndex, x)
  }

  void setInt(int parameterIndex, int x) throws SQLException {
    delegate.setInt(parameterIndex, x)
  }

  void setLong(int parameterIndex, long x) throws SQLException {
    delegate.setLong(parameterIndex, x)
  }

  void setFloat(int parameterIndex, float x) throws SQLException {
    delegate.setFloat(parameterIndex, x)
  }

  void setDouble(int parameterIndex, double x) throws SQLException {
    delegate.setDouble(parameterIndex, x)
  }

  void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
    delegate.setBigDecimal(parameterIndex, x)
  }

  void setString(int parameterIndex, String x) throws SQLException {
    delegate.setString(parameterIndex, x)
  }

  void setBytes(int parameterIndex, byte[] x) throws SQLException {
    delegate.setBytes(parameterIndex, x)
  }

  void setDate(int parameterIndex, Date x) throws SQLException {
    delegate.setDate(parameterIndex, x)
  }

  void setTime(int parameterIndex, Time x) throws SQLException {
    delegate.setTime(parameterIndex, x)
  }

  void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
    delegate.setTimestamp(parameterIndex, x)
  }

  void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
    delegate.setAsciiStream(parameterIndex, x, length)
  }

  void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
    delegate.setUnicodeStream(parameterIndex, x, length)
  }

  void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
    delegate.setBinaryStream(parameterIndex, x, length)
  }

  void clearParameters() throws SQLException {
    delegate.clearParameters()
  }

  void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
    delegate.setObject(parameterIndex, x, targetSqlType)
  }

  void setObject(int parameterIndex, Object x) throws SQLException {
    delegate.setObject(parameterIndex, x)
  }

  boolean execute() throws SQLException {
    return delegate.execute()
  }

  void addBatch() throws SQLException {
    delegate.addBatch()
  }

  void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
    delegate.setCharacterStream(parameterIndex, reader, length)
  }

  void setRef(int parameterIndex, Ref x) throws SQLException {
    delegate.setRef(parameterIndex, x)
  }

  void setBlob(int parameterIndex, Blob x) throws SQLException {
    delegate.setBlob(parameterIndex, x)
  }

  void setClob(int parameterIndex, Clob x) throws SQLException {
    delegate.setClob(parameterIndex, x)
  }

  void setArray(int parameterIndex, Array x) throws SQLException {
    delegate.setArray(parameterIndex, x)
  }

  ResultSetMetaData getMetaData() throws SQLException {
    return delegate.getMetaData()
  }

  void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
    delegate.setDate(parameterIndex, x, cal)
  }

  void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
    delegate.setTime(parameterIndex, x, cal)
  }

  void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
    delegate.setTimestamp(parameterIndex, x, cal)
  }

  void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
    delegate.setNull(parameterIndex, sqlType, typeName)
  }

  void setURL(int parameterIndex, URL x) throws SQLException {
    delegate.setURL(parameterIndex, x)
  }

  ParameterMetaData getParameterMetaData() throws SQLException {
    return delegate.getParameterMetaData()
  }

  void setRowId(int parameterIndex, RowId x) throws SQLException {
    delegate.setRowId(parameterIndex, x)
  }

  void setNString(int parameterIndex, String value) throws SQLException {
    delegate.setNString(parameterIndex, value)
  }

  void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
    delegate.setNCharacterStream(parameterIndex, value, length)
  }

  void setNClob(int parameterIndex, NClob value) throws SQLException {
    delegate.setNClob(parameterIndex, value)
  }

  void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
    delegate.setClob(parameterIndex, reader, length)
  }

  void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
    delegate.setBlob(parameterIndex, inputStream, length)
  }

  void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
    delegate.setNClob(parameterIndex, reader, length)
  }

  void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
    delegate.setSQLXML(parameterIndex, xmlObject)
  }

  void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
    delegate.setObject(parameterIndex, x, targetSqlType, scaleOrLength)
  }

  void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
    delegate.setAsciiStream(parameterIndex, x, length)
  }

  void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
    delegate.setBinaryStream(parameterIndex, x, length)
  }

  void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
    delegate.setCharacterStream(parameterIndex, reader, length)
  }

  void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
    delegate.setAsciiStream(parameterIndex, x)
  }

  void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
    delegate.setBinaryStream(parameterIndex, x)
  }

  void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
    delegate.setCharacterStream(parameterIndex, reader)
  }

  void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
    delegate.setNCharacterStream(parameterIndex, value)
  }

  void setClob(int parameterIndex, Reader reader) throws SQLException {
    delegate.setClob(parameterIndex, reader)
  }

  void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
    delegate.setBlob(parameterIndex, inputStream)
  }

  void setNClob(int parameterIndex, Reader reader) throws SQLException {
    delegate.setNClob(parameterIndex, reader)
  }

  ResultSet executeQuery(String sql) throws SQLException {
    return delegate.executeQuery(sql)
  }

  int executeUpdate(String sql) throws SQLException {
    return delegate.executeUpdate(sql)
  }

  void close() throws SQLException {
    delegate.close()
  }

  int getMaxFieldSize() throws SQLException {
    return delegate.getMaxFieldSize()
  }

  void setMaxFieldSize(int max) throws SQLException {
    delegate.setMaxFieldSize(max)
  }

  int getMaxRows() throws SQLException {
    return delegate.getMaxRows()
  }

  void setMaxRows(int max) throws SQLException {
    delegate.setMaxRows(max)
  }

  void setEscapeProcessing(boolean enable) throws SQLException {
    delegate.setEscapeProcessing(enable)
  }

  int getQueryTimeout() throws SQLException {
    return delegate.getQueryTimeout()
  }

  void setQueryTimeout(int seconds) throws SQLException {
    delegate.setQueryTimeout(seconds)
  }

  void cancel() throws SQLException {
    delegate.cancel()
  }

  SQLWarning getWarnings() throws SQLException {
    return delegate.getWarnings()
  }

  void clearWarnings() throws SQLException {
    delegate.clearWarnings()
  }

  void setCursorName(String name) throws SQLException {
    delegate.setCursorName(name)
  }

  boolean execute(String sql) throws SQLException {
    return delegate.execute(sql)
  }

  ResultSet getResultSet() throws SQLException {
    return delegate.getResultSet()
  }

  int getUpdateCount() throws SQLException {
    return delegate.getUpdateCount()
  }

  boolean getMoreResults() throws SQLException {
    return delegate.getMoreResults()
  }

  void setFetchDirection(int direction) throws SQLException {
    delegate.setFetchDirection(direction)
  }

  int getFetchDirection() throws SQLException {
    return delegate.getFetchDirection()
  }

  void setFetchSize(int rows) throws SQLException {
    delegate.setFetchSize(rows)
  }

  int getFetchSize() throws SQLException {
    return delegate.getFetchSize()
  }

  int getResultSetConcurrency() throws SQLException {
    return delegate.getResultSetConcurrency()
  }

  int getResultSetType() throws SQLException {
    return delegate.getResultSetType()
  }

  void addBatch(String sql) throws SQLException {
    delegate.addBatch(sql)
  }

  void clearBatch() throws SQLException {
    delegate.clearBatch()
  }

  int[] executeBatch() throws SQLException {
    return delegate.executeBatch()
  }

  Connection getConnection() throws SQLException {
    return delegate.getConnection()
  }

  boolean getMoreResults(int current) throws SQLException {
    return delegate.getMoreResults(current)
  }

  ResultSet getGeneratedKeys() throws SQLException {
    return delegate.getGeneratedKeys()
  }

  int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    return delegate.executeUpdate(sql, autoGeneratedKeys)
  }

  int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
    return delegate.executeUpdate(sql, columnIndexes)
  }

  int executeUpdate(String sql, String[] columnNames) throws SQLException {
    return delegate.executeUpdate(sql, columnNames)
  }

  boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
    return delegate.execute(sql, autoGeneratedKeys)
  }

  boolean execute(String sql, int[] columnIndexes) throws SQLException {
    return delegate.execute(sql, columnIndexes)
  }

  boolean execute(String sql, String[] columnNames) throws SQLException {
    return delegate.execute(sql, columnNames)
  }

  int getResultSetHoldability() throws SQLException {
    return delegate.getResultSetHoldability()
  }

  boolean isClosed() throws SQLException {
    return delegate.isClosed()
  }

  void setPoolable(boolean poolable) throws SQLException {
    delegate.setPoolable(poolable)
  }

  boolean isPoolable() throws SQLException {
    return delegate.isPoolable()
  }

  void closeOnCompletion() throws SQLException {
    delegate.closeOnCompletion()
  }

  boolean isCloseOnCompletion() throws SQLException {
    return delegate.isCloseOnCompletion()
  }
}
