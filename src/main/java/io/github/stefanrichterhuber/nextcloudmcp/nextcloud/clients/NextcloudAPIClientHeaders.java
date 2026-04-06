package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients;

import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

import io.github.stefanrichterhuber.nextcloudmcp.auth.NextcloudAuthProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

/**
 * Decorates out-going rest service calls to nextcloud with the necessary
 * authentication headers, depending on the configured authentication mode.
 */
@ApplicationScoped
public class NextcloudAPIClientHeaders implements ClientHeadersFactory {

    @Inject
    NextcloudAuthProvider provider;

    @Override
    public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incomingHeaders,
            MultivaluedMap<String, String> clientOutgoingHeaders) {

        final MultivaluedMap<String, String> result = new MultivaluedHashMap<>();
        final String authHeader = provider.getAuthorizationHeader();
        result.putSingle("Authorization", authHeader);
        result.putSingle("OCS-APIRequest", "true");

        return result;
    }
}
