package datadog.communication.http

import com.google.common.truth.Truth
import okhttp3.Request
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class SafeRequestBuilderTest {
  Request.Builder testBuilder = new Request.Builder()

  @Test
  void "test adding bad header"() {
    def name = 'bad'
    def password = 'very-secret-password'
    IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException, {
      testBuilder.url("http:localhost").addHeader(name, "$password\n")
    })
    Truth.assertThat(ex).hasMessageThat().contains(name)
    Truth.assertThat(ex).hasMessageThat().doesNotContain(password)
  }
  @Test
  void "test adding bad header2"(){
    def name = '\u0019'
    def password = 'very-secret-password'
    IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException, {
      testBuilder.url("http:localhost").addHeader(name, "\u0080$password")
    })
    Truth.assertThat(ex).hasMessageThat().contains(name)
    Truth.assertThat(ex).hasMessageThat().doesNotContain(password)
  }
}
