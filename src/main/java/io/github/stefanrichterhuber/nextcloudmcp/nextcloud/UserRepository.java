package io.github.stefanrichterhuber.nextcloudmcp.nextcloud;

import java.util.Optional;
import java.util.Set;

import io.github.stefanrichterhuber.nextcloudlib.runtime.models.NextcloudUserCredentials;

/**
 * Stores and retrieves per-user Nextcloud credentials and access configuration.
 *
 * <p>
 * Two categories of data are managed per user:
 * <ul>
 * <li><b>Credentials</b> — the Nextcloud login name and app password obtained
 * via
 * Nextcloud Login Flow V2, used to authenticate WebDAV and REST calls on behalf
 * of the user.</li>
 * <li><b>Access configuration</b> — an optional set of restrictions that limit
 * which
 * files and content types the AI is permitted to access for a given user.</li>
 * </ul>
 *
 * <p>
 * Methods come in two variants: those that operate on an explicitly named user
 * ({@code ForUser(String name)}) and convenience overloads that resolve the
 * name from
 * the currently authenticated OIDC principal ({@code ForCurrentUser()}).
 */
public interface UserRepository {

    /**
     * Restricts AI access to a subset of a user's Nextcloud files.
     *
     * @param rootFolder          optional path prefix that confines all file
     *                            operations to a
     *                            specific folder subtree (e.g.
     *                            {@code "/Documents"}). When
     *                            {@code null} or empty, the entire Nextcloud is
     *                            accessible.
     * @param filePatterns        optional set of glob patterns (e.g.
     *                            {@code "*.md"},
     *                            {@code "*.txt"}) that limit which files are
     *                            visible to the AI.
     *                            When empty, no pattern filter is applied.
     * @param textContent         whether plain-text file content may be read.
     * @param imageContent        whether image file content may be read.
     * @param audioContent        whether audio file content may be read.
     * @param calendarReadAccess  whether calendar read access is allowed.
     * @param calendarWriteAccess whether calendar write access is allowed.
     * @param contactAccess       whether contact access is allowed.
     */
    public record UserAccessConfig(String rootFolder, Set<String> filePatterns, boolean textContent,
            boolean imageContent,
            boolean audioContent, boolean calendarReadAccess, boolean calendarWriteAccess, boolean contactAccess) {
    }

    /**
     * Returns the Nextcloud credentials for the currently authenticated user.
     *
     * @return the credentials, or {@link Optional#empty()} if the user has not yet
     *         completed the Nextcloud login flow.
     */
    Optional<NextcloudUserCredentials> getCredentialsForCurrentUser();

    /**
     * Returns the Nextcloud credentials for the given user.
     *
     * @param name the OIDC subject / username to look up.
     * @return the credentials, or {@link Optional#empty()} if no credentials are
     *         stored
     *         for that user.
     */
    Optional<NextcloudUserCredentials> getCredentialsForUser(String name);

    /**
     * Persists Nextcloud credentials for the given user, replacing any previously
     * stored
     * credentials.
     *
     * @param name        the OIDC subject / username.
     * @param credentials the credentials to store.
     * @throws Exception if the credentials cannot be persisted.
     */
    void saveCredentialsForUser(String name, NextcloudUserCredentials credentials) throws Exception;

    /**
     * Persists Nextcloud credentials for the currently authenticated user,
     * replacing any
     * previously stored credentials.
     *
     * @param credentials the credentials to store.
     * @throws Exception if the credentials cannot be persisted.
     */
    void saveCredentialsForCurrentUser(NextcloudUserCredentials credentials) throws Exception;

    /**
     * Returns the access configuration for the currently authenticated user.
     *
     * @return the access configuration, or {@link Optional#empty()} if no
     *         configuration
     *         has been saved for the current user (in which case no restrictions
     *         apply).
     */
    Optional<UserAccessConfig> getAccessConfigForCurrentUser();

    /**
     * Returns the access configuration for the given user.
     *
     * @param name the OIDC subject / username to look up.
     * @return the access configuration, or {@link Optional#empty()} if no
     *         configuration
     *         has been saved for that user.
     */
    Optional<UserAccessConfig> getAccessConfigForUser(String name);

    /**
     * Persists the access configuration for the given user, replacing any
     * previously
     * stored configuration.
     *
     * @param name   the OIDC subject / username.
     * @param config the access configuration to store.
     * @throws Exception if the configuration cannot be persisted.
     */
    void saveAccessConfigForUser(String name, UserAccessConfig config) throws Exception;

    /**
     * Persists the access configuration for the currently authenticated user,
     * replacing
     * any previously stored configuration.
     *
     * @param config the access configuration to store.
     * @throws Exception if the configuration cannot be persisted.
     */
    void saveAccessConfigForCurrentUser(UserAccessConfig config) throws Exception;
}
