package datadog.trace.instrumentation.jdbc;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The values bound to one prepared or callable statement, put on the database span when the
 * statement runs. Held per statement instance, since {@code DBQueryInfo} is shared by all the
 * statements with the same SQL.
 *
 * <p>Immutable values are kept as bound and turned into strings when the statement runs. Other
 * values are turned into strings when bound, since the application may change them before the
 * statement runs, while drivers usually convert them when bound. Streams and LOBs are never read:
 * only their type is recorded.
 */
public final class QueryParameters {
  static final int MAX_PARAMETERS = 64;
  static final int MAX_VALUE_LENGTH = 1024;

  static final String VALUE_TAG_PREFIX = "db.query.parameter.";
  static final String TYPE_TAG_PREFIX = "_dd.dbm.query.parameter_type.";
  static final String TRUNCATED_TAG = "_dd.dbm.query.parameters_truncated";

  private static final String[] POSITIONAL_VALUE_TAGS = new String[MAX_PARAMETERS];
  private static final String[] POSITIONAL_TYPE_TAGS = new String[MAX_PARAMETERS];

  static {
    for (int i = 0; i < MAX_PARAMETERS; i++) {
      POSITIONAL_VALUE_TAGS[i] = VALUE_TAG_PREFIX + i;
      POSITIONAL_TYPE_TAGS[i] = TYPE_TAG_PREFIX + i;
    }
  }

  /**
   * Replaces values that are left out: streams and LOBs, which must not be read, and values that
   * could not be turned into a string.
   */
  private static final Object UNREAD = new Object();

  private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

  /** Values by 0-based position, so JDBC index 1 is at 0. */
  private final Object[] positionalValues = new Object[MAX_PARAMETERS];

  private final String[] positionalTypes = new String[MAX_PARAMETERS];

  /** Bit {@code i} is set when the position {@code i} is bound. */
  private long boundPositions;

  private Map<String, NamedParameter> namedParameters;

  /**
   * Whether an OUT parameter was registered, which can be an INOUT parameter. Kept for the life of
   * the statement, since drivers keep the registrations after {@code clearParameters}.
   */
  private boolean outParameters;

  /**
   * Whether values were left out: beyond the parameter limit, bound by name then by position (or
   * the reverse), since a name and a position can be the same parameter, or replaced by a batch.
   */
  private boolean incomplete;

  public static QueryParameters getOrCreate(
      ContextStore<PreparedStatement, QueryParameters> store, PreparedStatement statement) {
    QueryParameters queryParameters = store.get(statement);
    if (null == queryParameters) {
      queryParameters = new QueryParameters();
      store.put(statement, queryParameters);
    }
    return queryParameters;
  }

  /**
   * Records a value bound by a {@code setXxx} method other than {@code setNull} and the {@code
   * setObject} variants with a target SQL type.
   *
   * @param key the JDBC index ({@link Integer}) or the parameter name ({@link String})
   */
  public void bind(Object key, String setter, Object value) {
    put(key, typeOfSetter(setter, value), value);
  }

  /** Records a {@code setNull} call. */
  public void bindNull(Object key, int sqlType) {
    put(key, sqlTypeName(sqlType), null);
  }

  /**
   * Records a {@code setObject} call with a target SQL type.
   *
   * @param sqlType an {@link Integer} from {@link Types}, or a {@link SQLType}
   */
  public void bindWithSqlType(Object key, Object value, Object sqlType) {
    String type;
    if (sqlType instanceof Integer) {
      type = sqlTypeName((Integer) sqlType);
    } else if (sqlType instanceof SQLType) {
      type = ((SQLType) sqlType).getName();
    } else {
      type = typeOfValue(value);
    }
    put(key, type, value);
  }

  public void registerOutParameter() {
    outParameters = true;
  }

  public boolean hasOutParameters() {
    return outParameters;
  }

  /** Forgets all the values, like {@code clearParameters}. */
  public void clear() {
    forgetValues();
    incomplete = false;
  }

  /**
   * Forgets the values after a batch, or after an execution with OUT parameters, since the driver
   * may have replaced them: the values still bound in the driver are unknown, and reported as left
   * out until {@code clearParameters}.
   */
  public void invalidate() {
    forgetValues();
    incomplete = true;
  }

  private void forgetValues() {
    Arrays.fill(positionalValues, null);
    Arrays.fill(positionalTypes, null);
    boundPositions = 0;
    if (namedParameters != null) {
      namedParameters.clear();
    }
  }

