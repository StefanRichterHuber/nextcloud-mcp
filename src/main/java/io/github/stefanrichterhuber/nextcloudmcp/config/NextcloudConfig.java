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
    @WithDefault("quarkus-nextcloud-lib")
    String appName();
}
