package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.ClientWebApplicationException;

import io.github.stefanrichterhuber.nextcloudmcp.config.NextcloudConfig;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.NextcloudLoginFlowRestClient.InitiateLoginFlowV2Response;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.NextcloudLoginFlowRestClient.NextcloudAppCredentials;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.NextcloudUserCredentials;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

/**
 * Service for handling the Nextcloud Login Flow. It initiates the login flow,
 * polls for the generated token
 */
@ApplicationScoped
public class NextcloudLoginService {

    @Inject
    NextcloudConfig config;

    @Inject
    ScheduledExecutorService executorService;

    @Inject
    Logger log;

    public record LoginFlowJob(String loginUrl, CompletionStage<NextcloudUserCredentials> session) {

    }

    /**
     * Nextcloud login flow Token valid for max 20 minutes
     * 
     * @see https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html
     */
    private static int TOKEN_MAXTIME = 20 * 60;

    /**
     * Check for finished login flow every 10 seconds
     */
    private static final int TOKEN_POLL_RETRY = 5;

    /**
     * Initiates the Nextcloud Login Flow V2 for the configured default nextcloud
     * server. Returns the URL the user has to click
     * and starts polling the token endpoint in parallel
     * 
     * @see https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html
     */
    public LoginFlowJob initiateLoginFlow() {
        return initiateLoginFlow(config.url());
    }

    /**
     * Initiates the Nextcloud Login Flow V2. Returns the URL the user has to click
     * and starts polling the token endpoint in parallel
     * 
     * @param server Nextcloud server URL (including http(s) and port if needed,
     *               e.g. https://nextcloud.example.com:8080)
     * 
     * @see https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html
     */
    public LoginFlowJob initiateLoginFlow(String server) {
        final NextcloudLoginFlowRestClient loginFlowClient = QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(server))
                .followRedirects(true)
                .build(NextcloudLoginFlowRestClient.class);

        final InitiateLoginFlowV2Response r = loginFlowClient.initiateLoginFlowV2(config.appName());
        final String url = r.login();

        final CompletableFuture<NextcloudUserCredentials> session = new CompletableFuture<>();

        executorService.schedule(
                () -> pollLoginToken(loginFlowClient, r.poll().token(), r.poll().endpoint(),
                        TOKEN_MAXTIME, session),
                TOKEN_POLL_RETRY,
                TimeUnit.SECONDS);

        return new LoginFlowJob(url, session);

    }

    /**
     * Polls the login token previously generated with
     * {@link #initiateLoginFlow(String, String)}.
     * 
     * @see https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html
     */
    private void pollLoginToken(NextcloudLoginFlowRestClient loginFlowClient,
            String token, String pollurl, int remainingTime,
            CompletableFuture<NextcloudUserCredentials> result) {
        try {
            final Response response = loginFlowClient.pollLoginFlowV2(token);
            final NextcloudAppCredentials cr = response.readEntity(NextcloudAppCredentials.class);

            NextcloudUserCredentials session = new NextcloudUserCredentials(cr.loginName(), cr.appPassword(),
                    cr.server());

            result.complete(session);

        } catch (ClientWebApplicationException e) {
            // Failed as expected (fails with 404 as long the user has not finished the
            // login process)
            if (e.getResponse().getStatus() == Status.NOT_FOUND.getStatusCode() && remainingTime > 0) {
                executorService.schedule(
                        () -> pollLoginToken(loginFlowClient, token, pollurl,
                                remainingTime - TOKEN_POLL_RETRY, result),
                        TOKEN_POLL_RETRY,
                        TimeUnit.SECONDS);
            } else {
                result.completeExceptionally(new RuntimeException("Loginflow timeout after 20min", e));
            }
        }
    }
}
