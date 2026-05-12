package io.github.stefanrichterhuber.nextcloudmcp.config;

import org.jboss.logging.Logger;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class NextcloudConfigLogger {

    @Inject
    Logger logger;

    @Inject
    NextcloudConfig nextcloudConfig;

    @Inject
    AppConfig appConfig;

    @Startup
    void logConfig() {
        logger.infof("""
                Current configuration:
                * `nextcloud.url`: %s
                * `nextcloud.appName`: %s
                * `app.audit.enabled`: %s
                * `app.audit.file`: %s
                * `app.root-url` : %s
                * `app.user-respository.file`: %s
                    """,
                nextcloudConfig.url(),
                nextcloudConfig.appName(),
                appConfig.audit().enabled() ? "true" : "false",
                appConfig.audit().target(),
                appConfig.rootUrl(),
                appConfig.userRepository().file()

        );
    }
}
