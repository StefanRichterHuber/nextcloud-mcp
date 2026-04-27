package io.github.stefanrichterhuber.nextcloudmcp.nextcloud;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.stefanrichterhuber.nextcloudlib.runtime.models.NextcloudUserCredentials;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Implementation of {@link UserRepository} that persists user credentials and
 * configuration to a local JSON file.
 * 
 * <p>
 * <strong>SECURITY NOTE:</strong> App passwords and credentials are currently
 * stored in plain text within the JSON file. It is recommended to secure the
 * host system and ensure restrictive file permissions are maintained.
 * </p>
 */
@ApplicationScoped
public class FileBasedUserRepository implements UserRepository {

    /**
     * Internal data model for storing user information.
     */
    private record UserModel(NextcloudUserCredentials credentials, UserAccessConfig accessConfig) {
    }

    @Inject
    @ConfigProperty(name = "app.user-repository.file")
    Path storageFilePath;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    Logger log;

    /**
     * In-memory cache of user data, synchronized with the storage file.
     */
    private final Map<String, UserModel> users = new ConcurrentHashMap<>();

    /**
     * Initializes the repository by loading existing user data from the storage
     * file.
     */
    @PostConstruct
    void init() {
        log.infof("Storage file path (APP_USER_REPOSITORY_FILE): %s", storageFilePath);
        try {
            if (storageFilePath.getParent() != null) {

                Files.createDirectories(storageFilePath.getParent());
            }
            loadUsers();
        } catch (IOException e) {
            log.errorf(e, "Failed to initialize FileBasedUserRepository using file %s", storageFilePath);
        }
    }

    /**
     * Reads user data from the storage file into the in-memory map.
     * 
     * @throws IOException if the file cannot be read or parsed.
     */
    private void loadUsers() throws IOException {
        if (Files.exists(storageFilePath)) {
            final byte[] content = Files.readAllBytes(storageFilePath);
            if (content.length > 0) {
                final Map<String, UserModel> loaded = objectMapper.readValue(content,
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class,
                                UserModel.class));
                if (loaded != null) {
                    users.putAll(loaded);
                }
            }
            log.infof("Loaded %d users from %s", users.size(), storageFilePath);
        }
    }

    /**
     * Persists the current state of the in-memory map to the storage file.
     * Uses an atomic write strategy (write to temp file then move) to prevent data
     * corruption.
     * 
     * @throws IOException if the file cannot be written.
     */
    private synchronized void saveUsers() throws IOException {
        final Path tempFile = storageFilePath.resolveSibling(storageFilePath.getFileName() + ".tmp");
        try {
            final byte[] content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(users);
            Files.write(tempFile, content);

            // Attempt to set restrictive permissions (rw-------)
            try {
                final Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(tempFile, perms);
            } catch (UnsupportedOperationException e) {
                // Fallback for non-POSIX systems
                final File file = tempFile.toFile();
                file.setReadable(true, true);
                file.setWritable(true, true);
            }

            Files.move(tempFile, storageFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.debugf("User data successfully saved to %s", storageFilePath);
        } catch (IOException e) {
            log.errorf(e, "Failed to save user data to file %s", storageFilePath);
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }

    @Override
    public Optional<NextcloudUserCredentials> getCredentialsForCurrentUser() {
        final String sub = securityIdentity.getPrincipal().getName();
        return getCredentialsForUser(sub);
    }

    @Override
    public Optional<NextcloudUserCredentials> getCredentialsForUser(String name) {
        return Optional.ofNullable(users.get(name)).map(UserModel::credentials);
    }

    @Override
    public void saveCredentialsForUser(String name, NextcloudUserCredentials credentials) throws Exception {
        users.compute(name, (k, v) -> {
            if (v == null) {
                log.infof("Added credentials for new user %s -> Nextcloud user %s", name, credentials.loginName());
                return new UserModel(credentials, null);
            } else {
                log.infof("Added new credentials for existing user %s -> Nextcloud user %s", name,
                        credentials.loginName());
                return new UserModel(credentials, v.accessConfig);
            }
        });
        saveUsers();
    }

    @Override
    public void saveCredentialsForCurrentUser(NextcloudUserCredentials credentials) throws Exception {
        final String sub = securityIdentity.getPrincipal().getName();
        saveCredentialsForUser(sub, credentials);
    }

    @Override
    public Optional<UserAccessConfig> getAccessConfigForCurrentUser() {
        final String sub = securityIdentity.getPrincipal().getName();
        return getAccessConfigForUser(sub);
    }

    @Override
    public Optional<UserAccessConfig> getAccessConfigForUser(String name) {
        return Optional.ofNullable(users.get(name)).map(UserModel::accessConfig);
    }

    @Override
    public void saveAccessConfigForUser(String name, UserAccessConfig config) throws Exception {
        users.compute(name, (k, v) -> {
            if (v == null) {
                log.infof("Added access config for new user %s", name);
                return new UserModel(null, config);
            } else {
                log.infof("Added new access config for existing user %s", name);
                return new UserModel(v.credentials, config);
            }
        });
        saveUsers();
    }

    @Override
    public void saveAccessConfigForCurrentUser(UserAccessConfig config) throws Exception {
        final String sub = securityIdentity.getPrincipal().getName();
        saveAccessConfigForUser(sub, config);
    }
}
