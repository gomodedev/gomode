// MCP transport sends private request metadata and keeps decoder diagnostics out of model-visible errors.
//
// The shell calls server/discover, tools/list, tools/call, resources/list, resources/read, and
// POST-based SSE subscriptions/listen. server/discover capabilities decide which features run;
// unsupported ones are skipped. Subscription events are invalidations:
// notifications/resources/updated re-reads the resource, and notifications/resources/list_changed
// re-reads the list.
//
// Every request reads the display provider and declares a present display through the Go Mode
// display client capability extension, so glasses connected mid-session reach the next request.
package com.fghbuild.gomode.voice

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.fghbuild.gomode.sdk.v1.ClientExtensions
import com.fghbuild.gomode.sdk.v1.Display
import com.fghbuild.gomode.service.ServiceResourceClient
import com.fghbuild.gomode.service.ServiceResourceRead
import com.fghbuild.gomode.service.credentialedHTTPClient
import com.fghbuild.mcp.sdk.v1.ApiClient
import com.fghbuild.mcp.sdk.v1.ApiException
import com.fghbuild.mcp.sdk.v1.ClientCapabilities
import com.fghbuild.mcp.sdk.v1.ContentBlock
import com.fghbuild.mcp.sdk.v1.ContentType
import com.fghbuild.mcp.sdk.v1.Implementation
import com.fghbuild.mcp.sdk.v1.JSONPresence
import com.fghbuild.mcp.sdk.v1.JSONRPCNotification
import com.fghbuild.mcp.sdk.v1.JSONRPCRequest
import com.fghbuild.mcp.sdk.v1.Method
import com.fghbuild.mcp.sdk.v1.OpenAIUserLocation
import com.fghbuild.mcp.sdk.v1.PaginatedRequestParams
import com.fghbuild.mcp.sdk.v1.RequestMeta
import com.fghbuild.mcp.sdk.v1.ResourceDescriptor
import com.fghbuild.mcp.sdk.v1.ResourceTemplateDescriptor
import com.fghbuild.mcp.sdk.v1.ResourceTemplatesListResult
import com.fghbuild.mcp.sdk.v1.ResourcesListResult
import com.fghbuild.mcp.sdk.v1.ResourcesReadParams
import com.fghbuild.mcp.sdk.v1.ResourcesReadResult
import com.fghbuild.mcp.sdk.v1.Role
import com.fghbuild.mcp.sdk.v1.ServerDiscoverResult
import com.fghbuild.mcp.sdk.v1.SubscriptionFilter
import com.fghbuild.mcp.sdk.v1.SubscriptionsListenParams
import com.fghbuild.mcp.sdk.v1.ToolCallResult
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import com.fghbuild.mcp.sdk.v1.ToolsCallParams
import com.fghbuild.mcp.sdk.v1.ToolsListResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val SUBSCRIPTION_READ_TIMEOUT_MILLIS = 45_000L

private val idCounter = AtomicInteger(0)

internal class InvalidMcpResponse(
    cause: IllegalArgumentException,
) : IllegalStateException("Service returned an invalid MCP response. The request outcome is unknown.", cause) {
    init {
        Log.w("GoModeMcp", "Invalid MCP response", cause)
    }
}

/**
 * A tool result as the shell uses it. [structuredContent] is what the model receives. [userContent]
 * holds, in order, the content blocks whose audience is the user alone, which the connected glasses
 * show and the model never receives; see [userOnlyContent].
 */
data class McpToolResult(
    val structuredContent: JsonObject,
    val isError: Boolean = false,
    val userContent: List<UserContent> = emptyList(),
)

/**
 * Content of a tool result for the user alone: [Text], or an [Image] decoded from a PNG of at most
 * [MAX_USER_IMAGE_BYTES], sampled down to at most [MAX_USER_IMAGE_PX] pixels a side.
 */
sealed interface UserContent {
    data class Text(
        val text: String,
    ) : UserContent

    /** Equal only to an image of the same [image], since a Bitmap compares by identity. */
    data class Image(
        val image: Bitmap,
    ) : UserContent
}