  /** Puts the bound values and their types on the span, never throwing. */
  public void applyTo(AgentSpan span) {
    boolean truncated;
    try {
      truncated = tagParameters(span);
    } catch (Throwable ignored) {
      truncated = true;
    }
    if (truncated) {
      span.setTag(TRUNCATED_TAG, true);
    }
  }

  /** Returns whether anything was truncated or left out. */
  private boolean tagParameters(AgentSpan span) {
    boolean truncated = incomplete;
    int count = 0;
    long positions = boundPositions;
    while (positions != 0) {
      int position = Long.numberOfTrailingZeros(positions);
      positions &= positions - 1;
      truncated |=
          tag(
              span,
              POSITIONAL_VALUE_TAGS[position],
              POSITIONAL_TYPE_TAGS[position],
              positionalTypes[position],
              positionalValues[position]);
      count++;
    }
    if (namedParameters != null) {
      for (Map.Entry<String, NamedParameter> entry : namedParameters.entrySet()) {
        if (count == MAX_PARAMETERS) {
          truncated = true;
          break;
        }
        String name = entry.getKey();
        NamedParameter parameter = entry.getValue();
        truncated |=
            tag(
                span,
                VALUE_TAG_PREFIX + name,
                TYPE_TAG_PREFIX + name,
                parameter.type,
                parameter.value);
        count++;
      }
    }
    return truncated;
  }

  /** Keeps the value, turning it into a string only once it is known to be kept. */
  private void put(Object key, String type, Object value) {
    if (key instanceof Integer) {
      if (namedParameters != null && !namedParameters.isEmpty()) {
        namedParameters.clear();
        incomplete = true;
      }
      int position = (Integer) key - 1;
      if (position < 0 || position >= MAX_PARAMETERS) {
        incomplete = true;
        return;
      }
      positionalValues[position] = snapshot(value);
      positionalTypes[position] = type;
      boundPositions |= 1L << position;
    } else if (key instanceof String) {
      if (boundPositions != 0) {
        Arrays.fill(positionalValues, null);
        Arrays.fill(positionalTypes, null);
        boundPositions = 0;
        incomplete = true;
      }
      if (namedParameters == null) {
        namedParameters = new LinkedHashMap<>();
      }
      String name = (String) key;
      if (!namedParameters.containsKey(name)) {
        removeAliases(name);
        if (namedParameters.size() >= MAX_PARAMETERS) {
          incomplete = true;
          return;
        }
      }
      namedParameters.put(name, new NamedParameter(type, snapshot(value)));
    }
  }

  /**
   * Drivers may resolve different names to the same parameter, for example ignoring the case or an
   * {@code @} or {@code :} prefix. Since whether they do depends on the driver, the values bound to
   * a name that may be the same are left out.
   */
  private void removeAliases(String name) {
    Iterator<String> names = namedParameters.keySet().iterator();
    while (names.hasNext()) {
      if (mayBeSameParameter(names.next(), name)) {
        names.remove();
        incomplete = true;
      }
    }
  }

  static boolean mayBeSameParameter(String name, String other) {
    int start = parameterNameStart(name);
    int otherStart = parameterNameStart(other);
    int length = name.length() - start;
    return length == other.length() - otherStart
        && name.regionMatches(true, start, other, otherStart, length);
  }

  private static int parameterNameStart(String name) {
    return !name.isEmpty() && (name.charAt(0) == '@' || name.charAt(0) == ':') ? 1 : 0;
  }

  /** Returns whether the value was truncated or left out. */
  private static boolean tag(
      AgentSpan span, String valueTag, String typeTag, String type, Object value) {
    if (type != null) {
      span.setTag(typeTag, type);
    }
    if (value == UNREAD) {
      return true;
    }
    String string = toStringOrNull(value);
    if (null == string) {
      return true;
    }
    if (string.length() > MAX_VALUE_LENGTH) {
      int end = MAX_VALUE_LENGTH;
      if (Character.isHighSurrogate(string.charAt(end - 1))) {
        end--;
      }
      span.setTag(valueTag, string.substring(0, end));
      return true;
    }
    span.setTag(valueTag, string);
    return false;
  }

