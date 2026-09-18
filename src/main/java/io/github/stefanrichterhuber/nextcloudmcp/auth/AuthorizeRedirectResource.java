package io.github.stefanrichterhuber.nextcloudmcp.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

/**
 * Some identity providers do not fully support dynamic client
 * registration (RFC 7591). In this case claude.ai falls back to calls to
 * /register, /authorize and /token on the mcp server itself.
 * This resource implements these endpoints and proxies them to the actual
 * identity provider. It also provides a /register endpoint which just returns
 * some static, pre-configured client_id and client_secret
 * 
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7591">rfc7591</a>
 */
@Path("/")
@ApplicationScoped
public class AuthorizeRedirectResource {

    private static final String DEFAULT_CLIENT_NAME = "Claude";
    private static final List<String> IDENTIY_PROVIDER_CONFIG_LOCATIONS = List.of(
            "/.well-known/oauth-authorization-server",
            "/.well-known/openid-configuration");

    /**
     * RFC 7591 §2 defaults applied when the client omits these (optional) fields.
     */
    private static final String DEFAULT_TOKEN_ENDPOINT_AUTH_METHOD = "client_secret_basic";
    private static final List<String> DEFAULT_RESPONSE_TYPES = List.of("code");
    private static final List<String> DEFAULT_GRANT_TYPES = List.of("authorization_code");

    /**
     * Base URL of the identity provider, e.g. https://auth.example.com
     */
    @Inject
    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    String identityProviderBaseUrl;

    /**
     * Pre-registered client id
     */
    @Inject
    @ConfigProperty(name = "app.oidc.static-client-registration.client-id")
    Optional<String> clientId;

    /**
     * Pre-registered client secret
     */
    @Inject
    @ConfigProperty(name = "app.oidc.static-client-registration.credentials.secret")
    Optional<String> clientSecret;

    /**
     * When the underlying OIDC IDP, does not support dynamic client registration,
     * pass a static client_id and client_secret to the caller
     */
    @Inject
    @ConfigProperty(name = "app.oidc.static-client-registration.enabled", defaultValue = "false")
    boolean staticClientRegistrationEnabled;

    /**
     * When static client registration is enabled, only the redirect uris
     * pre-configred in the IDP can be used for the clients. So give a list of this
     * valid redirect uris
     */
    @Inject
    @ConfigProperty(name = "app.oidc.static-client-registration.redirect_uris")
    Optional<List<String>> staticClientRedirectURIS;

    private final HttpClient client = HttpClient.newHttpClient();

    /**
     * Identity provider configuration
     */
    @RegisterForReflection
    private record OpenIDConfiguration(
            @JsonProperty("authorization_endpoint") URI authorizationEndpoint,
            @JsonProperty("token_endpoint") URI tokenEndpoint,
            @JsonProperty("userinfo_endpoint") URI userinfoEndpoint,
            @JsonProperty("grant_types_supported") List<String> grantTypes,
            @JsonProperty("response_types_supported") List<String> responseTypes,
            @JsonProperty("token_endpoint_auth_methods_supported") List<String> tokenEndpointAuthMethods,
            @JsonProperty("scopes_supported") List<String> scopesSupported,
            @JsonProperty("registration_endpoint") String registrationEndpoint,
            @JsonProperty("introspection_endpoint") String introspectionEndpoint,
            @JsonProperty("end_session_endpoint") String endSessionEndpoint) {
    }

    /**
     * RFC 7591 §3.2.1 client information response.
     */
    @RegisterForReflection
    private record ClientRegistrationResponse(
            @JsonProperty("client_id") String clientId,
            @JsonProperty("client_secret") String clientSecret,
            @JsonProperty("client_name") String clientName,
            @JsonProperty("client_id_issued_at") Long issuedAt,
            @JsonProperty("redirect_uris") List<String> redirectUris,
            @JsonProperty("grant_types") List<String> grantTypes,
            @JsonProperty("response_types") List<String> responseTypes,
            @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod,
            @JsonProperty("scope") String scope) {
    }

    /**
     * RFC 7591 registration request body.
     * All fields are optional per spec — server substitutes defaults.
     */
    @RegisterForReflection
    private record ClientRegistrationRequest(
            @JsonProperty("application_type") String applicationType,
            @JsonProperty("redirect_uris") List<String> redirectUris,
            @JsonProperty("client_name") String clientName,
            @JsonProperty("logo_uri") String logoUri,
            @JsonProperty("contacts") List<String> contacts,
            @JsonProperty("grant_types") List<String> grantTypes,
            @JsonProperty("response_types") List<String> responseTypes,
            @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod,
            @JsonProperty("scope") String scope) {
    }

    private volatile OpenIDConfiguration openIDConfiguration;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Logger log;

