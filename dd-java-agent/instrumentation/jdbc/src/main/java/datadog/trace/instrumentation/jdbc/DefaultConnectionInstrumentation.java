package datadog.trace.instrumentation.jdbc;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;

@AutoService(InstrumenterModule.class)
public class DefaultConnectionInstrumentation extends AbstractConnectionInstrumentation
    implements Instrumenter.ForKnownTypes,
        Instrumenter.ForConfiguredType,
        Instrumenter.HasMethodAdvice {

  static final String[] CONCRETE_TYPES = {
    // redshift
    "com.amazon.redshift.jdbc.RedshiftConnectionImpl",
    // jt400
    "com.ibm.as400.access.AS400JDBCConnection",
    // should cover derby
    "org.apache.derby.impl.jdbc.EmbedConnection",
    "org.apache.hive.jdbc.HiveConnection",
    "org.apache.phoenix.jdbc.PhoenixConnection",
    "org.apache.pinot.client.PinotConnection",
    // complete
    "org.h2.jdbc.JdbcConnection",
    // GridGain's fork of H2
    "org.gridgain.internal.h2.jdbc.JdbcConnection",
    // complete
    "org.hsqldb.jdbc.JDBCConnection",
    "org.hsqldb.jdbc.jdbcConnection",
    // sqlite seems to be complete
    "org.sqlite.Conn",
    "org.sqlite.jdbc3.JDBC3Connection",
    "org.sqlite.jdbc4.JDBC4Connection",
    // covers snowflake
    "net.snowflake.client.jdbc.SnowflakeConnectionV1",
    // vertica
    "com.vertica.jdbc.common.SConnection",
    // this covers apache calcite/drill plus the drill-all uber-jar
    "org.apache.calcite.avatica.AvaticaConnection",
    "oadd.org.apache.calcite.avatica.AvaticaConnection",
    // SAP HANA in-memory DB
    "com.sap.db.jdbc.ConnectionSapDB",
    // IBM Informix
    "com.informix.jdbc.IfmxConnection",
    // Intersystems IRIS
    "com.intersystems.jdbc.IRISConnection",
    // Sybase
    "com.sybase.jdbc2.jdbc.SybConnection",
    "com.sybase.jdbc4.jdbc.SybConnection",
    // for testing purposes
    "test.TestConnection"
  };

  @Override
  public String configuredMatchingType() {
    // this won't match any class unless the property is set
    return InstrumenterConfig.get().getJdbcConnectionClassName();
  }

  public DefaultConnectionInstrumentation() {
    super("jdbc");
  }

  @Override
  public String[] knownMatchingTypes() {
    return CONCRETE_TYPES;
  }
}