  /**
   * Returns the value to keep until the statement runs: immutable values as is, the others as a
   * string, cut just after the length limit so that they are still seen as truncated.
   */
  private static Object snapshot(Object value) {
    if (null == value || isImmutable(value)) {
      return value;
    }
    if (isUnread(value)) {
      return UNREAD;
    }
    String string = toStringOrNull(value);
    if (null == string) {
      return UNREAD;
    }
    return string.length() > MAX_VALUE_LENGTH + 1
        ? string.substring(0, MAX_VALUE_LENGTH + 1)
        : string;
  }

  private static boolean isImmutable(Object value) {
    return value instanceof String
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Short
        || value instanceof Byte
        || value instanceof Boolean
        || value instanceof Character
        || value instanceof Double
        || value instanceof Float
        || value.getClass() == BigDecimal.class
        || value.getClass() == BigInteger.class
        || value instanceof LocalDate
        || value instanceof LocalTime
        || value instanceof LocalDateTime
        || value instanceof OffsetTime
        || value instanceof OffsetDateTime;
  }

  /** Returns {@code null} when the value can't be turned into a string. */
  private static String toStringOrNull(Object value) {
    if (value instanceof byte[]) {
      return toHex((byte[]) value);
    }
    if (null == value) {
      return "null";
    }
    try {
      // toString may return null, for example for a PostgreSQL PGobject holding SQL NULL
      return value.toString();
    } catch (Throwable ignored) {
      return null;
    }
  }

  /** Encodes only what can be kept, plus one byte so that the result is seen as truncated. */
  private static String toHex(byte[] bytes) {
    int length = Math.min(bytes.length, MAX_VALUE_LENGTH / 2 + 1);
    char[] hex = new char[length * 2];
    for (int i = 0; i < length; i++) {
      hex[2 * i] = HEX_DIGITS[(bytes[i] >> 4) & 0xF];
      hex[2 * i + 1] = HEX_DIGITS[bytes[i] & 0xF];
    }
    return new String(hex);
  }

  /** Values that may be read lazily from the database or from a stream. */
  private static boolean isUnread(Object value) {
    return value instanceof InputStream
        || value instanceof Reader
        || value instanceof Blob
        || value instanceof Clob
        || value instanceof SQLXML
        || value instanceof java.sql.Array
        || value instanceof Ref
        || value instanceof Struct;
  }

  private static String typeOfSetter(String setter, Object value) {
    switch (setter) {
      case "setBoolean":
        return "BOOLEAN";
      case "setByte":
        return "TINYINT";
      case "setShort":
        return "SMALLINT";
      case "setInt":
        return "INTEGER";
      case "setLong":
        return "BIGINT";
      case "setFloat":
        return "REAL";
      case "setDouble":
        return "DOUBLE";
      case "setBigDecimal":
        return "NUMERIC";
      case "setString":
        return "VARCHAR";
      case "setNString":
        return "NVARCHAR";
      case "setBytes":
        return "VARBINARY";
      case "setDate":
        return "DATE";
      case "setTime":
        return "TIME";
      case "setTimestamp":
        return "TIMESTAMP";
      case "setAsciiStream":
      case "setUnicodeStream":
      case "setCharacterStream":
        return "LONGVARCHAR";
      case "setNCharacterStream":
        return "LONGNVARCHAR";
      case "setBinaryStream":
        return "LONGVARBINARY";
      case "setBlob":
        return "BLOB";
      case "setClob":
        return "CLOB";
      case "setNClob":
        return "NCLOB";
      case "setArray":
        return "ARRAY";
      case "setRef":
        return "REF";
      case "setRowId":
        return "ROWID";
      case "setSQLXML":
        return "SQLXML";
      case "setURL":
        return "DATALINK";
      default: // setObject
        return typeOfValue(value);
    }
  }

