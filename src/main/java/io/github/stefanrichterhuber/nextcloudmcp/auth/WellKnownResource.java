package io.github.stefanrichterhuber.nextcloudmcp.auth;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/.well-known/")
public class WellKnownResource {
    @ConfigProperty(name = "app.root-url")
    String rootUrl;

    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    String authServerUrl;

    /**
     * 
     * @see https://datatracker.ietf.org/doc/html/rfc9728
     */
    @GET
    @Path("oauth-protected-resource")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getOAuthProtectedResource() {
        Map<String, Object> response = new HashMap<>();
        response.put("resource", rootUrl);
        response.put("authorization_servers", List.of(authServerUrl));
        response.put("scopes_supported", List.of("openid", "profile", "email", "groups"));

        response.put("resource_documentation", rootUrl + "/docs");
        return response;
    }

    /**
     * 
     * @see https://datatracker.ietf.org/doc/html/rfc9728
     */
    @GET
    @Path("oauth-protected-resource/mcp")
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    public Map<String, Object> getOAuthProtectedResourceMcp() {
        Map<String, Object> response = new HashMap<>();
        response.putAll(getOAuthProtectedResource());
        response.put("resource", rootUrl + "/mcp");
        return response;
    }
}
