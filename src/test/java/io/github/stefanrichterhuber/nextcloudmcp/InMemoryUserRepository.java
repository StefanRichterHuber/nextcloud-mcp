package io.github.stefanrichterhuber.nextcloudmcp;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.UserRepository;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.NextcloudUserCredentials;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

@ApplicationScoped
@Alternative
@Priority(1)
public class InMemoryUserRepository implements UserRepository {
    private record UserModel(NextcloudUserCredentials credentials, UserAccessConfig accessConfig) {

    }

    @Inject
    @ConfigProperty(name = "nextcloud.user")
    String user;

    @Inject
    @ConfigProperty(name = "nextcloud.password")
    String password;

    @Inject
    @ConfigProperty(name = "nextcloud.url")
    String url;

    private final Map<String, UserModel> models = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        UserModel um = new UserModel(new NextcloudUserCredentials(user, password, url),
                new UserAccessConfig("/", Set.of("*.md"), true, true, true));
        models.put(user, um);
    }

    @Override
    public Optional<NextcloudUserCredentials> getCredentialsForCurrentUser() {
        return getCredentialsForUser(user);
    }

    @Override
    public Optional<NextcloudUserCredentials> getCredentialsForUser(String name) {
        return Optional.ofNullable(models.get(name)).map(UserModel::credentials);
    }

    @Override
    public void saveCredentialsForUser(String name, NextcloudUserCredentials credentials) throws Exception {
        models.compute(name, (k, v) -> {
            if (v == null) {
                return new UserModel(credentials, null);
            } else {
                return new UserModel(credentials, v.accessConfig);
            }
        });
    }

    @Override
    public void saveCredentialsForCurrentUser(NextcloudUserCredentials credentials) throws Exception {
        saveCredentialsForUser(user, credentials);
    }

    @Override
    public Optional<UserAccessConfig> getAccessConfigForCurrentUser() {
        return getAccessConfigForUser(user);
    }

    @Override
    public Optional<UserAccessConfig> getAccessConfigForUser(String name) {
        return Optional.ofNullable(models.get(name)).map(UserModel::accessConfig);
    }

    @Override
    public void saveAccessConfigForUser(String name, UserAccessConfig config) throws Exception {
        models.compute(name, (k, v) -> {
            if (v == null) {
                return new UserModel(null, config);
            } else {
                return new UserModel(v.credentials, config);
            }
        });
    }

    @Override
    public void saveAccessConfigForCurrentUser(UserAccessConfig config) throws Exception {
        saveAccessConfigForUser(user, config);
    }

}
