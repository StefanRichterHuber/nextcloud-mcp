package io.github.stefanrichterhuber.nextcloudmcp.auth;

import java.security.Principal;

import io.github.stefanrichterhuber.nextcloudlib.runtime.auth.NextcloudAuthProvider;
import io.github.stefanrichterhuber.nextcloudlib.runtime.models.NextcloudUserCredentials;
import io.github.stefanrichterhuber.nextcloudlib.runtime.models.NextcloudUserCredentials.Mode;
import io.github.stefanrichterhuber.nextcloudmcp.config.NextcloudConfig;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.UserRepository;
import io.quarkus.security.credential.TokenCredential;
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

    private NextcloudUserCredentials creds = null;

    @Override
    public void setCredentials(NextcloudUserCredentials creds) {
        this.creds = creds;
    }

    @Override
    public NextcloudUserCredentials getCredentials() {
        if (creds == null) {

            if (config.userOidc() && !securityIdentity.isAnonymous()) {
                final Principal principal = securityIdentity.getPrincipal();
                final TokenCredential cred = securityIdentity.getCredential(TokenCredential.class);
                final String user = principal.getName();
                final String secret = cred.getToken();
                final String server = config.url();
                final Mode mode = Mode.OIDC_TOKEN;
                creds = new NextcloudUserCredentials(user, secret, server, mode);
            } else {
                creds = userRepository.getCredentialsForCurrentUser()
                        .orElseThrow(() -> new IllegalStateException("No credentials found for current user"));
            }
        }
        return creds;
    }

}