  /** The SQL type a driver would use for a value given to {@code setObject}. */
  static String typeOfValue(Object value) {
    if (value == null) {
      return "NULL";
    }
    if (value instanceof String) {
      return "VARCHAR";
    }
    if (value instanceof Integer) {
      return "INTEGER";
    }
    if (value instanceof Long) {
      return "BIGINT";
    }
    // arbitrary precision, so drivers bind BigInteger like BigDecimal rather than as BIGINT
    if (value instanceof BigDecimal || value instanceof BigInteger) {
      return "NUMERIC";
    }
    if (value instanceof Boolean) {
      return "BOOLEAN";
    }
    if (value instanceof Short) {
      return "SMALLINT";
    }
    if (value instanceof Byte) {
      return "TINYINT";
    }
    if (value instanceof Double) {
      return "DOUBLE";
    }
    if (value instanceof Float) {
      return "REAL";
    }
    if (value instanceof Character) {
      return "CHAR";
    }
    if (value instanceof byte[]) {
      return "VARBINARY";
    }
    if (value instanceof java.sql.Date || value instanceof LocalDate) {
      return "DATE";
    }
    if (value instanceof Time || value instanceof LocalTime) {
      return "TIME";
    }
    if (value instanceof Timestamp
        || value instanceof LocalDateTime
        || value instanceof java.util.Date
        || value instanceof Calendar) {
      return "TIMESTAMP";
    }
    if (value instanceof OffsetDateTime) {
      return "TIMESTAMP_WITH_TIMEZONE";
    }
    if (value instanceof OffsetTime) {
      return "TIME_WITH_TIMEZONE";
    }
    if (value instanceof NClob) {
      return "NCLOB";
    }
    if (value instanceof Clob) {
      return "CLOB";
    }
    if (value instanceof Blob) {
      return "BLOB";
    }
    if (value instanceof SQLXML) {
      return "SQLXML";
    }
    if (value instanceof java.sql.Array) {
      return "ARRAY";
    }
    if (value instanceof Struct) {
      return "STRUCT";
    }
    if (value instanceof Ref) {
      return "REF";
    }
    if (value instanceof RowId) {
      return "ROWID";
    }
    if (value instanceof URL) {
      return "DATALINK";
    }
    if (value instanceof CharSequence) {
      return "VARCHAR";
    }
    if (value instanceof Reader) {
      return "LONGVARCHAR";
    }
    if (value instanceof InputStream) {
      return "LONGVARBINARY";
    }
    return "JAVA_OBJECT";
  }

  /** The name of a {@link Types} constant, {@code OTHER} for vendor types. */
  static String sqlTypeName(int sqlType) {
    switch (sqlType) {
      case Types.BIT:
        return "BIT";
      case Types.TINYINT:
        return "TINYINT";
      case Types.SMALLINT:
        return "SMALLINT";
      case Types.INTEGER:
        return "INTEGER";
      case Types.BIGINT:
        return "BIGINT";
      case Types.FLOAT:
        return "FLOAT";
      case Types.REAL:
        return "REAL";
      case Types.DOUBLE:
        return "DOUBLE";
      case Types.NUMERIC:
        return "NUMERIC";
      case Types.DECIMAL:
        return "DECIMAL";
      case Types.CHAR:
        return "CHAR";
      case Types.VARCHAR:
        return "VARCHAR";
      case Types.LONGVARCHAR:
        return "LONGVARCHAR";
      case Types.DATE:
        return "DATE";
      case Types.TIME:
        return "TIME";
      case Types.TIMESTAMP:
        return "TIMESTAMP";
      case Types.BINARY:
        return "BINARY";
      case Types.VARBINARY:
        return "VARBINARY";
      case Types.LONGVARBINARY:
        return "LONGVARBINARY";
      case Types.NULL:
        return "NULL";
      case Types.JAVA_OBJECT:
        return "JAVA_OBJECT";
      case Types.DISTINCT:
        return "DISTINCT";
      case Types.STRUCT:
        return "STRUCT";
      case Types.ARRAY:
        return "ARRAY";
      case Types.BLOB:
        return "BLOB";
      case Types.CLOB:
        return "CLOB";
      case Types.REF:
        return "REF";
      case Types.DATALINK:
        return "DATALINK";
      case Types.BOOLEAN:
        return "BOOLEAN";
      case Types.ROWID:
        return "ROWID";
      case Types.NCHAR:
        return "NCHAR";
      case Types.NVARCHAR:
        return "NVARCHAR";
      case Types.LONGNVARCHAR:
        return "LONGNVARCHAR";
      case Types.NCLOB:
        return "NCLOB";
      case Types.SQLXML:
        return "SQLXML";
      case Types.REF_CURSOR:
        return "REF_CURSOR";
      case Types.TIME_WITH_TIMEZONE:
        return "TIME_WITH_TIMEZONE";
      case Types.TIMESTAMP_WITH_TIMEZONE:
        return "TIMESTAMP_WITH_TIMEZONE";
      default:
        return "OTHER";
    }
  }

  private static final class NamedParameter {
    final String type;
    final Object value;

    NamedParameter(String type, Object value) {
      this.type = type;
      this.value = value;
    }
  }
}
