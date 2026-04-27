package io.github.stefanrichterhuber.nextcloudmcp.config;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ConfigLogger {

    @Inject
    Logger log;

    @Inject
    NextcloudConfig config;

    @ConfigProperty(name = "app.root-url")
    String rootUrl;

    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    String authServerUrl;

    /**
     * Pre-registered client id
     */
    @ConfigProperty(name = "quarkus.oidc.client-id")
    String clientId;

    /**
     * Pre-registered client secret
     */
    @ConfigProperty(name = "quarkus.oidc.credentials.secret")
    String clientSecret;

    @Startup
    public void init() {
        log.infof("""
                Config items:
                * 'nextcloud.url':                  %s
                * 'nextcloud.appName'               %s
                * 'app.root-url'                    %s
                * 'quarkus.oidc.auth-server-url'    %s
                * 'quarkus.oidc.client-id'          %s
                * 'quarkus.oidc.credentials.secret' %s
                """,
                config.url(),
                config.appName(),
                rootUrl,
                authServerUrl,
                clientId != null && !clientId.isBlank() ? "[set]" : "[not set]",
                clientSecret != null && !clientSecret.isBlank() ? "[set]" : "[not set]");
    }
}
