package io.github.stefanrichterhuber.nextcloudmcp;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.github.stefanrichterhuber.nextcloudlib.runtime.auth.NextcloudAuthProvider;
import io.github.stefanrichterhuber.nextcloudlib.runtime.models.NextcloudUserCredentials;
import io.github.stefanrichterhuber.nextcloudlib.runtime.models.NextcloudUserCredentials.Mode;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

@ApplicationScoped
@Alternative
@Priority(1)
public class NextcloudAuthTestProvider implements NextcloudAuthProvider {
    @Inject
    @ConfigProperty(name = "nextcloud.user")
    String user;

    @Inject
    @ConfigProperty(name = "nextcloud.password")
    String password;

    @Inject
    @ConfigProperty(name = "nextcloud.url")
    String url;

    private NextcloudUserCredentials creds = null;

    @Override
    public void setCredentials(NextcloudUserCredentials creds) {
        this.creds = creds;
    }

    @Override
    public NextcloudUserCredentials getCredentials() {
        if (creds == null) {
            creds = new NextcloudUserCredentials(user, password, url, Mode.APP_PASSWORD);
        }
        return creds;
    }

}
