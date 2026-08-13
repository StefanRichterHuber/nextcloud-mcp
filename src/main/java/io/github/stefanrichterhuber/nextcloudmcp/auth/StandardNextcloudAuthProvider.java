package io.github.stefanrichterhuber.nextcloudmcp.auth;

import java.util.Base64;

import io.github.stefanrichterhuber.nextcloudlib.runtime.auth.NextcloudAuthProvider;
import io.github.stefanrichterhuber.nextcloudmcp.config.NextcloudConfig;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.UserRepository;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class StandardNextcloudAuthProvider implements NextcloudAuthProvider {
    @Inject
    UserRepository userRepository;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    NextcloudConfig config;

    @Override
    public String getUser() {
        if (config.userOidc()) {
            return securityIdentity.getPrincipal().getName();
        }
        return userRepository.getCredentialsForCurrentUser()
                .orElseThrow(() -> new IllegalStateException("No credentials found for current user")).loginName();
    }

    @Override
    public String getPassword() {
        if (config.userOidc()) {
            throw new UnsupportedOperationException(
                    "Method 'getPassword' is not supported when using OIDC authentication");
        }
        return userRepository.getCredentialsForCurrentUser()
                .orElseThrow(() -> new IllegalStateException("No credentials found for current user")).appPassword();

    }

    @Override
    public String getServer() {
        if (config.userOidc()) {
            return config.url();
        }
        return userRepository.getCredentialsForCurrentUser()
                .orElseThrow(() -> new IllegalStateException("No credentials found for current user")).server();
    }

    @Override
    public void setUser(String user) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'setUser'");
    }

    @Override
    public void setPassword(String password) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'setPassword'");
    }

    @Override
    public void setServer(String server) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'setServer'");
    }

    /**
     * Returns a Basic-Auth Authorization header build from {@link #getUser()} and
     * {@link #getPassword()}
     * 
     * @return
     */
    public String getAuthorizationHeader() {
        if (config.userOidc()) {
            final AccessTokenCredential cred = securityIdentity.getCredential(AccessTokenCredential.class);
            System.out.println("Token: " + cred.getToken());
            return "Bearer " + cred.getToken();
        }
        String valueToEncode = getUser() + ":" + getPassword();
        return "Basic " + Base64.getEncoder().encodeToString(valueToEncode.getBytes());
    }

}