// Bounds the PNG of a user-only image block; a 576 by 288 grey card is a few KiB.
internal const val MAX_USER_IMAGE_BYTES = 1 shl 20

// Bounds a decoded image's memory; every display is smaller, and each scales the image to fit.
internal const val MAX_USER_IMAGE_PX = 1024

/** The MIME type of the image blocks the shell draws on glasses that declare it. */
internal const val PNG_MIME_TYPE = "image/png"

/** [display] returns the display to declare on the next request, or null to declare none. */
class McpClient(
    endpointURL: String,
    private val protocolVersion: String,
    private val cookieProvider: () -> String?,
    private val display: () -> Display?,
    private val bearerTokenProvider: () -> String? = { null },
) : ServiceResourceClient {
    private val endpointURL = endpointURL.trimEnd('/')
    private val api = ApiClient(this.endpointURL, httpClient = credentialedHTTPClient)
    private val subscriptionAPI = ApiClient(this.endpointURL, httpClient = newSubscriptionHTTPClient())
    private val json = Json { ignoreUnknownKeys = true }
    private var toolsByName: Map<String, ToolDescriptor> = emptyMap()
    private val clientInfo = Implementation(name = "gomode-android", version = "1.0.0")

    private fun requestMeta(
        sessionID: String? = null,
        locale: String? = null,
    ): RequestMeta =
        RequestMeta(
            protocolVersion = protocolVersion,
            clientInfo = clientInfo,
            clientCapabilities = clientCapabilities(),
            locale = locale,
            session = sessionID,
            userAgent = "${clientInfo.name}/${clientInfo.version}",
            userLocation = OpenAIUserLocation(timezone = ZoneId.systemDefault().id),
        )

    private fun clientCapabilities(): ClientCapabilities {
        val display = display() ?: return ClientCapabilities()
        return ClientCapabilities(extensions = json.encodeToJsonElement(ClientExtensions(display = display)).jsonObject)
    }

    private fun cookieHeaders(): Map<String, String> =
        cookieProvider()?.takeIf { it.isNotBlank() }?.let { mapOf("Cookie" to it) } ?: emptyMap()

    private suspend inline fun <reified T> request(
        method: Method,
        params: JsonElement,
        name: String? = null,
        paramHeaders: Map<String, String> = emptyMap(),
    ): T {
        val headers = mcpHeaders(method, name, paramHeaders)
        val response =
            try {
                api.mcp(
                    req =
                        JSONRPCRequest(
                            jsonrpc = "2.0",
                            id = JSONPresence.Value(JsonPrimitive(idCounter.incrementAndGet())),
                            method = method,
                            params = JSONPresence.Value(params),
                        ),
                    headers = headers,
                )
            } catch (e: SerializationException) {
                throw InvalidMcpResponse(e)
            } catch (e: ApiException) {
                // The generated transport uses UNKNOWN for undecodable HTTP errors.
                // Preserve authentication status without forwarding its raw body.
                if (e.code != "UNKNOWN") throw e
                Log.w("GoModeMcp", "Unreadable MCP HTTP error", e)
                throw ApiException(e.statusCode, e.code, "MCP request failed (HTTP ${e.statusCode}).").apply {
                    initCause(e)
                }
            }
        val rpcError = response.error
        if (rpcError != null) throw ApiException(200, rpcError.code.toString(), rpcError.message)
        val result = (response.result as? JSONPresence.Value)?.value ?: error("Missing result in MCP response")
        return try {
            json.decodeFromJsonElement(result)
        } catch (e: SerializationException) {
            throw InvalidMcpResponse(e)
        }
    }

    override suspend fun serverDiscover(sessionID: String?): ServerDiscoverResult =
        request(
            method = Method.ServerDiscover,
            params = buildJsonObject { put("_meta", json.encodeToJsonElement(requestMeta(sessionID))) },
        )

    suspend fun serverInstructions(sessionID: String?): String = serverDiscover(sessionID).instructions.orEmpty()

    suspend fun listTools(sessionID: String? = null): List<ToolDescriptor> {
        val tools = mutableListOf<ToolDescriptor>()
        var cursor: String? = null
        do {
            val result =
                request<ToolsListResult>(
                    method = Method.ToolsList,
                    params =
                        json.encodeToJsonElement(
                            PaginatedRequestParams(
                                _meta = requestMeta(sessionID),
                                cursor = cursor,
                            ),
                        ),
                )
            tools += result.tools.orEmpty()
            cursor = result.nextCursor
        } while (!cursor.isNullOrEmpty())
        toolsByName = tools.associateBy { it.name }
        return tools
    }

    suspend fun callTool(
        name: String,
        args: JsonObject,
        sessionID: String?,
        locale: String?,
    ): McpToolResult {
        val result =
            request<ToolCallResult>(
                method = Method.ToolsCall,
                params =
                    json.encodeToJsonElement(
                        ToolsCallParams(
                            _meta =
                                requestMeta(
                                    sessionID = sessionID,
                                    locale = locale,
                                ),
                            name = name,
                            arguments = JSONPresence.Value(args),
                        ),
                    ),
                name = name,
                paramHeaders = mcpParamHeaders(toolsByName[name], args),
            )
        val content = result.content.orEmpty()
        val structuredContent =
            (result.structuredContent as? JSONPresence.Value)?.value?.jsonObject
                ?: textContentAsStructuredError(content)
        return McpToolResult(
            structuredContent = structuredContent,
            isError = result.isError ?: false,
            userContent = userOnlyContent(content),
        )
    }

    override suspend fun listResources(sessionID: String?): List<ResourceDescriptor> {
        val resources = mutableListOf<ResourceDescriptor>()
        var cursor: String? = null
        do {
            val result =
                request<ResourcesListResult>(
                    method = Method.ResourcesList,
                    params =
                        json.encodeToJsonElement(
                            PaginatedRequestParams(
                                _meta = requestMeta(sessionID),
                                cursor = cursor,
                            ),
                        ),
                )
            resources += result.resources.orEmpty()
            cursor = result.nextCursor
        } while (!cursor.isNullOrEmpty())
        return resources
    }

    suspend fun listResourceTemplates(sessionID: String?): List<ResourceTemplateDescriptor> {
        val templates = mutableListOf<ResourceTemplateDescriptor>()
        var cursor: String? = null
        do {
            val result =
                request<ResourceTemplatesListResult>(
                    method = Method.ResourceTemplatesList,
                    params =
                        json.encodeToJsonElement(
                            PaginatedRequestParams(
                                _meta = requestMeta(sessionID),
                                cursor = cursor,
                            ),
                        ),
                )
            templates += result.resourceTemplates.orEmpty()
            cursor = result.nextCursor
        } while (!cursor.isNullOrEmpty())
        return templates
    }

    override suspend fun readResource(
        uri: String,
        sessionID: String?,
    ): ServiceResourceRead =
        ServiceResourceRead(
            uri,
            request<ResourcesReadResult>(
                method = Method.ResourcesRead,
                params =
                    json.encodeToJsonElement(
                        ResourcesReadParams(
                            _meta = requestMeta(sessionID),
                            uri = uri,
                        ),
                    ),
                name = uri,
            ),
        )

    override fun listenSubscriptions(
        notifications: SubscriptionFilter,
        sessionID: String?,
    ): Flow<JSONRPCNotification> =
        subscriptionAPI.stream(
            path = "",
            method = "POST",
            body =
                json.encodeToString(
                    JSONRPCRequest(
                        jsonrpc = "2.0",
                        id = JSONPresence.Value(JsonPrimitive(idCounter.incrementAndGet())),
                        method = Method.SubscriptionsListen,
                        params =
                            JSONPresence.Value(
                                json.encodeToJsonElement(
                                    SubscriptionsListenParams(
                                        _meta = requestMeta(sessionID),
                                        notifications = notifications,
                                    ),
                                ),
                            ),
                    ),
                ),
            headers = mcpHeaders(Method.SubscriptionsListen),
            decode = { json.decodeFromString<JSONRPCNotification>(it) },
        )

    private fun mcpHeaders(
        method: Method,
        name: String? = null,
        paramHeaders: Map<String, String> = emptyMap(),
    ): Map<String, String> =
        buildMap {
            putAll(cookieHeaders())
            bearerTokenProvider()?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
            put("Mcp-Protocol-Version", protocolVersion)
            put("Mcp-Method", method.value)
            if (name != null) put("Mcp-Name", name)
            putAll(paramHeaders)
        }
}

