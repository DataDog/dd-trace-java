import datadog.trace.agent.test.InstrumentationSpecification
import java.sql.Connection
import java.sql.DriverManager
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName
import spock.lang.Shared

abstract class MySqlContainerSpecification extends InstrumentationSpecification {

  @Shared
  MySQLContainer mysql

  def setupSpec() {
    def image = DockerImageName.parse(System.getProperty("test.mysql.image", "mysql:8.0"))
      .asCompatibleSubstituteFor("mysql")
    mysql = new MySQLContainer(image)
      .withDatabaseName("corpus")
      .withUsername("corpus")
      .withPassword("corpus")
    mysql.addParameter("TC_MY_CNF", null)
    mysql.start()
  }

  def cleanupSpec() {
    mysql?.stop()
  }

  String baseUrl() {
    "jdbc:mysql://${mysql.host}:${mysql.getMappedPort(MySQLContainer.MYSQL_PORT)}/corpus"
  }

  String url(String... params) {
    def all = ["useSSL=false", "allowPublicKeyRetrieval=true"] + (params as List)
    return baseUrl() + "?" + all.findAll { it }.join("&")
  }

  Properties credentials() {
    def props = new Properties()
    props.setProperty("user", "corpus")
    props.setProperty("password", "corpus")
    return props
  }

  Connection connect(String url, Properties props = credentials()) {
    return DriverManager.getConnection(url, props)
  }
}
