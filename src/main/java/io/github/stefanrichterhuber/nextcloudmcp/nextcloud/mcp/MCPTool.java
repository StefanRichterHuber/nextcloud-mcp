package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.mcp;

import java.util.Optional;

import org.jboss.logging.Logger;

import io.github.stefanrichterhuber.nextcloudlib.runtime.models.NextcloudUserCredentials;
import io.github.stefanrichterhuber.nextcloudmcp.config.NextcloudConfig;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.UserRepository;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MCPTool {
    @Inject
    Logger log;

    @Inject
    NextcloudConfig config;

    @Inject
    UserRepository userRepository;

    /**
     * Asserts that the user is logged in with Nextcloud credentials. If not, throws
     * a ToolCallException with instructions on how to log in.
     */
    public void assertUserLoggedIn() {
        if (config.userOidc()) {
            return;
        }
        Optional<NextcloudUserCredentials> credentials = userRepository.getCredentialsForCurrentUser();
        if (!credentials.isPresent()) {
            throw new ToolCallException(
                    "User is not logged in with Nextcloud credentials. Use tool '" + LoginMCP.TOOL_INITIATE_LOGIN_NAME
                            + "' to start the login flow.");
        }
    }
}
