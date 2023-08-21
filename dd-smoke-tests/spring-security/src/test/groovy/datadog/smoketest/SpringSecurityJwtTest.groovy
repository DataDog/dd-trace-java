package datadog.smoketest

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import okhttp3.Request
import okhttp3.Response

import java.security.KeyPair
import java.text.ParseException
import java.util.concurrent.TimeoutException
import java.util.stream.Collectors

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_REQUEST_SAMPLING

@CompileDynamic
class SpringSecurityJwtTest extends AbstractServerSmokeTest {

  static final RSAKey rsaJWK = new RSAKeyGenerator(2048).generate()

  @Override
  def logLevel() {
    return "debug"
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    KeyPair kp = rsaJWK.toKeyPair()
    String publicKeyStr = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded())

    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")

    String cp = buildClassPath()
    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll([
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_REQUEST_SAMPLING, 100),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
    ])
    command.addAll((String[]) [
      "-cp",
      cp,
      "-Dserver.port=${httpPort}",
      "-Dpublickey=" + publicKeyStr,
      "com.example.jwt.SecurityJwtDemoApplication"
    ])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    processBuilder.environment().clear()
    processBuilder
  }


  def "server starts"() {
    setup:
    String url = "http://localhost:${httpPort}/"
    def request = new Request.Builder().url(url).get().build()

    when:
    Response response = client.newCall(request).execute()

    then:
    response.code() == 401
  }


  def "read endpoint"() {
    setup:
    String url = "http://localhost:${httpPort}/read"
    def token = generateToken(rsaJWK)
    def request = new Request.Builder().url(url).header("Authorization", "Bearer " + token).get().build()

    when:
    Response response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == "SuccesfulRead for foo"
    hasTainted {
      it.value == 'foo' &&
      it.ranges[0].source.origin == 'http.request.header'
    }
  }

  private static String withSystemProperty(final String config, final Object value) {
    return "-Ddd.${config}=${value}"
  }

  private static String buildClassPath(){
    String cp = System.getProperty("java.class.path")
    String separator = System.getProperty("path.separator")
    String mainJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")

    String result = Arrays.stream(cp.split(":"))
    .filter(s ->
    s.contains("spring")
    || s.contains("jose")
    || s.contains("commons-codec")
    || s.contains("snakeyaml")
    ||s.contains("logback")
    ||s.contains("log4j")
    ||s.contains("slf4j")
    ||s.contains("jakarta.annotation")
    ||s.contains("tomcat")
    ||s.contains("fasterxml")
    ).collect(Collectors.joining(separator))

    return mainJar + ":" + result
  }

  private void hasTainted(final Closure<Boolean> matcher) {
    final slurper = new JsonSlurper()
    final tainteds = []
    try {
      processTestLogLines { String log ->
        final index = log.indexOf('tainted=')
        if (index >= 0) {
          final tainted = slurper.parse(new StringReader(log.substring(index + 8)))
          tainteds.add(tainted)
          if (matcher.call(tainted)) {
            return true
          }
        }
      }
    } catch (TimeoutException toe) {
      throw new AssertionError("No matching tainted found. Tainteds found: ${tainteds}")
    }
  }

  String generateToken(RSAKey rsaJWK) throws JOSEException, ParseException {

    JWSSigner signer = new RSASSASigner(rsaJWK)
    JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
    .subject("foo")
    .issuer("http://foobar.com")
    .audience("foobar")
    .claim("scope", "read")
    .claim("name", "Mr Foo Bar")
    .expirationTime(new Date(new Date().getTime() + 60 * 1000))
    .build()

    SignedJWT signedJWT = new SignedJWT(
    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJWK.getKeyID()).build(),
    claimsSet)

    signedJWT.sign(signer)

    String token = signedJWT.serialize()
    System.out.println("Token: " + token)

    return token
  }
}
