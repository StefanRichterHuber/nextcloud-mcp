package io.github.stefanrichterhuber.nextcloudmcp.auth;

import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

@ApplicationScoped
public class StandardNextcloudAuthProvider implements NextcloudAuthProvider {
    @Inject
    UserRepository userRepository;

    @Override
    public MultivaluedMap<String, String> getCustomHeaders() {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add("OCS-APIRequest", "true");
        return headers;
    }

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
    public String getUrl() {
        return userRepository.getCredentialsForCurrentUser()
                .orElseThrow(() -> new IllegalStateException("No credentials found for current user")).server();
    }

}
