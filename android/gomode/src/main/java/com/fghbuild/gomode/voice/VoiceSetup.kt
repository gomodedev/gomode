// Resolves the active service into gateway endpoints, authorization, MCP tools, and voice context.
//
// Setup resolves webShell.voiceGateway.url against the service URL, discovers a compatible voice
// choice, fetches a token from tokenEndpoint for an external gateway, posts the offer, and opens
// the voice-gateway data channel. It declares the native call and server commands plus the catalog
// tools; direct selected-server MCP and frontend aliases are not advertised. Bounded sourced
// context goes in session.setup.context.text before the first response, and server instructions
// stay scoped to their originating server. Catalog calls carry explicit server, kind, and tool
// identity and use the owning service's credentials; pending calls keep their destination across
// selection.
//
// Cloud voice and device speech with a remote gateway use one conversation owner, independent of
// the selected page. Connecting pins the gateway sponsor, endpoint, voice choice, speaking voice,
// speech stage owners and models, and authorization; WebRTC recovery keeps them. Context from
// every connected server goes to the chosen gateway.
package com.fghbuild.gomode.voice

import android.webkit.CookieManager
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.SpeechStage
import com.fghbuild.gomode.sdk.v1.Display
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.service.VoiceTokenClient
import com.fghbuild.gomode.service.readInitialServiceContext
import com.fghbuild.gomode.service.resolveServiceURL
import com.fghbuild.gomode.service.serviceCatalogTools
import com.fghbuild.gomode.service.serviceOrigin
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import com.fghbuild.voicegateway.sdk.v2.ServiceAuthorization

/** VoiceSetupException reports a configuration or sign-in problem the user can act on. */
internal class VoiceSetupException(
    message: String,
    /** The speech stage whose gateway model is at fault, if any. */
    val stage: SpeechStage? = null,
) : Exception(message)

/** Reports settings that changed between preparing a voice session and connecting it. */
internal const val VOICE_SETTINGS_CHANGED = "Voice settings changed. Try connecting again."

// Foreground ownership starts at the user's connect action, before discovery can suspend.
internal fun initialVoiceCallerName(settings: SettingsState): String =
    settings.services.firstOrNull { it.id == settings.activeServiceId }?.label ?: "Go Mode"

/** VoiceSetup is the resolved service, gateway, and MCP state for one voice session. */
internal data class VoiceSetup(
    val serverName: String,
    val serverID: String?,
    val gatewayEndpointURL: String,
    val service: ServiceAuthorization?,
    /** The gateway voice setup; null when an on-device engine runs the language model. */
    val voice: GatewayVoiceSelection? = null,
    /** The gateway's advertised STUN URLs; WebRTC contacts no other STUN server. */
    val stunServers: List<String> = emptyList(),
    val tokenEndpointURL: String?,
    val gatewayHeaders: Map<String, String>,
    val mcpClient: McpClient?,
    val tools: List<ToolDescriptor>,
    val systemInstruction: String,
    val serviceContextText: String,
)

/** Returns a standalone voice setup for offline or direct on-device engine execution. */
internal fun standaloneVoiceSetup(): VoiceSetup =
    VoiceSetup(
        serverName = "Go Mode",
        serverID = null,
        gatewayEndpointURL = "",
        service = null,
        tokenEndpointURL = null,
        gatewayHeaders = emptyMap(),
        // Without an endpoint, no request reaches a server that could read a display.
        mcpClient = McpClient("", "2024-11-05", { null }, display = { null }),
        tools = emptyList(),
        systemInstruction = "",
        serviceContextText = "",
    )

/** Who owns the MCP client of a voice session's service. */
internal sealed interface VoiceSetupMcp {
    /** A shared ServiceCatalog owns every participant's MCP client, tools, and context streams. */
    data object Catalog : VoiceSetupMcp

    /** Setup prepares the active service's MCP client, whose requests declare [glassesDisplay]. */
    data class Service(
        val glassesDisplay: () -> Display?,
    ) : VoiceSetupMcp
}

/**
 * Resolves the active service for a voice session, with its MCP client owned as [mcp] says.
 *
 * Throws [VoiceSetupException] when the service cannot support voice, and lets
 * [VoiceMcpAuthChangedException] propagate when credentials change mid-setup.
 */
