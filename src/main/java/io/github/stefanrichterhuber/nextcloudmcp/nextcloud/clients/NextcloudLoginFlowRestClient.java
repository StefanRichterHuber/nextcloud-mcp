package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients;

import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * @see https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html
 */
public interface NextcloudLoginFlowRestClient {
    public record InitiateLoginFlowV2Response(String login, Poll poll) {
        public record Poll(String token, String endpoint) {
        }
    }

    public record NextcloudAppCredentials(String server, String loginName, String appPassword) {
    }

    /**
     * Initiate the login flow v2. The user agent paramter equals to the application
     * name shown in the Nextcloud login window
     */
    @POST
    @Path("index.php/login/v2")
    @Produces(MediaType.APPLICATION_JSON)
    InitiateLoginFlowV2Response initiateLoginFlowV2(@HeaderParam("User-Agent") String userAgent);

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Path("login/v2/poll")
    Response pollLoginFlowV2(@FormParam("token") String token);

    /**
     * Removes an app password.
     * 
     * @param authorization header: Basic base64_encode(username:app_password)
     * @return
     */
    @DELETE
    @Path("ocs/v2.php/core/apppassword")
    @Produces(MediaType.APPLICATION_XML)
    @ClientHeaderParam(name = "OCS-APIREQUEST", value = "true")
    Response deleteAppPassword(@HeaderParam("User-Agent") String userAgent,
            @HeaderParam("Authorization") String authorization);

}
