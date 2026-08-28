package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.mcp.model;

import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Metadata a server attaches to a UI resource to describe how the host should
 * embed and secure it.
 * <p>
 * Every component is optional in the wire format. A {@code null} component
 * means
 * "not specified" and leaves the decision to the host, which is deliberately
 * distinct from an explicitly supplied empty or {@code false} value.
 *
 * @param csp           Content Security Policy configuration. Servers declare
 *                      which
 *                      external origins their UI needs to access; hosts use
 *                      this to
 *                      enforce appropriate CSP headers. {@code null} = not
 *                      specified.
 * @param permissions   sandbox permissions requested by the UI. Servers declare
 *                      which
 *                      browser capabilities their UI needs and hosts
 *                      <em>may</em> honor
 *                      them by setting the corresponding iframe {@code allow}
 *                      attributes.
 *                      Apps should not assume a requested permission was
 *                      granted and
 *                      should fall back to JavaScript feature detection.
 *                      {@code null} = no permissions requested.
 * @param domain        dedicated origin for the view. Optional domain for the
 *                      view's
 *                      sandbox origin, useful when views need stable, dedicated
 *                      origins
 *                      for OAuth callbacks, CORS policies or API key
 *                      allowlists.
 *                      <p>
 *                      <strong>Host-dependent:</strong> the format and
 *                      validation rules
 *                      for this field are determined by each host, so servers
 *                      must
 *                      consult host-specific documentation for the expected
 *                      format.
 *                      Common patterns are hash-based subdomains
 *                      ({@code {hash}.claudemcpcontent.com}) and URL-derived
 *                      subdomains
 *                      ({@code www-example-com.oaiusercontent.com}).
 *                      <p>
 *                      If omitted, the host uses its default sandbox origin,
 *                      typically
 *                      one per conversation.
 *                      <p>
 *                      Examples:
 *                      {@code "a904794854a047f6.claudemcpcontent.com"},
 *                      {@code "www-example-com.oaiusercontent.com"}
 * @param prefersBorder visual boundary preference, controlling whether a
 *                      visible border
 *                      and background is provided by the host. Setting an
 *                      explicit value
 *                      is recommended because host defaults vary.
 *                      <ul>
 *                      <li>{@code TRUE} — request a visible border and
 *                      background</li>
 *                      <li>{@code FALSE} — request no visible border or
 *                      background</li>
 *                      <li>{@code null} — the host decides</li>
 *                      </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record UIResourceMeta(
        @JsonProperty("csp") McpUiResourceCsp csp,
        @JsonProperty("permissions") Permissions permissions,
        @JsonProperty("domain") String domain,
        @JsonProperty("prefersBorder") Boolean prefersBorder) {

    /**
     * Returns the border preference as an {@link Optional}, making the
     * "host decides" case explicit at the call site.
     *
     * @return the requested border preference, or an empty {@code Optional} if the
     *         server left the decision to the host
     */
    @JsonIgnore
    public Optional<Boolean> borderPreference() {
        return Optional.ofNullable(prefersBorder);
    }

    /**
     * Returns the declared CSP, falling back to the secure default when the server
     * did not specify one.
     *
     * @return the declared policy, or {@link McpUiResourceCsp#empty()} if absent
     */
    @JsonIgnore
    public McpUiResourceCsp cspOrDefault() {
        return csp != null ? csp : McpUiResourceCsp.empty();
    }

    /**
     * Sandbox permissions requested by a UI resource.
     * <p>
     * Each component follows the JSON schema convention of an empty object as a
     * marker: the <em>presence</em> of the key is the request, and the object body
     * is reserved for future per-permission options. A {@code null} component means
     * the capability was not requested.
     *
     * @param camera         request camera access. Maps to the Permissions Policy
     *                       {@code camera} feature.
     * @param microphone     request microphone access. Maps to the Permissions
     *                       Policy
     *                       {@code microphone} feature.
     * @param geolocation    request geolocation access. Maps to the Permissions
     *                       Policy
     *                       {@code geolocation} feature.
     * @param clipboardWrite request clipboard write access. Maps to the Permissions
     *                       Policy {@code clipboard-write} feature.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Permissions(
            @JsonProperty("camera") PermissionRequest camera,
            @JsonProperty("microphone") PermissionRequest microphone,
            @JsonProperty("geolocation") PermissionRequest geolocation,
            @JsonProperty("clipboardWrite") PermissionRequest clipboardWrite) {

        /**
         * @return {@code true} if camera access was requested
         */
        @JsonIgnore
        public boolean isCameraRequested() {
            return camera != null;
        }

        /**
         * @return {@code true} if microphone access was requested
         */
        @JsonIgnore
        public boolean isMicrophoneRequested() {
            return microphone != null;
        }

        /**
         * @return {@code true} if geolocation access was requested
         */
        @JsonIgnore
        public boolean isGeolocationRequested() {
            return geolocation != null;
        }

        /**
         * @return {@code true} if clipboard write access was requested
         */
        @JsonIgnore
        public boolean isClipboardWriteRequested() {
            return clipboardWrite != null;
        }

        /**
         * Renders the requested capabilities as Permissions Policy feature names,
         * suitable for building an iframe {@code allow} attribute.
         *
         * @return the feature names of all requested permissions, in declaration order
         */
        @JsonIgnore
        public java.util.List<String> requestedFeatures() {
            var features = new java.util.ArrayList<String>(4);
            if (isCameraRequested()) {
                features.add("camera");
            }
            if (isMicrophoneRequested()) {
                features.add("microphone");
            }
            if (isGeolocationRequested()) {
                features.add("geolocation");
            }
            if (isClipboardWriteRequested()) {
                features.add("clipboard-write");
            }
            return java.util.List.copyOf(features);
        }
    }

    /**
     * Marker for a single requested permission.
     * <p>
     * Serializes to and deserializes from an empty JSON object ({@code {}}). The
     * type carries no state today; it exists so the protocol can add per-permission
     * options later without a breaking change. Unknown properties sent by newer
     * peers are ignored.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PermissionRequest() {

        /** Shared instance — the type is stateless, so one instance suffices. */
        private static final PermissionRequest INSTANCE = new PermissionRequest();

        /**
         * @return the shared marker instance representing a requested permission
         */
        public static PermissionRequest requested() {
            return INSTANCE;
        }

        /**
         * Gives Jackson a (empty) property source so the marker serializes as
         * {@code {}} instead of failing the {@code FAIL_ON_EMPTY_BEANS} check.
         *
         * @return always an empty map
         */
        @JsonAnyGetter
        Map<String, Object> properties() {
            return Map.of();
        }
    }
}