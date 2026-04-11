package io.github.stefanrichterhuber.nextcloudmcp.config;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "nextcloud")
public interface NextcloudConfig {
    /**
     * Root url of the nextcloud installation (e.g. 'https://nextcloud.example.com')
     *
     * @return
     */
    String url();

    /**
     * Name of this applicaition (required to get the correct app password for the
     * nextcloud rest api).
     *
     * @return
     */

    String appName();
}
