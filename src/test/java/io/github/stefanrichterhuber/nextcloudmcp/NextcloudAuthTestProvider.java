package io.github.stefanrichterhuber.nextcloudmcp;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.github.stefanrichterhuber.nextcloudmcp.auth.NextcloudAuthProvider;
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

    @Override
    public String getUser() {
        return user;
    }

    @Override
    public String getPassword() {
        return password;

    }

    @Override
    public String getServer() {
        return url;
    }

}
