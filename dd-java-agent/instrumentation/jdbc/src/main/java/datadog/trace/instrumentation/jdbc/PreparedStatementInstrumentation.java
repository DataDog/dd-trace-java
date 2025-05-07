package datadog.trace.instrumentation.jdbc;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;

@AutoService(InstrumenterModule.class)
public final class PreparedStatementInstrumentation extends AbstractPreparedStatementInstrumentation
    implements Instrumenter.ForKnownTypes,
        Instrumenter.ForConfiguredType,
        Instrumenter.HasMethodAdvice {

  private static final String[] CONCRETE_TYPES = {
    // redshift
    "com.amazon.redshift.jdbc.RedshiftPreparedStatement",
    "com.amazon.redshift.jdbc.RedshiftCallableStatement",
    // jt400
    "com.ibm.as400.access.AS400JDBCPreparedStatement",
    // probably patchy cover
    "com.microsoft.sqlserver.jdbc.SQLServerPreparedStatement",
    "com.microsoft.sqlserver.jdbc.SQLServerCallableStatement",
    // should cover mysql
    "com.mysql.jdbc.PreparedStatement",
    "com.mysql.jdbc.jdbc1.PreparedStatement",
    "com.mysql.jdbc.jdbc2.PreparedStatement",
    "com.mysql.jdbc.ServerPreparedStatement",
    "com.mysql.cj.jdbc.PreparedStatement",
    "com.mysql.cj.jdbc.ServerPreparedStatement",
    "com.mysql.cj.jdbc.ClientPreparedStatement",
    "com.mysql.cj.JdbcCallableStatement",
    "com.mysql.jdbc.CallableStatement",
    "com.mysql.jdbc.jdbc1.CallableStatement",
    "com.mysql.jdbc.jdbc2.CallableStatement",
    "com.mysql.cj.jdbc.CallableStatement",
    "oracle.jdbc.driver.OracleCallableStatementWrapper",
    "oracle.jdbc.driver.OraclePreparedStatementWrapper",
    // covers hsqldb
    "org.hsqldb.jdbc.JDBCPreparedStatement",
    "org.hsqldb.jdbc.jdbcPreparedStatement",
    "org.hsqldb.jdbc.JDBCCallableStatement",
    "org.hsqldb.jdbc.jdbcCallableStatement",
    // should cover derby
    "org.apache.derby.impl.jdbc.EmbedPreparedStatement",
    "org.apache.derby.impl.jdbc.EmbedCallableStatement",
    // hive
    "org.apache.hive.jdbc.HivePreparedStatement",
    "org.apache.hive.jdbc.HiveCallableStatement",
    "org.apache.phoenix.jdbc.PhoenixPreparedStatement",
    "org.apache.pinot.client.PinotPreparedStatement",
    // covers h2
    "org.h2.jdbc.JdbcPreparedStatement",
    "org.h2.jdbc.JdbcCallableStatement",
    // GridGain's fork of H2
    "org.gridgain.internal.h2.jdbc.JdbcPreparedStatement",
    "org.gridgain.internal.h2.jdbc.JdbcCallableStatement",
    // covers mariadb
    "org.mariadb.jdbc.JdbcPreparedStatement",
    "org.mariadb.jdbc.JdbcCallableStatement",
    "org.mariadb.jdbc.MariaDbServerPreparedStatement",
    "org.mariadb.jdbc.MariaDbClientPreparedStatement",
    "org.mariadb.jdbc.MySQLPreparedStatement",
    "org.mariadb.jdbc.MySQLCallableStatement",
    "org.mariadb.jdbc.MySQLServerSidePreparedStatement",
    // MariaDB Connector/J v2.x
    "org.mariadb.jdbc.ServerSidePreparedStatement",
    "org.mariadb.jdbc.ClientSidePreparedStatement",
    // MariaDB Connector/J v3.x
    "org.mariadb.jdbc.ServerPreparedStatement",
    "org.mariadb.jdbc.ClientPreparedStatement",
    // should completely cover postgresql
    "org.postgresql.jdbc1.PreparedStatement",
    "org.postgresql.jdbc1.CallableStatement",
    "org.postgresql.jdbc1.Jdbc1PreparedStatement",
    "org.postgresql.jdbc1.Jdbc1CallableStatement",
    "org.postgresql.jdbc2.PreparedStatement",
    "org.postgresql.jdbc2.CallableStatement",
    "org.postgresql.jdbc2.AbstractJdbc2Statement",
    "org.postgresql.jdbc2.Jdbc2PreparedStatement",
    "org.postgresql.jdbc2.Jdbc2CallableStatement",
    "org.postgresql.jdbc3.AbstractJdbc3Statement",
    "org.postgresql.jdbc3.Jdbc3PreparedStatement",
    "org.postgresql.jdbc3.Jdbc3CallableStatement",
    "org.postgresql.jdbc3g.AbstractJdbc3gStatement",
    "org.postgresql.jdbc3g.Jdbc3gPreparedStatement",
    "org.postgresql.jdbc3g.Jdbc3gCallableStatement",
    "org.postgresql.jdbc4.AbstractJdbc4Statement",
    "org.postgresql.jdbc4.Jdbc4PreparedStatement",
    "org.postgresql.jdbc4.Jdbc4CallableStatement",
    "org.postgresql.jdbc.PgPreparedStatement",
    "org.postgresql.jdbc.PgCallableStatement",
    "postgresql.PreparedStatement",
    "postgresql.CallableStatement",
    // EDB version of postgresql
    "com.edb.jdbc.PgPreparedStatement",
    "com.edb.jdbc.PgCallableStatement",
    // should completely cover sqlite
    "org.sqlite.jdbc3.JDBC3PreparedStatement",
    "org.sqlite.jdbc4.JDBC4PreparedStatement",
    "org.sqlite.PrepStmt",
    // covers snowflake
    "net.snowflake.client.jdbc.SnowflakePreparedStatementV1",
    // vertica
    "com.vertica.jdbc.common.SPreparedStatement",
    // this covers apache calcite/drill plus the drill-all uber-jar
    "org.apache.calcite.avatica.AvaticaPreparedStatement",
    "oadd.org.apache.calcite.avatica.AvaticaPreparedStatement",
    // jtds (for SQL Server and Sybase)
    "net.sourceforge.jtds.jdbc.JtdsPreparedStatement",
    "net.sourceforge.jtds.jdbc.JtdsCallableStatement",
    // SAP HANA in-memory DB
    "com.sap.db.jdbc.PreparedStatementSapDB",
    "com.sap.db.jdbc.CallableStatementSapDB",
    // aws-mysql-jdbc
    "software.aws.rds.jdbc.mysql.shading.com.mysql.cj.jdbc.CallableStatement",
    "software.aws.rds.jdbc.mysql.shading.com.mysql.cj.jdbc.ClientPreparedStatement",
    "software.aws.rds.jdbc.mysql.shading.com.mysql.cj.jdbc.PreparedStatement",
    "software.aws.rds.jdbc.mysql.shading.com.mysql.cj.jdbc.ServerPreparedStatement",
    "software.aws.rds.jdbc.mysql.shading.com.mysql.cj.JdbcCallableStatement",
    // IBM Informix
    "com.informix.jdbc.IfxPreparedStatement",
    // Intersystems IRIS
    "com.intersystems.jdbc.IRISPreparedStatement",
    "com.intersystems.jdbc.IRISCallableStatement",
    // sybase
    "com.sybase.jdbc2.jdbc.SybPreparedStatement",
    "com.sybase.jdbc2.jdbc.SybCallableStatement",
    "com.sybase.jdbc4.jdbc.SybPreparedStatement",
    "com.sybase.jdbc4.jdbc.SybCallableStatement",
    // for testing purposes
    "test.TestPreparedStatement"
  };

  @Override
  public String configuredMatchingType() {
    // this won't match any class unless the property is set
    return InstrumenterConfig.get().getJdbcPreparedStatementClassName();
  }

  public PreparedStatementInstrumentation() {
    super("jdbc");
  }

  @Override
  public String[] knownMatchingTypes() {
    return CONCRETE_TYPES;
  }
}
