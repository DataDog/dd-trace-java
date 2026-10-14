package server

import datadog.trace.agent.test.utils.OkHttpUtils
import okhttp3.OkHttpClient
import okhttp3.Protocol

class IastVertxHttp1ServerLatestDepTest extends IastVertxHttpServerLatestDepTest {

  OkHttpClient client = OkHttpUtils.clientBuilder().protocols([Protocol.HTTP_1_1]).build()
}