internal suspend fun prepareVoiceSetup(
    settingsRepository: SettingsRepository,
    settingsClient: ServiceSettingsClient,
    bearerTokenFor: (String) -> String?,
    mcp: VoiceSetupMcp,
    voiceTokenClient: VoiceTokenClient = VoiceTokenClient(),
    pinnedVoice: GatewayVoiceSelection? = null,
    capturedSettings: SettingsState? = null,
    sessionID: String? = null,
    ensureCurrent: () -> Unit = {},
): VoiceSetup {
    val settings = capturedSettings ?: settingsRepository.settings.value

    fun requireCurrent() {
        if (capturedSettings == null) ensureVoiceSettingsCurrent(settings, settingsRepository.settings.value)
        ensureCurrent()
    }
    requireCurrent()
    requireLoadedVoiceSettings(settings)
    if (settings.activeServiceURL.isBlank()) {
        throw VoiceSetupException("Service URL is not configured")
    }
    val serviceSettings = settingsClient.fetch(settings.activeServiceURL)
    val voiceGatewayURL = serviceSettings.webShell.voiceGateway.url
    if (voiceGatewayURL.isNullOrBlank()) {
        throw VoiceSetupException("Voice is not available for this service")
    }
    // Single active skill today. SKILL.md frontmatter activation across the
    // toolGroups catalog (progressive disclosure) is future work; see
    // docs/PLAN_GOMODE.md phase progressive-skills.
    val group =
        serviceSettings.webShell.toolGroups
            .orEmpty()
            .firstOrNull()
    if (group == null &&
        mcp is VoiceSetupMcp.Service
    ) {
        throw VoiceSetupException("Voice is not available for this service")
    }
    val mcpEndpointURL = group?.let { resolveServiceURL(settings.activeServiceURL, it.endpoint) }
    val gatewayEndpointURL = resolveServiceURL(settings.activeServiceURL, voiceGatewayURL)
    val externalGateway = serviceOrigin(gatewayEndpointURL) != serviceOrigin(settings.activeServiceURL)
    val tokenEndpoint = serviceSettings.webShell.voiceGateway.tokenEndpoint
    if (externalGateway && tokenEndpoint.isNullOrBlank()) {
        throw VoiceSetupException("External voice gateway requires a token endpoint")
    }
    val tokenEndpointURL =
        if (externalGateway) {
            resolveServiceURL(settings.activeServiceURL, requireNotNull(tokenEndpoint))
        } else {
            null
        }
    if (tokenEndpointURL != null &&
        serviceOrigin(tokenEndpointURL) != serviceOrigin(settings.activeServiceURL)
    ) {
        throw VoiceSetupException("Voice token endpoint must be hosted by the service")
    }
    if (mcp is VoiceSetupMcp.Service &&
        group?.authRequired == true &&
        mcpEndpointURL != null &&
        cookieFor(mcpEndpointURL).isNullOrBlank() &&
        bearerTokenFor(mcpEndpointURL).isNullOrBlank()
    ) {
        throw VoiceSetupException("Sign in to the hosted service before using voice")
    }
    if (!externalGateway &&
        serviceSettings.webShell.voiceGateway.authRequired == true &&
        cookieFor(gatewayEndpointURL).isNullOrBlank() &&
        bearerTokenFor(gatewayEndpointURL).isNullOrBlank()
    ) {
        throw VoiceSetupException("Sign in to the hosted service before using voice")
    }

    val gatewayHeaders = serviceAuthHeaders(gatewayEndpointURL, bearerTokenFor)
    val choices =
        if (usesGatewayVoice(settings)) {
            discoverVoiceChoices(settings.activeServiceURL, serviceSettings, bearerTokenFor)
        } else {
            null
        }
    val selection = choices?.resolve(settings, pinnedVoice)
    requireCurrent()

    val participant =
        when (mcp) {
            VoiceSetupMcp.Catalog -> {
                null
            }

            is VoiceSetupMcp.Service -> {
                prepareServiceMcp(
                    settings.activeServiceURL,
                    serviceSettings,
                    bearerTokenFor,
                    mcp.glassesDisplay,
                    sessionID,
                )
            }
        }
    val client = participant?.client
    val systemInstruction =
        participant?.instructions.orEmpty()
    val tools = (participant?.tools ?: serviceCatalogTools) + nativeVoiceTools(settings)
    val serviceContextText =
        if (participant == null) {
            // The conversation's item streams own setup and subsequent context.
            ""
        } else {
            readInitialServiceContext(participant.client, sessionID)
        }
    val service = tokenEndpointURL?.let { voiceTokenClient.fetch(it, cookieFor(it), bearerTokenFor(it)) }
    requireCurrent()
    if (gatewayHeaders != serviceAuthHeaders(gatewayEndpointURL, bearerTokenFor)) {
        throw VoiceSetupException("Service authentication changed. Try connecting again.")
    }
    return VoiceSetup(
        serverName = serviceSettings.service,
        serverID = settings.activeServiceId,
        gatewayEndpointURL = gatewayEndpointURL,
        voice = selection,
        stunServers = choices?.catalog?.stunServers.orEmpty(),
        service = service,
        tokenEndpointURL = tokenEndpointURL,
        gatewayHeaders = gatewayHeaders,
        mcpClient = client,
        tools = tools,
        systemInstruction = systemInstruction,
        serviceContextText = serviceContextText,
    )
}

internal fun cookieFor(url: String): String? = CookieManager.getInstance().getCookie(url)

internal fun serviceAuthHeaders(
    url: String,
    bearerTokenFor: (String) -> String?,
): Map<String, String> =
    buildMap {
        cookieFor(url)?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        bearerTokenFor(url)?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
    }

// Setup owns a snapshot: later settings must not change the provider of an in-flight attempt.
internal fun ensureVoiceSettingsCurrent(
    captured: SettingsState,
    current: SettingsState,
) {
    if (captured.activeServiceId != current.activeServiceId ||
        captured.activeServiceURL != current.activeServiceURL ||
        captured.voiceSetup != current.voiceSetup ||
        captured.gatewayVoiceSetups != current.gatewayVoiceSetups ||
        captured.speakingVoices != current.speakingVoices ||
        captured.synthesisVoices != current.synthesisVoices
    ) {
        throw VoiceSetupException(VOICE_SETTINGS_CHANGED)
    }
}
