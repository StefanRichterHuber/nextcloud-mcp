package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.mcp;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.stefanrichterhuber.nextcloudlib.runtime.models.NextcloudUserCredentials;
import io.github.stefanrichterhuber.nextcloudmcp.audit.MCPAudit;
import io.github.stefanrichterhuber.nextcloudmcp.config.AppConfig;
import io.github.stefanrichterhuber.nextcloudmcp.config.NextcloudConfig;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.UserRepository;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.UserRepository.UserAccessConfig;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.mcp.model.McpUiResourceCsp;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.mcp.model.UIResourceMeta;
import io.quarkiverse.mcp.server.MetaField;
import io.quarkiverse.mcp.server.MetaKey;
import io.quarkiverse.mcp.server.Resource;
import io.quarkiverse.mcp.server.ResourceContents;
import io.quarkiverse.mcp.server.TextResourceContents;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MCP tools and resource for the inline security-configuration UI.
 *
 * <p>
 * Implements an <em>MCP App</em> — an interactive HTML application embedded
 * directly in
 * the MCP response — that allows users to configure their personal file-access
 * restrictions
 * without leaving the chat interface. The specification is defined in
 * <a href=
 * "https://modelcontextprotocol.io/seps/1865-mcp-apps-interactive-user-interfaces-for-mcp">
 * SEP-1865: MCP Apps</a>.
 *
 * <h2>How it works</h2>
 * <ol>
 * <li>The LLM calls the {@code config-ui} tool. The tool response carries a
 * {@code ui.resourceUri} metadata field pointing to
 * {@value #RESOURCE_CONFIG_UI_NAME}
 * and returns the current {@link UserRepository.UserAccessConfig} as JSON so
 * the UI
 * can pre-populate its form fields.</li>
 * <li>The MCP client fetches the {@code ui://config} resource and renders the
 * HTML
 * application inside the chat window.</li>
 * <li>When the user submits the form, the UI calls the
 * {@code set-access-config} tool
 * with the updated settings, which persists them via
 * {@link UserRepository}.</li>
 * </ol>
 *
 */
@ApplicationScoped
public class ConfigMCP {
    public static final String TOOL_SET_CONFIG_NAME = "set-access-config";
    public static final String TOOL_CONFIG_TOOL_NAME = "config-ui";
    public static final String RESOURCE_CONFIG_UI_NAME = "ui://config";
    private static final String CONFIG_RESOURCE_META = "{ \"resourceUri\": \"" + RESOURCE_CONFIG_UI_NAME + "\" }";

    // https://github.com/modelcontextprotocol/ext-apps/blob/main/specification/draft/apps.mdx
    private static final String APP_MIME_TYPE = "text/html;profile=mcp-app"; // MUST be "text/html;profile=mcp-app"

    /**
     * Raw form data submitted by the MCP App UI.
     *
     * <p>
     * All fields arrive as strings because HTML form values are always strings.
     * {@link #toUserAccessConfig()} converts them to the typed
     * {@link UserAccessConfig} record expected by the repository.
     *
     * @param rootFolder          the root folder path restriction (may be empty).
     * @param filePatterns        comma-separated glob patterns (e.g.
     *                            {@code "*.md,*.txt"}).
     * @param contentText         string representation of a boolean
     *                            ({@code "true"/"false"}).
     * @param contentImage        string representation of a boolean
     *                            ({@code "true"/"false"}).
     * @param contentAudio        string representation of a boolean
     *                            ({@code "true"/"false"}).
     * @param calendarReadAccess  string representation of a boolean
     *                            ({@code "true"/"false"}).
     * @param calendarWriteAccess string representation of a boolean
     *                            ({@code "true"/"false"}).
     * @param contactAccess       string representation of a boolean
     *                            ({@code "true"/"false"}).
     */
    private record ConfigFromApp(String rootFolder, String filePatterns, String contentText, String contentImage,
            String contentAudio, String calendarReadAccess, String calendarWriteAccess, String contactAccess) {

        /**
         * Converts the raw string form data into a typed {@link UserAccessConfig}.
         *
         * <p>
         * The {@code filePatterns} string is split on commas; each token becomes one
         * entry in the resulting set.
         *
         * @return the equivalent {@link UserAccessConfig} ready for persistence.
         */
        public UserAccessConfig toUserAccessConfig() {
            final Set<String> patterns = filePatterns != null && !filePatterns.isEmpty()
                    ? Set.of(filePatterns.split(","))
                    : Collections.emptySet();
            final boolean textContent = contentText != null ? Boolean.parseBoolean(contentText) : false;
            final boolean imageContent = contentImage != null ? Boolean.parseBoolean(contentImage) : false;
            final boolean audioContent = contentAudio != null ? Boolean.parseBoolean(contentAudio) : false;
            final boolean calendarRead = calendarReadAccess != null ? Boolean.parseBoolean(calendarReadAccess) : false;
            final boolean calendarWrite = calendarWriteAccess != null ? Boolean.parseBoolean(calendarWriteAccess)
                    : false;
            final boolean contact = contactAccess != null ? Boolean.parseBoolean(contactAccess) : false;
            return new UserAccessConfig(rootFolder, patterns, textContent, imageContent, audioContent, calendarRead,
                    calendarWrite, contact);
        }
    }

    @Inject
    Template config;

    @Inject
    AppConfig appConfig;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    UserRepository userRepository;

    @Inject
    NextcloudConfig nextcloudConfig;

    @Inject
    MCPTool tool;

    /**
     * Provides the actual HTML for the MCP App to configure the access, including
     * all required metadata to allow loading further resources and calling back to
     * this app
     * 
     * @see https://github.com/anthropics/claude-ai-mcp/issues/40
     * @return HTML Document as text resource
     * @throws Exception
     */
    @Resource(uri = RESOURCE_CONFIG_UI_NAME)
    ResourceContents configResources() throws Exception {
        final String appRootUrl = appConfig.rootUrl();
        final TemplateInstance instance = config
                .data("href", appRootUrl);

        String html = instance.render();

        if (appConfig.fixResourceURIs()) {
            // Replace relative resource paths with absolute ones (especially for both cs
            // and js). Fix for claude-ai-mcp issue 40
            html = html.replace("/static/bundle/", appRootUrl + "/static/bundle/");
        }
        // see
        // https://github.com/modelcontextprotocol/ext-apps/blob/main/specification/draft/apps.mdx

        final String domain = null;
        final McpUiResourceCsp csp = new McpUiResourceCsp(List.of(appRootUrl), List.of(appRootUrl), List.of(),
                List.of(appRootUrl));
        final UIResourceMeta ui = new UIResourceMeta(csp, null, domain, true);

        final Map<MetaKey, Object> meta = new HashMap<>();
        meta.put(MetaKey.from("ui"), ui);

        // Files.write(Paths.get("full.html"), html.getBytes(StandardCharsets.UTF_8));

        return new TextResourceContents(RESOURCE_CONFIG_UI_NAME, html, APP_MIME_TYPE, meta);
    }

    public record ConfigResponse(String user, String server, UserAccessConfig config) {

    }

    /**
     * Tool to provide the metadata and initial user data for the Config MCP APP to
     * configure access restrictions
     * 
     * @return
     */
    @MetaField(name = "ui", type = MetaField.Type.JSON, value = CONFIG_RESOURCE_META)
    @Tool(name = TOOL_CONFIG_TOOL_NAME, title = "Configure Nextcloud MCP access settings", description = "MCP App to manage the configuration for the Nextcloud MCP plugin", structuredContent = true)
    @MCPAudit
    public ConfigResponse config() {
        tool.assertUserLoggedIn();

        final UserAccessConfig accessConfig = userRepository.getAccessConfigForCurrentUser()
                .orElse(new UserAccessConfig(null, null, false, false, false, false, false, false));

        NextcloudUserCredentials nextcloudUserCredentials = userRepository.getCredentialsForCurrentUser().get();
        ConfigResponse cr = new ConfigResponse(nextcloudUserCredentials.loginName(), nextcloudUserCredentials.server(),
                accessConfig);

        return cr;
    }

    /**
     * Tool to set the changed user settings for access restrinctions
     * 
     * @param config JSON String containing the new restrictions
     * @return
     */
    @Tool(name = TOOL_SET_CONFIG_NAME, title = "Set Nextcloud MCP access settings", description = "Set the access configuration for the Nextcloud MCP plugin. Only used internally by the config UI MCP App.")
    @MCPAudit
    public ToolResponse setAccessConfig(ConfigFromApp config) {
        try {
            tool.assertUserLoggedIn();
            userRepository.saveAccessConfigForCurrentUser(config.toUserAccessConfig());

            final ToolResponse response = ToolResponse.success("Access configuration set to: " + config);
            return response;
        } catch (Exception e) {
            if (e instanceof ToolCallException tce) {
                throw tce;
            }
            throw new ToolCallException("Failed to set access configuration", e);
        }

    }
}