    /**
     * Discovers the authorization and token endpoints from the identity provider's
     * metadata. Supports both RFC 8414 and OIDC discovery formats.
     */
    @PostConstruct
    void discoverAuthorizationEndpoint() {
        final String baseUrl = identityProviderBaseUrl.endsWith("/")
                ? identityProviderBaseUrl.substring(0, identityProviderBaseUrl.length() - 1)
                : identityProviderBaseUrl;
        // Try RFC 8414 first, fall back to OIDC discovery
        for (String path : IDENTIY_PROVIDER_CONFIG_LOCATIONS) {
            try {
                final HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .GET()
                        .build();
                final HttpResponse<String> response = client.send(
                        request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    openIDConfiguration = objectMapper.readValue(response.body(), OpenIDConfiguration.class);
                    log.infof("Discovered identity provider metadata from %s%s", baseUrl, path);
                    return;
                }
                log.debugf("Identity provider metadata not found at %s%s (status %d)", baseUrl, path,
                        response.statusCode());
            } catch (Exception e) {
                log.debugf(e, "Failed to fetch identity provider metadata from %s%s", baseUrl, path);
            }
        }
        throw new IllegalStateException("Could not discover authorization_endpoint from " + baseUrl);

    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/register")
    public Response register(@Context HttpHeaders headers, String body) {
        if (this.openIDConfiguration.registrationEndpoint() != null) {
            // The backend supports client registration -> just pass the the request through
            try {
                final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(openIDConfiguration.registrationEndpoint()))
                        .POST(HttpRequest.BodyPublishers.ofString(body));

                // Forward relevant headers from the original request
                for (String header : List.of(
                        HttpHeaders.AUTHORIZATION,
                        HttpHeaders.CONTENT_TYPE)) {
                    String value = headers.getHeaderString(header);
                    if (value != null) {
                        requestBuilder.header(header, value);
                    }
                }

                final HttpResponse<String> upstream = client.send(
                        requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofString());

                // Forward the upstream status and body back to Claude verbatim
                return Response
                        .status(upstream.statusCode())
                        .entity(upstream.body())
                        .type(MediaType.APPLICATION_JSON)
                        .build();

            } catch (Exception e) {
                log.errorf(e, "Token endpoint proxy to %s failed", openIDConfiguration.tokenEndpoint());
                return Response
                        .status(Response.Status.BAD_GATEWAY)
                        .entity(Map.of(
                                "error", "server_error",
                                "error_description", "Token endpoint proxy failed: " + e.getMessage()))
                        .build();
            }
        } else {
            if (!staticClientRegistrationEnabled || clientId.isEmpty() || clientSecret.isEmpty()) {
                log.errorf(
                        "MCP Client requests dynmic client registration. Downstream identity provider '%s' does not support dynamic client registration, but properties 'quarkus.oidc.client-id' and 'quarkus.oidc.credentials.secret' are not set",
                        this.identityProviderBaseUrl);
                return Response.status(Response.Status.BAD_REQUEST)
                        .build();
            }
            if (body == null || body.isBlank()) {
                log.errorf(
                        "MCP Client requests dynmic client registration: Body is empty");
                return Response.status(Response.Status.BAD_REQUEST)
                        .build();
            }
            final ObjectMapper om = new ObjectMapper();
            try {
                final ClientRegistrationRequest request = om.readValue(body, ClientRegistrationRequest.class);

                // The backend does not support dynamic client registration -> send back
                // pre-configured clientId / clientSecriet
                final List<String> requestedScopes = request.scope() != null
                        ? List.of(request.scope().split(" "))
                        : List.of();
                final List<String> grantedScopes = requestedScopes.stream()
                        .filter(s -> openIDConfiguration.scopesSupported().contains(s))
                        .toList();

                // RFC 7591 §2: token_endpoint_auth_method defaults to "client_secret_basic"
                // when omitted
                final String tokenEndpointAuthMethod = request.tokenEndpointAuthMethod() != null
                        ? request.tokenEndpointAuthMethod()
                        : DEFAULT_TOKEN_ENDPOINT_AUTH_METHOD;
                if (!openIDConfiguration.tokenEndpointAuthMethods().contains(tokenEndpointAuthMethod)) {
                    log.warnf("Client registration rejected: token_endpoint_auth_method %s not supported by %s",
                            tokenEndpointAuthMethod, identityProviderBaseUrl);
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of(
                                    "error", "invalid_client_metadata",
                                    "error_description",
                                    "Token endpoint auth method " + tokenEndpointAuthMethod + " not supported"))
                            .build();
                }

                // RFC 7591 §2: response_types defaults to ["code"] when omitted
                final List<String> requestedResponseTypes = request.responseTypes() != null
                        ? request.responseTypes()
                        : DEFAULT_RESPONSE_TYPES;
                final List<String> grantedResponseTypes = requestedResponseTypes.stream()
                        .filter(t -> openIDConfiguration.responseTypes().contains(t)).toList();
                if (grantedResponseTypes.isEmpty()) {
                    log.warnf("Client registration rejected: response_types %s not supported by %s",
                            requestedResponseTypes, identityProviderBaseUrl);
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of(
                                    "error", "invalid_client_metadata",
                                    "error_description",
                                    "Response types " + requestedResponseTypes + " are not supported"))
                            .build();
                }

                // RFC 7591 §2: grant_types defaults to ["authorization_code"] when omitted
                final List<String> requestedGrantTypes = request.grantTypes() != null
                        ? request.grantTypes()
                        : DEFAULT_GRANT_TYPES;
                final List<String> grantedGrantTypes = requestedGrantTypes.stream()
                        .filter(t -> openIDConfiguration.grantTypes().contains(t)).toList();
                if (grantedGrantTypes.isEmpty()) {
                    log.warnf("Client registration rejected: grant_types %s not supported by %s",
                            requestedGrantTypes, identityProviderBaseUrl);
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of(
                                    "error", "invalid_client_metadata",
                                    "error_description", "Grant types " + requestedGrantTypes + " are not supported"))
                            .build();
                }

