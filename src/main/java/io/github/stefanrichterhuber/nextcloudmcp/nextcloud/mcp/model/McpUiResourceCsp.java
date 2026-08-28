package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.mcp.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Content Security Policy configuration for an MCP UI resource.
 * <p>
 * Each component describes a set of allowed origins that is translated into one
 * or more CSP directives on the iframe hosting the UI resource. Every component
 * is optional in the wire format; an omitted or empty list is the secure
 * default
 * and means "nothing is allowed" for the corresponding directive.
 * <p>
 * Instances are immutable: {@code null} components are normalized to empty
 * lists
 * and all lists are defensively copied, so accessors never return {@code null}.
 *
 * @param connectDomains  origins for network requests (fetch/XHR/WebSocket).
 *                        <ul>
 *                        <li>Empty or omitted = no external connections (secure
 *                        default)</li>
 *                        <li>Maps to the CSP {@code connect-src} directive</li>
 *                        </ul>
 *                        Example:
 *                        {@code ["https://api.weather.com", "wss://realtime.service.com"]}
 * @param resourceDomains origins for static resources (images, scripts,
 *                        stylesheets,
 *                        fonts, media).
 *                        <ul>
 *                        <li>Empty or omitted = no external resources (secure
 *                        default)</li>
 *                        <li>Wildcard subdomains are supported:
 *                        {@code https://*.example.com}</li>
 *                        <li>Maps to the CSP {@code img-src},
 *                        {@code script-src},
 *                        {@code style-src}, {@code font-src} and
 *                        {@code media-src} directives</li>
 *                        </ul>
 *                        Example:
 *                        {@code ["https://cdn.jsdelivr.net", "https://*.cloudflare.com"]}
 * @param frameDomains    origins for nested iframes.
 *                        <ul>
 *                        <li>Empty or omitted = no nested iframes allowed
 *                        ({@code frame-src 'none'})</li>
 *                        <li>Maps to the CSP {@code frame-src} directive</li>
 *                        </ul>
 *                        Example:
 *                        {@code ["https://www.youtube.com", "https://player.vimeo.com"]}
 * @param baseUriDomains  allowed base URIs for the document.
 *                        <ul>
 *                        <li>Empty or omitted = only the same origin is allowed
 *                        ({@code base-uri 'self'})</li>
 *                        <li>Maps to the CSP {@code base-uri} directive</li>
 *                        </ul>
 *                        Example: {@code ["https://cdn.example.com"]}
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpUiResourceCsp(
        @JsonProperty("connectDomains") List<String> connectDomains,
        @JsonProperty("resourceDomains") List<String> resourceDomains,
        @JsonProperty("frameDomains") List<String> frameDomains,
        @JsonProperty("baseUriDomains") List<String> baseUriDomains) {

    /** The most restrictive policy: nothing external is allowed. */
    private static final McpUiResourceCsp EMPTY = new McpUiResourceCsp(null, null, null, null);

    /**
     * Canonical constructor. Normalizes every component to an immutable, non-null
     * list so that callers never have to null-check an accessor.
     *
     * @throws NullPointerException if any of the supplied lists contains a
     *                              {@code null} element
     */
    public McpUiResourceCsp {
        connectDomains = copyOrEmpty(connectDomains);
        resourceDomains = copyOrEmpty(resourceDomains);
        frameDomains = copyOrEmpty(frameDomains);
        baseUriDomains = copyOrEmpty(baseUriDomains);
    }

    /**
     * Returns the secure default policy where all four directives are empty.
     *
     * @return a shared instance denying all external origins
     */
    public static McpUiResourceCsp empty() {
        return EMPTY;
    }

    /**
     * Indicates whether this policy grants no external origin at all.
     *
     * @return {@code true} if every component is empty
     */
    @JsonIgnore
    public boolean isEmpty() {
        return connectDomains.isEmpty()
                && resourceDomains.isEmpty()
                && frameDomains.isEmpty()
                && baseUriDomains.isEmpty();
    }

    private static List<String> copyOrEmpty(List<String> domains) {
        return domains == null ? List.of() : List.copyOf(domains);
    }
}