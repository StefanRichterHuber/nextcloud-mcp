package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients;

import java.net.URI;
import java.time.Duration;
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

    /**
     * Results of the login flow initiation, containing the URL the user has to
     * click
     * and a future that will be completed once the user has finished the login flow
     */
    public record LoginFlowJob(String loginUrl, CompletionStage<NextcloudUserCredentials> session) {

    }

    /**
     * Exception thrown when the login flow fails, either because the user did not
     * finish the login process within 20 minutes, or because of an unexpected error
     * during the login flow process.
     */
    public class LoginFLowFailedException extends RuntimeException {
        public LoginFLowFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Nextcloud login flow Token valid for max 20 minutes
     * 
     * @see https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html
     */
    private static Duration TOKEN_MAXTIME = Duration.ofMinutes(20);

    /**
     * Check for finished login flow every x seconds
     */
    private static final Duration TOKEN_POLL_RETRY = Duration.ofSeconds(5);

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
                TOKEN_POLL_RETRY.getSeconds(),
                TimeUnit.SECONDS);

        return new LoginFlowJob(url, session);

    }

    /**
     * Polls the login token previously generated with
     * {@link #initiateLoginFlow(String, String)}. Completes the given future once
     * the user has finished the login flow and the token is valid. If the token is
     * not valid yet, retries until the token expires after 20 minutes.
     * If the token expires, completes the future exceptionally with a timeout
     * exception.
     * 
     * @param loginFlowClient the rest client to call the poll endpoint
     * @param token           the login token to poll for
     * @param pollurl         the URL of the poll endpoint (provided by the response
     *                        of the login flow initiation)
     * @param remainingTime   the remaining time until the token expires (starts
     *                        with {@link #TOKEN_MAXTIME} and is reduced by
     *                        {@link #TOKEN_POLL_RETRY} on each
     *                        retry)
     * @param result          the future to complete once the user has finished the
     *                        login flow and the token is valid, or to complete
     *                        exceptionally if the token expires
     * 
     * @see https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html
     */
    private void pollLoginToken(NextcloudLoginFlowRestClient loginFlowClient,
            String token, String pollurl, Duration remainingTime,
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
            if (e.getResponse().getStatus() == Status.NOT_FOUND.getStatusCode() && remainingTime.getSeconds() > 0) {
                Duration newRemainingTime = remainingTime.minus(TOKEN_POLL_RETRY);

                executorService.schedule(
                        () -> pollLoginToken(loginFlowClient, token, pollurl,
                                newRemainingTime, result),
                        TOKEN_POLL_RETRY.getSeconds(),
                        TimeUnit.SECONDS);
            } else {
                result.completeExceptionally(new LoginFLowFailedException("Loginflow timeout after 20min", e));
            }
        } catch (Exception e) {
            result.completeExceptionally(new LoginFLowFailedException("Unexpected error during login flow", e));
        }
    }
}
