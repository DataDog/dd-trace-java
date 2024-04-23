//
//import com.amazonaws.SDKGlobalConfiguration
////import com.amazonaws.auth.AWSStaticCredentialsProvider
////import com.amazonaws.auth.AnonymousAWSCredentials
//import datadog.trace.agent.test.naming.VersionedNamingTestBase
////import datadog.trace.api.config.GeneralConfig
////import spock.lang.Shared
//
//
//abstract class SnsClientTest extends VersionedNamingTestBase {
//
////  def setup() {
////    System.setProperty(SDKGlobalConfiguration.ACCESS_KEY_SYSTEM_PROPERTY, "my-access-key")
////    System.setProperty(SDKGlobalConfiguration.SECRET_KEY_SYSTEM_PROPERTY, "my-secret-key")
////  }
//
////  @Override
////  protected void configurePreAgent() {
//////    super.configurePreAgent()
//////    // Set a service name that gets sorted early with SORT_BY_NAMES
//////    injectSysConfig(GeneralConfig.SERVICE_NAME, "A-service")
//////    injectSysConfig(GeneralConfig.DATA_STREAMS_ENABLED, isDataStreamsEnabled().toString())
////  }
//
////  @Shared
////  def credentialsProvider = new AWSStaticCredentialsProvider(new AnonymousAWSCredentials())
//
//  @Override
//  String operation() {
//    null
//  }
//
//  @Override
//  String service() {
//    null
//  }
//
////  boolean hasTimeInQueueSpan() {
////    false
////  }
//
////  abstract String expectedOperation(String awsService, String awsOperation)
//
////  abstract String expectedService(String awsService, String awsOperation)
//
//}
