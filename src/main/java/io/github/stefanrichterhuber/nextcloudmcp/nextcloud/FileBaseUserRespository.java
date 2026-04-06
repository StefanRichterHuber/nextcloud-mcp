package io.github.stefanrichterhuber.nextcloudmcp.nextcloud;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.NextcloudUserCredentials;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class FileBaseUserRespository implements UserRepository {
    private static final Path STORAGE_FILE = Paths.get("users.json");

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SecurityIdentity securityIdentity;

    private final Map<String, NextcloudUserCredentials> users = new HashMap<>();

    @PostConstruct
    void init() {
        try {
            loadUsers();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load users", e);
        }
    }

    private void loadUsers() throws StreamReadException, DatabindException, IOException {
        if (Files.exists(STORAGE_FILE)) {
            byte[] content = Files.readAllBytes(STORAGE_FILE);
            Map<String, NextcloudUserCredentials> loaded = objectMapper.readValue(content,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class,
                            NextcloudUserCredentials.class));
            users.putAll(loaded);

        } else {
            users.clear();
        }
    }

    private void saveUsers() throws IOException {
        byte[] content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(users);
        Files.write(STORAGE_FILE, content);
    }

    public Optional<NextcloudUserCredentials> getCredentialsForCurrentUser() {
        final String sub = securityIdentity.getPrincipal().getName();
        return getCredentialsForUser(sub);
    }

    public Optional<NextcloudUserCredentials> getCredentialsForUser(String name) {
        return Optional.ofNullable(users.get(name));
    }

    public void saveCredentialsForUser(String name, NextcloudUserCredentials credentials) throws IOException {
        users.put(name, credentials);
        saveUsers();
    }

    public void saveCredentialsForCurrentUser(NextcloudUserCredentials credentials) throws IOException {
        final String sub = securityIdentity.getPrincipal().getName();
        saveCredentialsForUser(sub, credentials);
    }
}
