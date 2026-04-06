package io.github.stefanrichterhuber.nextcloudmcp.nextcloud;

import java.io.IOException;
import java.util.Optional;

import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.NextcloudUserCredentials;

public interface UserRepository {
    Optional<NextcloudUserCredentials> getCredentialsForCurrentUser();

    Optional<NextcloudUserCredentials> getCredentialsForUser(String name);

    void saveCredentialsForUser(String name, NextcloudUserCredentials credentials) throws Exception;

    void saveCredentialsForCurrentUser(NextcloudUserCredentials credentials) throws IOException;
}
