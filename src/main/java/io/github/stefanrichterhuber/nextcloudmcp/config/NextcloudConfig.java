package io.github.stefanrichterhuber.nextcloudmcp.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "nextcloud")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface NextcloudConfig {
    /**
     * Root url of the nextcloud installation (e.g. 'https://nextcloud.example.com')
     *
     * @return
     */
    String url();

    /**
     * Name of this application (required to get the correct app password for the
     * nextcloud rest api).
     *
     * @return
     */
    @WithDefault("nextcloud-mcp")
    String appName();

    /**
     * Whether to use the user OIDC token for authentication instead of the app
     * password. This only works if the app 'user_oidc' is installed on the
     * nextcloud instance and configured to accept the configured OIDC provider. If
     * this is set to true, the app password will be ignored and the token provided
     * by the OIDC provider will be used for authentication. This is more secure and
     * more convenient (since the additional login step is not required) than using
     * an app password, but requires additional configuration on the nextcloud
     * instance.
     * 
     * 
     * @return
     */
    @WithDefault("false")
    boolean userOidc();
}
