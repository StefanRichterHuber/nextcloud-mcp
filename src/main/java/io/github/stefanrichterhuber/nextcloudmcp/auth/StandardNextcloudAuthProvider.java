package io.github.stefanrichterhuber.nextcloudmcp.auth;

import io.github.stefanrichterhuber.nextcloudlib.runtime.auth.NextcloudAuthProvider;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class StandardNextcloudAuthProvider implements NextcloudAuthProvider {
    @Inject
    UserRepository userRepository;

    @Override
    public String getUser() {
        return userRepository.getCredentialsForCurrentUser()
                .orElseThrow(() -> new IllegalStateException("No credentials found for current user")).loginName();
    }

    @Override
    public String getPassword() {
        return userRepository.getCredentialsForCurrentUser()
                .orElseThrow(() -> new IllegalStateException("No credentials found for current user")).appPassword();
    }

    @Override
    public String getServer() {
        return userRepository.getCredentialsForCurrentUser()
                .orElseThrow(() -> new IllegalStateException("No credentials found for current user")).server();
    }

}
