package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.api.config.TraceInstrumentationConfig.RDS_TAGS_ENABLED;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DECORATE;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.bootstrap.instrumentation.decorator.AwsRdsEndpoint;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WithConfigExtension.class)
class R2dbcDecoratorRdsEndpointTest {
  private static final String RDS_HOST =
      "orders.cluster-ro-c9akciq32bzq.us-east-1.rds.amazonaws.com";

  @Test
  void parsesRdsEndpointFromConnectionHostname() {
    ConnectionFactoryOptions options =
        ConnectionFactoryOptions.builder().option(ConnectionFactoryOptions.HOST, RDS_HOST).build();

    AwsRdsEndpoint endpoint = DECORATE.rdsEndpoint(options, DECORATE.dbHostname(options));

    assertNotNull(endpoint);
    assertSame(AwsRdsEndpoint.parse(RDS_HOST), endpoint);
  }

  @Test
  void hasNoEndpointForOtherHosts() {
    ConnectionFactoryOptions options =
        ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.HOST, "db.example.com")
            .build();

    assertNull(DECORATE.rdsEndpoint(options, DECORATE.dbHostname(options)));
  }

  @Test
  @WithConfig(key = RDS_TAGS_ENABLED, value = "false")
  void skipsRdsEndpointWhenTaggingIsDisabled() {
    ConnectionFactoryOptions options =
        ConnectionFactoryOptions.builder().option(ConnectionFactoryOptions.HOST, RDS_HOST).build();

    assertNull(DECORATE.rdsEndpoint(options, DECORATE.dbHostname(options)));
  }
}
