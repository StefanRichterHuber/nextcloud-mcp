package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.mcp;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

import io.github.stefanrichterhuber.nextcloudlib.runtime.NextcloudLoginService;
import io.github.stefanrichterhuber.nextcloudlib.runtime.models.NextcloudUserCredentials;
import io.github.stefanrichterhuber.nextcloudmcp.audit.MCPAudit;
import io.github.stefanrichterhuber.nextcloudmcp.config.NextcloudConfig;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.UserRepository;
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

    @Inject
    NextcloudConfig config;

    private final Map<String, NextcloudLoginService.LoginFlowJob> ongoingLoginFlows = new ConcurrentHashMap<>();

    /**
     * MCP tool: checks whether the current OIDC user already has valid Nextcloud
     * app-password credentials stored in the {@link UserRepository}.
     *
     * @return success response if credentials are present; error response
     *         (prompting the LLM to call {@code initiate-login}) if they are not
     */
    @Tool(name = TOOL_CHECK_FOR_LOGIN_NAME, title = "Check if the user is logged in", description = TOOL_CHECK_FOR_LOGIN_DESCRIPTION, annotations = @Annotations(title = "Check if the user is logged in", destructiveHint = false, readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    @MCPAudit
    public ToolResponse checkForLogin() {
        if (config.userOidc()) {
            return ToolResponse.success("User is logged in with OIDC token.");
        }

        final Optional<NextcloudUserCredentials> credentials = userRepository.getCredentialsForCurrentUser();
        if (credentials.isPresent()) {
            return ToolResponse.success("User is logged in with Nextcloud credentials.");
        } else {
            return ToolResponse.error(
                    "User is not logged in with Nextcloud credentials. Use tool '" + TOOL_INITIATE_LOGIN_NAME
                            + "' to start the login flow.");
        }
    }

    /**
     * MCP tool: revokes the current user's Nextcloud app-password and removes the
     * stored credentials. Any in-progress Login Flow V2 session for the user is
     * also cancelled.
     *
     * @return success response when credentials are removed (or were already
     *         absent); error response if the Nextcloud server rejects the
     *         revocation
     * @throws ToolCallException if the credential store update fails after a
     *                           successful remote revocation
     */
    @Tool(name = TOOL_DELETE_LOGIN_NAME, title = "Delete Nextcloud login", description = TOOL_DELETE_LOGIN_DESCRIPTION, annotations = @Annotations(title = "Delete Nextcloud login", destructiveHint = true, readOnlyHint = false, idempotentHint = true, openWorldHint = false))
    @MCPAudit
    public ToolResponse deleteLogin() {
        if (config.userOidc()) {
            return ToolResponse.error("User is logged in with OIDC token. Not possible to delete login credentials.");
        }

        final String user = securityIdentity.getPrincipal().getName();
        // Cancel login flow
        final NextcloudLoginService.LoginFlowJob job = ongoingLoginFlows.remove(user);
        if (job != null) {
            job.session().toCompletableFuture().cancel(false);
        }

        // Cancel existing accounts
        final Optional<NextcloudUserCredentials> credentials = userRepository.getCredentialsForCurrentUser();
        if (credentials.isPresent()) {
            final boolean success = loginService.deleteUserAccount(credentials.get());
            if (success) {
                try {
                    userRepository.removeCredentialsForCurrentUser();
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

    /**
     * MCP tool: starts a Nextcloud Login Flow V2 session for the current OIDC user
     * and returns the browser URL the user must open to authorise the request.
     *
     * <p>
     * If a flow is already in progress for this user the existing login URL is
     * returned immediately without starting a new session. Once the user authorises
     * in the browser the resulting app-password is persisted via
     * {@link UserRepository} and a final MCP progress notification (progress=100)
     * is sent to the LLM. Failure or cancellation is reported the same way.
     *
     * @param progress MCP channel used to send intermediate notifications while
     *                 polling for the app-password
     * @return success response containing the login URL the user must visit
     */
    @Tool(name = TOOL_INITIATE_LOGIN_NAME, title = "Start the login process at nextcloud", description = TOOL_INITIATE_LOGIN_DESCRIPTION, annotations = @Annotations(title = "Start the login process at nextcloud", destructiveHint = false, readOnlyHint = false, idempotentHint = true, openWorldHint = false))
    @MCPAudit
    public ToolResponse initiateLogin() {
        if (config.userOidc()) {
            return ToolResponse
                    .error("User is logged in with OIDC token. Not possible to create additional login credentials.");
        }
        final String user = securityIdentity.getPrincipal().getName();

        final NextcloudLoginService.LoginFlowJob job = ongoingLoginFlows.computeIfAbsent(user, u -> {
            final NextcloudLoginService.LoginFlowJob j = loginService.initiateLoginFlow(config.url(),
                    config.appName());
            j.session().thenAccept(credentials -> {
                ongoingLoginFlows.remove(user);
                try {
                    userRepository.saveCredentialsForUser(user, credentials);
                } catch (Exception e) {
                    log.errorf(e, "Failed to save new nextcloud credentials for user: %s", user);
                }
            }).exceptionally(e -> {
                ongoingLoginFlows.remove(user);
                log.errorf(e, "Login process failed for user %s", user);
                return null;
            });
            return j;
        });
        return ToolResponse
                .success("Please request the user to click the following URL to login to Nextcloud: " + job.loginUrl());

    }
}
