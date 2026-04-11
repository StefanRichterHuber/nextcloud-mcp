package io.github.stefanrichterhuber.nextcloudmcp.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 */
@Path("/")
@ApplicationScoped
public class AuthorizeRedirectResource {

    private static final String DEFAULT_CLIENT_NAME = "Claude";
    private static final List<String> IDENTIY_PROVIDER_CONFIG_LOCATIONS = List.of(
            "/.well-known/oauth-authorization-server",
            "/.well-known/openid-configuration");

    /**
     * Base URL of the identity provider, e.g. https://auth.example.com
     */
    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    String identityProviderBaseUrl;

    /**
     * Pre-registered client id
     */
    @ConfigProperty(name = "quarkus.oidc.client-id")
    String clientId;

    /**
     * Pre-registered client secret
     */
    @ConfigProperty(name = "quarkus.oidc.credentials.secret")
    String clientSecret;

    private final HttpClient client = HttpClient.newHttpClient();

    /**
     * Identity provider configuration
     */
    private record OpenIDConfiguration(
            @JsonProperty("authorization_endpoint") URI authorizationEndpoint,
            @JsonProperty("token_endpoint") URI tokenEndpoint,
            @JsonProperty("userinfo_endpoint") URI userinfoEndpoint,
            @JsonProperty("grant_types_supported") List<String> grantTypes,
            @JsonProperty("response_types_supported") List<String> responseTypes,
            @JsonProperty("token_endpoint_auth_methods_supported") List<String> tokenEndpointAuthMethods,
            @JsonProperty("scopes_supported") List<String> scopesSupported) {
    }

    /**
     * RFC 7591 registration request body.
     * All fields are optional per spec — server substitutes defaults.
     */
    private record ClientRegistrationRequest(
            @JsonProperty("redirect_uris") List<String> redirectUris,
            @JsonProperty("client_name") String clientName,
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
        // Try RFC 8414 first, fall back to OIDC discovery
        for (String path : IDENTIY_PROVIDER_CONFIG_LOCATIONS) {
            try {
                final HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(identityProviderBaseUrl + path))
                        .GET()
                        .build();
                final HttpResponse<String> response = client.send(
                        request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    openIDConfiguration = objectMapper.readValue(response.body(), OpenIDConfiguration.class);
                    return;
                }
            } catch (Exception e) {
                // try next path
            }
        }
        throw new IllegalStateException("Could not discover authorization_endpoint from " + identityProviderBaseUrl);

    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/register")
    public Response register(ClientRegistrationRequest request) {

        final List<String> requestedScopes = request.scope() != null
                ? List.of(request.scope().split(" "))
                : List.of();
        final List<String> grantedScopes = requestedScopes.stream()
                .filter(s -> openIDConfiguration.scopesSupported().contains(s))
                .toList();

        if (!openIDConfiguration.tokenEndpointAuthMethods().contains(request.tokenEndpointAuthMethod)) {
            return Response.serverError()
                    .entity("Token Endpoint Method " + request.tokenEndpointAuthMethod() + " not supported").build();
        }

        final List<String> grantedResponseTypes = request.responseTypes().stream()
                .filter(t -> openIDConfiguration.responseTypes().contains(t)).toList();
        if (grantedResponseTypes.isEmpty()) {
            return Response.serverError()
                    .entity("Response types " + request.responseTypes() + " are not supported").build();
        }

        final List<String> grantedGrantTypes = request.grantTypes().stream()
                .filter(t -> openIDConfiguration.grantTypes().contains(t)).toList();
        if (grantedGrantTypes.isEmpty()) {
            return Response.serverError()
                    .entity("Grant types " + request.grantTypes() + " are not supported").build();
        }

        // RFC 7591 §3.1: validate redirect_uris are present
        if (request.redirectUris() == null || request.redirectUris().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "error", "invalid_redirect_uri",
                            "error_description", "redirect_uris is required"))
                    .build();
        }

        // RFC 7591 §3.2.1: 201 Created with client information response
        // We return the static client information
        final Map<String, Object> response = Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "client_name", request.clientName() != null
                        ? request.clientName()
                        : DEFAULT_CLIENT_NAME,
                "redirect_uris", request.redirectUris(),
                "grant_types", grantedGrantTypes,
                "response_types", grantedResponseTypes,
                "token_endpoint_auth_method", request.tokenEndpointAuthMethod(),
                "scope", String.join(" ", grantedScopes));

        return Response.status(Response.Status.CREATED)
                .entity(response)
                .build();
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
            return Response
                    .status(Response.Status.BAD_GATEWAY)
                    .entity(Map.of(
                            "error", "server_error",
                            "error_description", "Token endpoint proxy failed: " + e.getMessage()))
                    .build();
        }
    }
}