internal fun newSubscriptionHTTPClient(): OkHttpClient =
    credentialedHTTPClient
        .newBuilder()
        .readTimeout(SUBSCRIPTION_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .build()

// The model never receives a block for the user alone, glasses or not.
private fun textContentAsStructuredError(content: List<ContentBlock>): JsonObject {
    val text =
        content.firstNotNullOfOrNull { block ->
            block.takeIf { it.type == ContentType.Text && !it.isUserOnly() }?.text
        } ?: return JsonObject(emptyMap())
    return buildJsonObject { put("error", text) }
}

/**
 * Of the blocks of [content] whose audience is exactly the user, the last text and the last PNG
 * image, in their order: a display shows the last of them it draws, so these serve every display.
 * Only that image is decoded, and it is skipped when its encoding is broken or it is too large.
 */
internal fun userOnlyContent(content: List<ContentBlock>): List<UserContent> {
    val text = content.indexOfLast { it.isUserOnly() && it.type == ContentType.Text && it.text != null }
    val image =
        content.indexOfLast {
            it.isUserOnly() && it.type == ContentType.Image && it.mimeType == PNG_MIME_TYPE && it.data != null
        }
    return listOf(text, image).filter { it >= 0 }.sorted().mapNotNull { i ->
        val block = content[i]
        if (i == text) block.text?.let(UserContent::Text) else block.data?.let(::decodeUserImage)
    }
}

private fun ContentBlock.isUserOnly(): Boolean = annotations?.audience == listOf(Role.User)

private fun decodeUserImage(data: String): UserContent.Image? {
    // Base64 holds 3 bytes in 4 characters; refuse a larger block before decoding it.
    if (data.length / 4 * 3 > MAX_USER_IMAGE_BYTES) return null
    val png =
        try {
            Base64.decode(data, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            Log.w("GoModeMcp", "User image block is not base64", e)
            return null
        }
    if (png.size > MAX_USER_IMAGE_BYTES) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType != PNG_MIME_TYPE) {
        Log.w("GoModeMcp", "User image block is not a PNG")
        return null
    }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_USER_IMAGE_PX) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(png, 0, png.size, options)?.let(UserContent::Image)
}

