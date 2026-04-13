package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.mcp;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.UserRepository;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.NextcloudLoginService;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.NextcloudUserCredentials;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.mcp.server.Tool.Annotations;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MCP tools for authenticating a user against Nextcloud.
 *
 * <p>
 * Before any file operation can be performed, the server needs a valid
 * Nextcloud app
 * password for the authenticated OIDC user. These tools drive the
 * <a href=
 * "https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html">
 * Nextcloud Login Flow V2</a> to obtain and persist those credentials.
 *
 * <h2>Login sequence</h2>
 * <ol>
 * <li>The LLM calls {@code check-for-login} to determine whether credentials
 * are already
 * present for the current user.</li>
 * <li>If not, the LLM calls {@code initiate-login}. The tool starts a Login
 * Flow V2
 * session and immediately returns a login URL for the user to open in a
 * browser.</li>
 * <li>The tool polls for the resulting app password in the background and sends
 * MCP
 * progress notifications as the flow advances.</li>
 * <li>Once the user authorises the request in the browser, the credentials are
 * persisted
 * via {@link UserRepository} and a final progress notification is sent to the
 * LLM.</li>
 * </ol>
 *
 * <p>
 * At most one login flow per OIDC user is tracked at a time. Calling
 * {@code initiate-login} while a flow is already in progress simply returns the
 * existing
 * login URL rather than starting a new one.
 */
@ApplicationScoped
public class LoginMCP {
    public static final String TOOL_CHECK_FOR_LOGIN_NAME = "check-for-login";
    private static final String TOOL_CHECK_FOR_LOGIN_DESCRIPTION = "Checks if the user is logged in to Nextcloud and has valid credentials.";
    public static final String TOOL_INITIATE_LOGIN_NAME = "initiate-login";
    private static final String TOOL_INITIATE_LOGIN_DESCRIPTION = "Initiates the Nextcloud Login Flow. Returns a URL the user has to click to login and starts polling for the generated token. If there is already an login initiated for the current user, this just returns the existing login url again, until the login process is successful or cancelled.";
    public static final String TOOL_DELETE_LOGIN_NAME = "delete-login";
    private static final String TOOL_DELETE_LOGIN_DESCRIPTION = "Deletes the current access to nextcloud and on-going login flows. Re-login with tool "
            + TOOL_INITIATE_LOGIN_NAME + ".";

    @Inject
    UserRepository userRepository;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    NextcloudLoginService loginService;

    @Inject
    Logger log;

    private final Map<String, NextcloudLoginService.LoginFlowJob> ongoingLoginFlows = new ConcurrentHashMap<>();

    @Tool(name = TOOL_CHECK_FOR_LOGIN_NAME, description = TOOL_CHECK_FOR_LOGIN_DESCRIPTION, annotations = @Annotations(title = "Check if the user is logged in", destructiveHint = false, readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse checkForLogin() {
        final Optional<NextcloudUserCredentials> credentials = userRepository.getCredentialsForCurrentUser();
        if (credentials.isPresent()) {
            return ToolResponse.success("User is logged in with Nextcloud credentials.");
        } else {
            return ToolResponse.error(
                    "User is not logged in with Nextcloud credentials. Use tool '" + TOOL_INITIATE_LOGIN_NAME
                            + "' to start the login flow.");
        }
    }

    @Tool(name = TOOL_DELETE_LOGIN_NAME, description = TOOL_DELETE_LOGIN_DESCRIPTION, annotations = @Annotations(title = "Delete Nextcloud login", destructiveHint = true, readOnlyHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse deleteLogin() {
        final String user = securityIdentity.getPrincipal().getName();
        // Cancel login flow
        ongoingLoginFlows.remove(user);
        // Cancel existing accounts
        final Optional<NextcloudUserCredentials> credentials = userRepository.getCredentialsForCurrentUser();
        if (credentials.isPresent()) {
            final boolean success = loginService.deleteUserAccount(credentials.get());
            if (success) {
                try {
                    userRepository.saveCredentialsForCurrentUser(null);
                } catch (Exception e) {
                    throw new ToolCallException("Failed to delete credentials for user", e);
                }
                return ToolResponse.success("User login for Nextcloud sucessfully removed");
            } else {
                return ToolResponse.error("Failed to remove Nextcloud login");
            }
        } else {
            return ToolResponse.success("User is already logged out from Nextcloud");
        }
    }

    @Tool(name = TOOL_INITIATE_LOGIN_NAME, description = TOOL_INITIATE_LOGIN_DESCRIPTION, annotations = @Annotations(title = "Start the login process at nextcloud", destructiveHint = false, readOnlyHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse initiateLogin(Progress progress) {
        final String user = securityIdentity.getPrincipal().getName();

        final NextcloudLoginService.LoginFlowJob existingJob = ongoingLoginFlows.get(user);
        if (existingJob != null) {
            return ToolResponse
                    .success("Login flow already in progress. Please click the following URL to login to Nextcloud: "
                            + existingJob.loginUrl());
        }

        final NextcloudLoginService.LoginFlowJob job = loginService.initiateLoginFlow();
        final String message = "Please request the user to click the following URL to login to Nextcloud: "
                + job.loginUrl();
        progress.notificationBuilder().setMessage(message).setProgress(0).build().send();
        job.session().thenAccept(credentials -> {
            ongoingLoginFlows.remove(user);
            progress.notificationBuilder().setMessage(
                    "Login successful! Nextcloud credentials have been saved. You can now use other tools that require Nextcloud authentication. If you want to login with a different account, please repeat the login process with tool '"
                            + TOOL_INITIATE_LOGIN_NAME + "'.")
                    .setProgress(100).build().send();
            try {
                userRepository.saveCredentialsForUser(user, credentials);
            } catch (Exception e) {
                log.errorf(e, "Failed to save new nextcloud credentials for user: %s", user);
            }
        }).exceptionally(e -> {
            ongoingLoginFlows.remove(user);
            log.errorf(e, "Login process failed for user %s", user);
            progress.notificationBuilder().setMessage(
                    "An error occurred while logging in to Nextcloud. Please repeat the login process with tool '"
                            + TOOL_INITIATE_LOGIN_NAME + "'.")
                    .setProgress(100).build().send();
            return null;
        });
        return ToolResponse
                .success("Please request the user to click the following URL to login to Nextcloud: " + job.loginUrl());

    }
}
