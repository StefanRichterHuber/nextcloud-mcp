package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.mcp;

import java.util.Optional;

import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.UserRepository;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.NextcloudLoginService;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.NextcloudUserCredentials;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class LoginMCP {
    @Inject
    UserRepository userRepository;
    @Inject
    SecurityIdentity securityIdentity;
    @Inject
    NextcloudLoginService loginService;

    private static final String TOOL_CHECK_FOR_LOGIN_NAME = "check-for-login";
    private static final String TOOL_CHECK_FOR_LOGIN_DESCRIPTION = "Checks if the user is logged in to Nextcloud and has valid credentials.";
    private static final String TOOL_INITIATE_LOGIN_NAME = "initiate-login";
    private static final String TOOL_INITIATE_LOGIN_DESCRIPTION = "Initiates the Nextcloud Login Flow. Returns a URL the user has to click to login and starts polling for the generated token.";

    @Tool(name = TOOL_CHECK_FOR_LOGIN_NAME, description = TOOL_CHECK_FOR_LOGIN_DESCRIPTION)
    public ToolResponse checkForLogin() {
        Optional<NextcloudUserCredentials> credentials = userRepository.getCredentialsForCurrentUser();
        if (credentials.isPresent()) {
            return ToolResponse.success("User is logged in with Nextcloud credentials.");
        } else {
            return ToolResponse.success(
                    "User is not logged in with Nextcloud credentials. Use tool 'initiate-login' to start the login flow.");
        }
    }

    @Tool(name = TOOL_INITIATE_LOGIN_NAME, description = TOOL_INITIATE_LOGIN_DESCRIPTION)
    public ToolResponse initiateLogin() {
        String user = securityIdentity.getPrincipal().getName();

        NextcloudLoginService.LoginFlowJob job = loginService.initiateLoginFlow();
        job.session().thenAccept(credentials -> {
            try {
                userRepository.saveCredentialsForUser(user, credentials);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        return ToolResponse
                .success("Please request the user to click the following URL to login to Nextcloud: " + job.loginUrl());

    }
}
