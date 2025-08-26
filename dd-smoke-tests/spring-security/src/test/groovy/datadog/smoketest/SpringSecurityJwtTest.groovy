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
import groovy.transform.CompileDynamic
import okhttp3.Request
import okhttp3.Response

import java.security.KeyPair
import java.text.ParseException
import java.util.stream.Collectors

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED

@CompileDynamic
class SpringSecurityJwtTest extends AbstractIastServerSmokeTest {
  static final RSAKey RSA_JWK = new RSAKeyGenerator(2048).generate()

  @Override
  ProcessBuilder createProcessBuilder() {
    KeyPair kp = RSA_JWK.toKeyPair()
    String publicKeyStr = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded())

    String cp = buildClassPath()
    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll([
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_DETECTION_MODE, 'FULL'),
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
    def token = generateToken(RSA_JWK)
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
