package io.github.stefanrichterhuber.nextcloudmcp.nextcloud;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.NextcloudUserCredentials;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class FileBasedUserRepository implements UserRepository {
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

    private final Map<String, UserModel> users = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        try {
            loadUsers();
        } catch (IOException e) {
            log.errorf(e, "Failed to load user data from file %s", storageFilePath);
        }
    }

    private void loadUsers() throws StreamReadException, DatabindException, IOException {
        if (Files.exists(storageFilePath)) {
            byte[] content = Files.readAllBytes(storageFilePath);
            Map<String, UserModel> loaded = objectMapper.readValue(content,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class,
                            UserModel.class));
            users.putAll(loaded);

        } else {
            users.clear();
        }
    }

    private synchronized void saveUsers() throws IOException {
        byte[] content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(users);
        Files.write(storageFilePath, content);
    }

    public Optional<NextcloudUserCredentials> getCredentialsForCurrentUser() {
        final String sub = securityIdentity.getPrincipal().getName();
        return getCredentialsForUser(sub);
    }

    public Optional<NextcloudUserCredentials> getCredentialsForUser(String name) {
        return Optional.ofNullable(users.get(name)).map(UserModel::credentials);
    }

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
}
