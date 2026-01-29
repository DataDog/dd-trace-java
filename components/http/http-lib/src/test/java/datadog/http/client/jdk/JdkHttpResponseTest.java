package datadog.http.client.jdk;

import static org.junit.jupiter.api.condition.JRE.JAVA_11;

import datadog.http.client.HttpResponseTest;
import org.junit.jupiter.api.condition.EnabledForJreRange;

@EnabledForJreRange(min = JAVA_11)
public class JdkHttpResponseTest extends HttpResponseTest {
}
