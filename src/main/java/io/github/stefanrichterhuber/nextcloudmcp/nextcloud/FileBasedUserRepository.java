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

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.stefanrichterhuber.nextcloudlib.runtime.models.NextcloudUserCredentials;
import io.github.stefanrichterhuber.nextcloudmcp.config.AppConfig;
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
    AppConfig appConfig;

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
        try {
            if (appConfig.userRepository().file().getParent() != null) {
                Files.createDirectories(appConfig.userRepository().file().getParent());
            }
            loadUsers();
        } catch (IOException e) {
            log.errorf(e, "Failed to initialize FileBasedUserRepository using file %s",
                    appConfig.userRepository().file());
        }
    }

    /**
     * Reads user data from the storage file into the in-memory map.
     * 
     * @throws IOException if the file cannot be read or parsed.
     */
    private void loadUsers() throws IOException {
        if (Files.exists(appConfig.userRepository().file())) {
            final byte[] content = Files.readAllBytes(appConfig.userRepository().file());
            if (content.length > 0) {
                final Map<String, UserModel> loaded = objectMapper.readValue(content,
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class,
                                UserModel.class));
                if (loaded != null) {
                    users.putAll(loaded);
                }
            }
            log.debugf("Loaded %d users from %s", users.size(), appConfig.userRepository().file());
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
        final Path tempFile = appConfig.userRepository().file()
                .resolveSibling(appConfig.userRepository().file().getFileName() + ".tmp");
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

            Files.move(tempFile, appConfig.userRepository().file(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            log.debugf("User data successfully saved to %s", appConfig.userRepository().file());
        } catch (IOException e) {
            log.errorf(e, "Failed to save user data to file %s", appConfig.userRepository().file());
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
                return new UserModel(credentials, null);
            } else {
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
                return new UserModel(null, config);
            } else {
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

    @Override
    public void removeCredentialsForCurrentUser() throws Exception {
        final String sub = securityIdentity.getPrincipal().getName();
        removeCredentialsForUser(sub);
    }

    @Override
    public void removeCredentialsForUser(String userId) throws Exception {
        saveCredentialsForUser(userId, null);
    }
}
