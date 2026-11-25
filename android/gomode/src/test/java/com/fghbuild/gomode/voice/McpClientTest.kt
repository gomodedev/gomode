// Tests native MCP requests, tool result normalization, and SSE configuration.
package com.fghbuild.gomode.voice

import android.graphics.Bitmap
import android.util.Base64
import com.fghbuild.gomode.glasses.G2_DISPLAY
import com.fghbuild.gomode.glasses.HALO_DISPLAY
import com.fghbuild.gomode.sdk.v1.Display
import com.fghbuild.mcp.sdk.v1.NotificationMethod
import com.fghbuild.mcp.sdk.v1.RequestMeta
import com.fghbuild.mcp.sdk.v1.ResourcesReadParams
import com.fghbuild.mcp.sdk.v1.SubscriptionFilter
import com.fghbuild.mcp.sdk.v1.SubscriptionsListenParams
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class McpClientTest {
    @Test
    fun `resource reads and subscriptions send the current device timezone`() =
        runTest {
            val original = TimeZone.getDefault()
            val json = Json { ignoreUnknownKeys = true }
            try {
                MockWebServer().use { server ->
                    server.start()
                    val client = McpClient(server.url("/mcp").toString(), "2026-07-28", { null }, display = { null })
                    TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
                    server.enqueue(
                        MockResponse().setBody(
                            """{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","contents":[],
                            "ttlMs":0,"cacheScope":"private"}}""",
                        ),
                    )
                    client.readResource("host://usage", "conversation-one")
                    val readBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                    val read = json.decodeFromJsonElement<ResourcesReadParams>(requireNotNull(readBody["params"]))
                    assertEquals("America/Toronto", read._meta.userLocation?.timezone)
                    assertEquals(null, read._meta.locale)
                    assertEquals("conversation-one", read._meta.session)

                    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
                    repeat(2) {
                        server.enqueue(
                            MockResponse()
                                .setHeader("Content-Type", "text/event-stream")
                                .setBody(
                                    "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/subscriptions/acknowledged\",\"params\":{}}\n\n",
                                ),
                        )
                        client
                            .listenSubscriptions(
                                SubscriptionFilter(resourceSubscriptions = listOf("host://usage")),
                                "conversation-one",
                            ).first()
                        val listenBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                        val listen =
                            json.decodeFromJsonElement<SubscriptionsListenParams>(requireNotNull(listenBody["params"]))
                        assertEquals("Asia/Kolkata", listen._meta.userLocation?.timezone)
                        assertEquals(null, listen._meta.locale)
                        assertEquals("conversation-one", listen._meta.session)
                    }
                }
            } finally {
                TimeZone.setDefault(original)
            }
        }

    @Test
    fun `authoritative context reads validate private authority while other results stay advisory`() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val client = McpClient(server.url("/mcp").toString(), "2026-07-28", { null }, display = { null })
                val malformed =
                    listOf(
                        """{"uri":"fixture://context","epoch":"owner","version":"private-version-sentinel","dependencies":[]}""",
                        """{"uri":"fixture://context","epoch":"","version":1,"dependencies":[]}""",
                        """{"uri":"fixture://context","epoch":"owner","version":1,"dependencies":null}""",
                        """{"uri":"fixture://context","epoch":"owner","version":"1","dependencies":[]}""",
                        """{"uri":"fixture://context","epoch":"owner","version":-1,"dependencies":[]}""",
                        """{"uri":"fixture://context","epoch":"owner","version":9007199254740992,"dependencies":[]}""",
                        """{"uri":"fixture://context","epoch":"${"e".repeat(129)}","version":1,"dependencies":[]}""",
                        """{"uri":"fixture://context","epoch":"owner","version":1,"dependencies":[{"uris":${JsonArray(
                            List(51) { JsonPrimitive("x") },
                        )}}]}""",
                        """{"uri":"fixture://context","epoch":"owner","version":1,"dependencies":[{"uris":["${"u".repeat(
                            1025,
                        )}"]}]}""",
                        """{"uri":"fixture://context","epoch":"owner","version":1,"dependencies":[{"uris":["x"],"aspects":${JsonArray(
                            List(17) { JsonPrimitive("x") },
                        )}}]}""",
                        """{"uri":"fixture://context","epoch":"owner","version":1,"dependencies":[{"uris":["x"],"aspects":["${"a".repeat(
                            65,
                        )}"]}]}""",
                        """{"uri":"fixture://context","epoch":"owner","version":1,"dependencies":${JsonArray(
                            List(129) {
                                Json.parseToJsonElement("""{"uris":["host://one"]}""")
                            },
                        )}}""",
                        """{"uri":"fixture://context","epoch":"owner","version":1,"dependencies":[{"uris":null}]}""",
                        """{"uri":"fixture://context","epoch":"owner","version":1,"dependencies":[{"uris":[]}]}""",
                        """{"uri":"fixture://context","epoch":"owner","version":1,
                            "dependencies":[{"uris":["host://one"],"aspects":null}]}""",
                    )

                fun response(
                    uri: String,
                    metadata: String,
                ): MockResponse {
                    val body =
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put(
                                "result",
                                buildJsonObject {
                                    put("resultType", "complete")
                                    put("ttlMs", 0)
                                    put("cacheScope", "private")
                                    put(
                                        "_meta",
                                        buildJsonObject {
                                            put("com.fghbuild.gomode/context", Json.parseToJsonElement(metadata))
                                        },
                                    )
                                    put(
                                        "contents",
                                        JsonArray(
                                            listOf(
                                                buildJsonObject {
                                                    put("uri", uri)
                                                    put("text", "資料 public _meta intact 日本語")
                                                },
                                            ),
                                        ),
                                    )
                                },
                            )
                        }
                    return MockResponse().setBody(body.toString())
                }
                for (metadata in malformed) {
                    server.enqueue(response("fixture://context", metadata))
                    val error =
                        runCatching {
                            client.readResource(
                                "fixture://context",
                                "conversation",
                            )
                        }.exceptionOrNull()
                    assertNotNull("Accepted invalid authority: $metadata", error)
                    assertTrue(error?.message?.contains("private-version-sentinel") == false)
                    server.enqueue(response("fixture://catalog", metadata))
                    val ordinary = client.readResource("fixture://catalog", "conversation")
                    assertTrue(ordinary.context == null)
                    assertTrue(
                        ordinary.result.contents
                            .orEmpty()
                            .single()
                            .text
                            ?.contains("日本語") == true,
                    )
                }
                server.enqueue(
                    response(
                        "fixture://context",
                        """{"uri":"fixture://context","epoch":"owner","version":1e0,"dependencies":[]}""",
                    ),
                )
                val verified = client.readResource("fixture://context", "conversation")
                assertEquals(1L, checkNotNull(verified.context).version)
                assertTrue(checkNotNull(verified.context?.content).contains("資料 public _meta intact 日本語"))
                for (uri in listOf("host://ordinary", "fixture://catalog", "gomode://notifications")) {
                    server.enqueue(response(uri, malformed.first()))
                    client.readResource(uri, "conversation")
                }
                server.enqueue(
                    MockResponse().setBody(
                        """{"jsonrpc":"2.0","result":{"resultType":"complete","content":[],
                    "structuredContent":{"saved":true},"_meta":{"com.fghbuild.gomode/context":${malformed.first()}}}}""",
                    ),
                )
                val applied =
                    client.callTool(
                        "save",
                        Json.parseToJsonElement("{}").jsonObject,
                        "conversation",
                        locale = null,
                    )
                assertTrue(!applied.isError)
                assertEquals(JsonPrimitive(true), applied.structuredContent["saved"])
                assertTrue(!applied.structuredContent.toString().contains("private-version-sentinel"))
                assertEquals(malformed.size * 2 + 5, server.requestCount)
            }
        }

    @Test
    fun `request metadata preserves extensions and validates known fields`() {
        val input =
            """{"io.modelcontextprotocol/protocolVersion":"2026-07-28",""" +
                """"io.modelcontextprotocol/clientInfo":{"name":"client","version":"1"},""" +
                """"io.modelcontextprotocol/clientCapabilities":{},"openai/locale":"fr-CA",""" +
                """"progressToken":null,"example/future":{"nested":[true,42]}}"""
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<RequestMeta>(input)
        assertEquals("fr-CA", decoded.locale)
        assertEquals(Json.parseToJsonElement("""{"nested":[true,42]}"""), decoded.extra["example/future"])
        assertEquals(json.parseToJsonElement(input), json.parseToJsonElement(json.encodeToString(decoded)))
        for (invalid in listOf("42", "true", "null", "{}", "[]")) {
            val bad = input.replace("\"fr-CA\"", invalid)
            try {
                json.decodeFromString<RequestMeta>(bad)
                throw AssertionError("accepted locale $invalid")
            } catch (_: SerializationException) {
                // Each malformed known field must be rejected.
            }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the last user-only block is for the glasses and never for the model`() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val client = McpClient(server.url("/mcp").toString(), "2026-07-28", { null }, display = { null })

                suspend fun call(content: String): McpToolResult {
                    server.enqueue(
                        MockResponse().setBody(
                            """{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","content":[$content]}}""",
                        ),
                    )
                    return client.callTool("glance", JsonObject(emptyMap()), sessionID = null, locale = null)
                }

                fun userOnly(block: String) = block.dropLast(1) + ""","annotations":{"audience":["user"]}}"""

                fun image(
                    width: Int,
                    height: Int,
                    mimeType: String = "image/png",
                ): String {
                    val png = ByteArrayOutputStream()
                    Bitmap
                        .createBitmap(
                            width,
                            height,
                            Bitmap.Config.ARGB_8888,
                        ).compress(Bitmap.CompressFormat.PNG, 100, png)
                    val data = Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP)
                    return userOnly("""{"type":"image","data":"$data","mimeType":"$mimeType"}""")
                }
                val card = userOnly("""{"type":"text","text":"Glasses card"}""")
                val both =
                    """{"type":"text","text":"Both","annotations":{"audience":["user","assistant"],"priority":0.9}}"""

                // Only text the model may read becomes the result without structured content.
                val mixed = call("$card,${image(40, 20)},$both,$card")
                assertEquals(buildJsonObject { put("error", "Both") }, mixed.structuredContent)
                assertEquals(JsonObject(emptyMap()), call(card).structuredContent)
                // The last text and the last image, in their order, serve every display.
                val (shown, last) = mixed.userContent
                assertEquals(40 to 20, (shown as UserContent.Image).image.let { it.width to it.height })
                assertEquals(UserContent.Text("Glasses card"), last)
                assertEquals(2, mixed.userContent.size)
                val many =
                    call(
                        (1..40).joinToString(",") { "${image(it, 1)},${userOnly("""{"type":"text","text":"$it"}""")}" },
                    )
                assertEquals(2, many.userContent.size)
                assertEquals(40, (many.userContent[0] as UserContent.Image).image.width)
                assertEquals(UserContent.Text("40"), many.userContent[1])
                val sampled = call(image(4096, 64)).userContent.single() as UserContent.Image
                assertTrue("${sampled.image.width}", sampled.image.width <= MAX_USER_IMAGE_PX)

                // The shell skips a block it cannot show.
                val jpeg = image(4, 4, "image/jpeg")
                val broken = userOnly("""{"type":"image","data":"not base64!","mimeType":"image/png"}""")
                val audio = userOnly("""{"type":"audio","data":"AAAA","mimeType":"audio/wav"}""")
                assertEquals(listOf(UserContent.Text("Glasses card")), call("$jpeg,$card,$broken,$audio").userContent)
                assertEquals(emptyList<UserContent>(), call(both).userContent)
            }
        }

    @Test
    fun `each request declares the glasses display connected at that moment`() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                var display: Display? = null
                val client = McpClient(server.url("/mcp").toString(), "2026-07-28", { null }, display = { display })

                suspend fun capabilities(request: suspend () -> Unit): JsonObject {
                    server.enqueue(
                        MockResponse().setBody(
                            """{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","contents":[],
                            "content":[],"ttlMs":0,"cacheScope":"private"}}""",
                        ),
                    )
                    request()
                    val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                    val params = requireNotNull(body["params"]).jsonObject
                    val meta = requireNotNull(params["_meta"]).jsonObject
                    return requireNotNull(meta["io.modelcontextprotocol/clientCapabilities"]).jsonObject
                }

                suspend fun callTool() {
                    client.callTool("read_item", JsonObject(emptyMap()), sessionID = null, locale = null)
                }
                val halo =
                    Json.parseToJsonElement(
                        """{"com.fghbuild.gomode/display":
                        {"width":256,"height":256,"shape":"circle","colorSpace":"rgb","bitsPerPixel":4,
                        "imageTypes":["image/png"]}}""",
                    )

                val g2 =
                    Json.parseToJsonElement(
                        """{"com.fghbuild.gomode/display":
                        {"width":576,"height":288,"shape":"rectangle","colorSpace":"luminance","bitsPerPixel":4}}""",
                    )

                assertEquals(JsonObject(emptyMap()), capabilities(::callTool))
                display = HALO_DISPLAY
                assertEquals(halo, capabilities(::callTool)["extensions"])
                assertEquals(halo, capabilities { client.readResource("host://usage", null) }["extensions"])
                display = G2_DISPLAY
                assertEquals(g2, capabilities(::callTool)["extensions"])
                display = null
                assertEquals(JsonObject(emptyMap()), capabilities(::callTool))
            }
        }

    @Test
    fun `malformed MCP envelopes never expose private transport diagnostics`() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val client = McpClient(server.url("/mcp").toString(), "2026-07-28", { null }, display = { null })
                for (status in listOf(200, 401)) {
                    server.enqueue(
                        MockResponse().setResponseCode(status).setBody(
                            """{"_meta":{"private":"private-envelope-sentinel"},"result":BROKEN}""",
                        ),
                    )
                    val failure =
                        runCatching {
                            client.callTool(
                                "read_item",
                                Json.parseToJsonElement("{}").jsonObject,
                                sessionID = null,
                                locale = null,
                            )
                        }.exceptionOrNull()
                    assertNotNull(failure)
                    assertTrue(failure?.message?.contains("private-envelope-sentinel") == false)
                    assertNotNull(failure?.cause)
                    assertEquals(if (status == 200) 1 else 2, server.requestCount)
                    if (status == 401) {
                        assertEquals(401, (failure as com.fghbuild.mcp.sdk.v1.ApiException).statusCode)
                    }
                }
            }
        }

    @Test
    fun `tool results normalize content beside opaque metadata`() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val client = McpClient(server.url("/mcp").toString(), "2026-07-28", { null }, display = { null })
                val meta =
                    Json
                        .parseToJsonElement(
                            """{"example.org/dependencies":{"uris":["host://one"],"revision":7},
                        "other.example/opaque":[null,true,{"nested":"private transport value"}]}""",
                        ).jsonObject
                for (structured in listOf(false, true)) {
                    for (isError in listOf(false, true)) {
                        val structuredField = if (structured) """, "structuredContent":{"state":"ready"}""" else ""
                        server.enqueue(
                            MockResponse().setBody(
                                """
                                {"jsonrpc":"2.0","id":1,"result":{
                                "resultType":"complete","content":[{"type":"text","text":"Readable tool message"}],
                                "isError":$isError,"_meta":$meta$structuredField}}
                                """.trimIndent(),
                            ),
                        )
                        val result =
                            client.callTool(
                                "read_item",
                                Json.parseToJsonElement("{}").jsonObject,
                                sessionID = null,
                                locale = null,
                            )
                        assertEquals(isError, result.isError)
                        assertEquals(
                            Json.parseToJsonElement(
                                if (structured) """{"state":"ready"}""" else """{"error":"Readable tool message"}""",
                            ),
                            result.structuredContent,
                        )
                    }
                }
            }
        }

    @Test
    fun `tool arguments pass unchanged and HTTP success does not hide invalid params`() =
        runTest {
            val server = MockWebServer()
            val originalTimezone = TimeZone.getDefault()
            val originalLocale = Locale.getDefault()
            Locale.setDefault(Locale.CANADA_FRENCH)
            TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
            server.start()
            try {
                server.enqueue(
                    MockResponse().setBody(
                        """
                        {"jsonrpc":"2.0","id":1,"result":{
                          "resultType":"complete","content":[],"structuredContent":{"state":"waiting"}
                        }}
                        """.trimIndent(),
                    ),
                )
                val errorMessage = "invalid arguments: json: unknown field \"query\""
                server.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        """{"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":${Json.encodeToString(
                            errorMessage,
                        )}}}""",
                    ),
                )
                val client = McpClient(server.url("/mcp").toString(), "2026-07-28", { null }, display = { null })
                val validArgs = Json.parseToJsonElement("""{"item":6}""").jsonObject
                assertEquals(
                    "waiting",
                    client
                        .callTool("item_detail", validArgs, "conversation-one", locale = "en-GB")
                        .structuredContent["state"]
                        ?.jsonPrimitive
                        ?.content,
                )
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
                val generatedArgs = Json.parseToJsonElement("""{"query":"item 6"}""").jsonObject
                val failure =
                    runCatching {
                        client.callTool(
                            "item_detail",
                            generatedArgs,
                            "conversation-one",
                            locale = "en-GB",
                        )
                    }.exceptionOrNull()
                assertTrue(failure is com.fghbuild.mcp.sdk.v1.ApiException)
                assertEquals("-32602", (failure as com.fghbuild.mcp.sdk.v1.ApiException).code)
                assertEquals(errorMessage, failure?.message)
                for ((args, timezone) in listOf(validArgs to "America/Toronto", generatedArgs to "Asia/Kolkata")) {
                    val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    assertEquals("tools/call", body["method"]?.jsonPrimitive?.content)
                    val params = requireNotNull(body["params"]).jsonObject
                    assertEquals("item_detail", params["name"]?.jsonPrimitive?.content)
                    assertEquals(args, params["arguments"])
                    val meta = requireNotNull(params["_meta"]).jsonObject
                    assertEquals("en-GB", meta["openai/locale"]?.jsonPrimitive?.content)
                    assertEquals("conversation-one", meta["openai/session"]?.jsonPrimitive?.content)
                    assertEquals("gomode-android/1.0.0", meta["openai/userAgent"]?.jsonPrimitive?.content)
                    assertEquals(
                        Json.parseToJsonElement("""{"timezone":"$timezone"}"""),
                        params["_meta"]?.jsonObject?.get("openai/userLocation"),
                    )
                }
            } finally {
                Locale.setDefault(originalLocale)
                TimeZone.setDefault(originalTimezone)
                server.shutdown()
            }
        }

    @Test
    fun `MCP request sends bearer and cookie only to the original service`() =
        runBlocking {
            val service = MockWebServer()
            val other = MockWebServer()
            service.start()
            other.start()
            try {
                service.enqueue(
                    MockResponse()
                        .setResponseCode(307)
                        .setHeader("Location", other.url("/stolen")),
                )
                other.enqueue(MockResponse().setResponseCode(200).setBody(SERVER_DISCOVER_JSON))
                val client =
                    McpClient(
                        endpointURL = service.url("/mcp").toString(),
                        protocolVersion = "2026-07-28",
                        cookieProvider = { "session=secret" },
                        display = { null },
                        bearerTokenProvider = { "scoped-token" },
                    )

                assertTrue(runCatching { client.serverDiscover() }.isFailure)

                val request = service.takeRequest()
                assertEquals("session=secret", request.getHeader("Cookie"))
                assertEquals("Bearer scoped-token", request.getHeader("Authorization"))
                assertEquals(null, other.takeRequest(200, TimeUnit.MILLISECONDS))
            } finally {
                service.shutdown()
                other.shutdown()
            }
        }

    @Test
    fun `subscription client tolerates an idle SSE stream`() {
        assertEquals(45_000L, newSubscriptionHTTPClient().readTimeoutMillis.toLong())
        assertEquals(false, newSubscriptionHTTPClient().followRedirects)
        assertEquals(false, newSubscriptionHTTPClient().followSslRedirects)
    }

    @Test
    fun `server instructions request includes json rpc id`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody(SERVER_DISCOVER_JSON).setResponseCode(200))
                val client =
                    McpClient(
                        endpointURL = server.url("/mcp").toString(),
                        protocolVersion = "2026-07-28",
                        cookieProvider = { null },
                        display = { null },
                    )

                assertEquals("Use the tools.", client.serverInstructions("conversation-one"))

                val request = server.takeRequest()
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals("/mcp", request.path)
                assertEquals("2026-07-28", request.getHeader("Mcp-Protocol-Version"))
                assertEquals("server/discover", request.getHeader("Mcp-Method"))
                assertEquals("2.0", body["jsonrpc"]?.jsonPrimitive?.content)
                assertNotNull(body["id"])
                assertEquals("server/discover", body["method"]?.jsonPrimitive?.content)
                assertEquals(
                    "conversation-one",
                    body["params"]
                        ?.jsonObject
                        ?.get("_meta")
                        ?.jsonObject
                        ?.get("openai/session")
                        ?.jsonPrimitive
                        ?.content,
                )
                assertEquals(
                    null,
                    body["params"]
                        ?.jsonObject
                        ?.get("_meta")
                        ?.jsonObject
                        ?.get("openai/locale"),
                )
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `resources list request paginates`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody(RESOURCES_LIST_PAGE_1_JSON).setResponseCode(200))
                server.enqueue(MockResponse().setBody(RESOURCES_LIST_PAGE_2_JSON).setResponseCode(200))
                val client =
                    McpClient(
                        endpointURL = server.url("/mcp").toString(),
                        protocolVersion = "2026-07-28",
                        cookieProvider = { "session=abc" },
                        display = { null },
                    )

                val resources = client.listResources()

                assertEquals(listOf("service://items", "service://usage"), resources.map { it.uri })
                val firstRequest = server.takeRequest()
                val firstBody = Json.parseToJsonElement(firstRequest.body.readUtf8()).jsonObject
                assertEquals("/mcp", firstRequest.path)
                assertEquals("session=abc", firstRequest.getHeader("Cookie"))
                assertEquals("resources/list", firstRequest.getHeader("Mcp-Method"))
                assertEquals("resources/list", firstBody["method"]?.jsonPrimitive?.content)

                val secondRequest = server.takeRequest()
                val secondBody = Json.parseToJsonElement(secondRequest.body.readUtf8()).jsonObject
                assertEquals("resources/list", secondRequest.getHeader("Mcp-Method"))
                assertEquals(
                    "next",
                    secondBody["params"]
                        ?.jsonObject
                        ?.get("cursor")
                        ?.jsonPrimitive
                        ?.content,
                )
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `resource templates list request uses resource template method`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody(RESOURCE_TEMPLATES_LIST_JSON).setResponseCode(200))
                val client =
                    McpClient(
                        endpointURL = server.url("/mcp").toString(),
                        protocolVersion = "2026-07-28",
                        cookieProvider = { null },
                        display = { null },
                    )

                val templates = client.listResourceTemplates(sessionID = null)

                assertEquals(listOf("item"), templates.map { it.name })
                val request = server.takeRequest()
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals("resources/templates/list", request.getHeader("Mcp-Method"))
                assertEquals("resources/templates/list", body["method"]?.jsonPrimitive?.content)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `resource read request sends resource uri`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody(RESOURCE_READ_JSON).setResponseCode(200))
                val client =
                    McpClient(
                        endpointURL = server.url("/mcp").toString(),
                        protocolVersion = "2026-07-28",
                        cookieProvider = { null },
                        display = { null },
                    )

                val result = client.readResource("service://items")

                assertEquals("[]", requireNotNull(result.result.contents).single().text)
                val request = server.takeRequest()
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals("resources/read", request.getHeader("Mcp-Method"))
                assertEquals("service://items", request.getHeader("Mcp-Name"))
                assertEquals("resources/read", body["method"]?.jsonPrimitive?.content)
                assertEquals(
                    "service://items",
                    body["params"]
                        ?.jsonObject
                        ?.get("uri")
                        ?.jsonPrimitive
                        ?.content,
                )
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `subscriptions listen posts sse request and parses notifications`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(SUBSCRIPTION_SSE)
                        .setResponseCode(200),
                )
                val client =
                    McpClient(
                        endpointURL = server.url("/mcp").toString(),
                        protocolVersion = "2026-07-28",
                        cookieProvider = { null },
                        display = { null },
                    )

                val events =
                    client
                        .listenSubscriptions(
                            SubscriptionFilter(resourceSubscriptions = listOf("service://items")),
                        ).take(2)
                        .toList()

                assertEquals(NotificationMethod.SubscriptionsAcknowledged, events[0].method)
                assertEquals(NotificationMethod.ResourcesUpdated, events[1].method)
                val request = server.takeRequest()
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val params = body["params"]?.jsonObject
                val resourceSubscriptions =
                    params
                        ?.get("notifications")
                        ?.jsonObject
                        ?.get("resourceSubscriptions")
                        ?.jsonArray
                        ?.map { it.jsonPrimitive.content }
                assertEquals("POST", request.method)
                assertEquals("text/event-stream", request.getHeader("Accept"))
                assertEquals("subscriptions/listen", request.getHeader("Mcp-Method"))
                assertEquals("subscriptions/listen", body["method"]?.jsonPrimitive?.content)
                assertEquals(listOf("service://items"), resourceSubscriptions)
            } finally {
                server.shutdown()
            }
        }

    private companion object {
        const val SERVER_DISCOVER_JSON = """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "result": {
                "resultType": "complete",
                "supportedVersions": ["2026-07-28"],
                "capabilities": {"tools": {"listChanged": true}},
                "serverInfo": {"name": "test", "version": "1.0.0"},
                "instructions": "Use the tools.",
                "ttlMs": 1000,
                "cacheScope": "private"
              }
            }
        """

        const val RESOURCES_LIST_PAGE_1_JSON = """
            {
              "jsonrpc": "2.0",
              "id": 2,
              "result": {
                "resultType": "complete",
                "nextCursor": "next",
                "resources": [
                  {"uri": "service://items", "name": "items", "mimeType": "application/json"}
                ],
                "ttlMs": 1000,
                "cacheScope": "private"
              }
            }
        """

        const val RESOURCES_LIST_PAGE_2_JSON = """
            {
              "jsonrpc": "2.0",
              "id": 3,
              "result": {
                "resultType": "complete",
                "resources": [
                  {"uri": "service://usage", "name": "usage", "mimeType": "application/json"}
                ],
                "ttlMs": 1000,
                "cacheScope": "private"
              }
            }
        """

        const val RESOURCE_TEMPLATES_LIST_JSON = """
            {
              "jsonrpc": "2.0",
              "id": 4,
              "result": {
                "resultType": "complete",
                "resourceTemplates": [
                  {"name": "item", "uriTemplate": "service://items/{id}", "mimeType": "application/json"}
                ],
                "ttlMs": 1000,
                "cacheScope": "private"
              }
            }
        """

        const val RESOURCE_READ_JSON = """
            {
              "jsonrpc": "2.0",
              "id": 5,
              "result": {
                "resultType": "complete",
                "contents": [
                  {"uri": "service://items", "mimeType": "application/json", "text": "[]"}
                ],
                "ttlMs": 1000,
                "cacheScope": "private"
              }
            }
        """

        const val SUBSCRIPTION_SSE =
            "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/subscriptions/acknowledged\"," +
                "\"params\":{\"notifications\":{\"resourceSubscriptions\":[\"service://items\"]}}}\n\n" +
                ": keepalive\n\n" +
                "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/resources/updated\"," +
                "\"params\":{\"uri\":\"service://items\"}}\n\n"
    }
}