                // RFC 7591 §3.1: validate redirect_uris are present
                if (request.redirectUris() == null || request.redirectUris().isEmpty()) {
                    log.warnf("Dynamic client registration, MCP client does not provide redirect_uris: %s", request);
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of(
                                    "error", "invalid_redirect_uri",
                                    "error_description", "redirect_uris is required"))
                            .build();
                }

                // Only pre-configured redirect_uris are possible
                final List<String> redirectUris = request.redirectUris.stream()
                        .filter(u -> this.staticClientRedirectURIS.orElse(List.of()).contains(u)).toList();
                if (redirectUris.isEmpty()) {
                    log.warnf(
                            "Dynamic client registration, MCP client does not provide supported redirect_uris (supported uris %s): %s",
                            this.staticClientRedirectURIS, request);

                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of(
                                    "error", "invalid_redirect_uri",
                                    "error_description", "redirect_uris is required"))
                            .build();
                }

                final String clientName = request.clientName() != null ? request.clientName() : DEFAULT_CLIENT_NAME;

                final long clientIdIssuedAt = System.currentTimeMillis();
                log.infof("Registered client '%s' with redirect_uris=%s", clientName, request.redirectUris());

                // RFC 7591 §3.2.1: 201 Created with client information response
                // We return the static client information
                final ClientRegistrationResponse response = new ClientRegistrationResponse(
                        clientId.get(),
                        clientSecret.get(),
                        clientName,
                        clientIdIssuedAt,
                        request.redirectUris(),
                        grantedGrantTypes,
                        grantedResponseTypes,
                        tokenEndpointAuthMethod,
                        String.join(" ", grantedScopes));

                return Response.status(Response.Status.CREATED)
                        .entity(response)
                        .build();
            } catch (JsonProcessingException e) {
                log.errorf(e, "Failed to parse client registration request for static client registration");
                return Response.status(Response.Status.BAD_REQUEST)
                        .build();
            }
        }
    }

    /**
     * Redirects the /authorize call to the authorization_endpoint of the identity
     * provider
     * 
     * @param uriInfo Request uri
     * @return Redirection
     */
    @GET
    @Path("/authorize")
    public Response authorize(@Context UriInfo uriInfo) {
        URI target = UriBuilder
                .fromUri(openIDConfiguration.authorizationEndpoint())
                .replaceQuery(uriInfo.getRequestUri().getRawQuery())
                .build();

        return Response
                .status(Response.Status.FOUND)
                .location(target)
                .build();
    }

    /**
     * Proxies the /token call to the token_endpoint of the identity provider. Mere
     * redirect is not accepted by claude.ai, so we implement a proxy call
     * 
     * @param headers Headers to proxy
     * @param body    Body to proxy
     * @return Result of the call to the identity provider
     */
    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response token(@Context HttpHeaders headers, String body) {
        try {

            final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(openIDConfiguration.tokenEndpoint())
                    .POST(HttpRequest.BodyPublishers.ofString(body));

            // Forward relevant headers from the original request
            for (String header : List.of(
                    HttpHeaders.AUTHORIZATION,
                    HttpHeaders.CONTENT_TYPE)) {
                String value = headers.getHeaderString(header);
                if (value != null) {
                    requestBuilder.header(header, value);
                }
            }

            final HttpResponse<String> upstream = client.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            // Forward the upstream status and body back to Claude verbatim
            return Response
                    .status(upstream.statusCode())
                    .entity(upstream.body())
                    .type(MediaType.APPLICATION_JSON)
                    .build();

        } catch (Exception e) {
            log.errorf(e, "Token endpoint proxy to %s failed", openIDConfiguration.tokenEndpoint());
            return Response
                    .status(Response.Status.BAD_GATEWAY)
                    .entity(Map.of(
                            "error", "server_error",
                            "error_description", "Token endpoint proxy failed: " + e.getMessage()))
                    .build();
        }
    }
}