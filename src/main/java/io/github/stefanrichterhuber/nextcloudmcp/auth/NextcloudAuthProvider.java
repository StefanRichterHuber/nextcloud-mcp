package io.github.stefanrichterhuber.nextcloudmcp.auth;

import java.util.Base64;

import jakarta.ws.rs.core.MultivaluedMap;

public interface NextcloudAuthProvider {

    /**
     * Additional headers for the request. Usually at least containes a
     * "OCS-APIRequest": "true"
     *
     * @return
     */
    MultivaluedMap<String, String> getCustomHeaders();

    /**
     * User for basic auth header
     *
     * @return
     */
    String getUser();

    /**
     * Password for basic auth header
     *
     * @return
     */
    String getPassword();

    /**
     * Nextcloud URL to connect to, e.g. https://nextcloud.example.com:8080
     *
     * @return
     */
    String getUrl();

    /**
     * Returns a Basic-Auth Authorization header build from {@link #getUser()} and
     * {@link #getPassword()}
     * 
     * @return
     */
    default String getAuthorizationHeader() {
        String valueToEncode = getUser() + ":" + getPassword();
        return "Basic " + Base64.getEncoder().encodeToString(valueToEncode.getBytes());
    }
}
