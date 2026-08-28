package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 
 * UIResource
 * 
 * @param uri         Unique identifier for the UI resource. MUST use the
 *                    `ui://` URI scheme to distinguish UI resources from other
 *                    MCP resource types. Example "ui://weather-dashboard"
 * @param name        Human-readable display name for the UI resource. Used for
 *                    listing and identifying the resource in host interfaces.
 *                    Example: "Weather Dashboard"
 * @param description Optional description of the UI resource's purpose and
 *                    functionality. Provides context about what the UI does and
 *                    when to use it. Example: "Interactive weather
 *                    visualization with real-time updates"
 * @param mimeType    MIME type of the UI content. SHOULD be
 *                    `text/html;profile=mcp-app` for HTML-based UIs in the
 *                    initial MVP. Other content types are reserved for future
 *                    extensions. Example: "text/html;profile=mcp-app"
 * @param meta        Resource metadata for security and rendering
 *                    configuration. Includes Content Security Policy
 *                    configuration, dedicated domain settings, and visual
 *                    preferences.
 */
public record UIResource(String uri, String name, String description, String mimeType,
        @JsonProperty("_meta") Meta meta) {

    /**
     * Resource metadata for security and rendering
     * 
     * @param ui Resource metadata for security and rendering
     *           configuration. Includes Content Security Policy
     *           configuration, dedicated domain settings, and visual
     *           preferences.
     */
    public record Meta(@JsonProperty("ui") UIResourceMeta ui) {
    }

}