private fun mcpParamHeaders(
    tool: ToolDescriptor?,
    args: JsonObject,
): Map<String, String> {
    val inputSchema = tool?.inputSchema?.jsonObject ?: return emptyMap()
    val headers = mutableMapOf<String, String>()
    collectMcpParamHeaders(inputSchema, args, headers)
    return headers
}

private fun collectMcpParamHeaders(
    schema: JsonObject,
    args: JsonElement?,
    headers: MutableMap<String, String>,
) {
    val headerName = schema["x-mcp-header"]?.jsonPrimitive?.content
    if (headerName != null && args != null && args !is JsonNull) {
        headers["Mcp-Param-$headerName"] = encodeMcpHeaderValue(args.jsonPrimitive.content)
    }
    val properties = schema["properties"]?.jsonObject ?: return
    val argObject = args as? JsonObject ?: return
    properties.forEach { (key, child) ->
        val childSchema = child as? JsonObject ?: return@forEach
        collectMcpParamHeaders(childSchema, argObject[key], headers)
    }
}

private fun encodeMcpHeaderValue(value: String): String {
    if (isPlainMcpHeaderValue(value)) return value
    val encoded = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return "=?base64?$encoded?="
}

private fun isPlainMcpHeaderValue(value: String): Boolean {
    if (value.startsWith("=?base64?") && value.endsWith("?=")) return false
    if (value.trim() != value) return false
    return value.all { it == ' ' || it in '!'..'~' }
}
