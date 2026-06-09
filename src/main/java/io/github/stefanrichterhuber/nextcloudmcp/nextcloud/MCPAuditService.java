package io.github.stefanrichterhuber.nextcloudmcp.nextcloud;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.stefanrichterhuber.nextcloudlib.runtime.models.NextcloudUserCredentials;
import io.github.stefanrichterhuber.nextcloudmcp.config.AppConfig;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class MCPAuditService {
    @Inject
    Logger logger;

    @Inject
    UserRepository userRepository;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    AppConfig config;

    @Inject
    Instance<AuditLogger> auditLogger;

    /**
     * Records a single MCP tool invocation to all registered {@link AuditLogger}
     * implementations.
     * Does nothing when auditing is disabled via {@code nextcloud.audit.enabled}.
     *
     * @param tool      the {@link Tool} annotation of the invoked method
     * @param toolArgs  ordered list of {@link ToolArg} annotations describing each
     *                  parameter
     * @param argValues ordered list of runtime argument values, parallel to
     *                  {@code toolArgs}
     * @param success   {@code true} if the tool completed without error
     * @param content   the tool response contents used to derive the log message
     * @param startTime invocation start time in epoch milliseconds
     * @param endTime   invocation end time in epoch milliseconds
     */
    public void logCall(Tool tool, List<ToolArg> toolArgs, List<Object> argValues, boolean success,
            Collection<? extends Object> content,
            long startTime, long endTime) {
        if (config.audit().enabled()) {
            try {

                final String message = toolResponseContentsToMessage(content);
                final String user = securityIdentity.getPrincipal().getName();
                final Optional<NextcloudUserCredentials> credentialsForCurrentUser = userRepository
                        .getCredentialsForCurrentUser();
                final String nextcloudUser = credentialsForCurrentUser.map(creds -> creds.loginName())
                        .orElse("<not set>");
                final String nextcloudServer = credentialsForCurrentUser.map(creds -> creds.server())
                        .orElse("<not set>");
                final List<AuditLogEntry.CallArg> callArgs = new ArrayList<>();
                for (int i = 0; i < toolArgs.size(); i++) {
                    callArgs.add(
                            new AuditLogEntry.CallArg(toolArgs.get(i).name(),
                                    objectMapper.valueToTree(argValues.get(i))));
                }

                final LocalDateTime called = LocalDateTime.ofInstant(Instant.ofEpochMilli(startTime),
                        ZoneId.systemDefault());
                final Duration duration = Duration.ofMillis(endTime - startTime);

                final AuditLogEntry le = new AuditLogEntry(user, nextcloudUser, nextcloudServer,
                        tool.name(), success,
                        callArgs,
                        message,
                        called,
                        duration);

                auditLogger.forEach(al -> al.log(le));
            } catch (Exception e) {
                logger.errorf(e, "Failed to write mcp tool call to audit log.");
            }
        }
    }

    /**
     * Extracts and concatenates the text from all {@link TextContent} items in the
     * response,
     * then truncates the result to the configured
     * {@code nextcloud.audit.message-length} limit.
     *
     * @param contents the tool response contents
     * @return the (possibly truncated) text representation, or an empty string if
     *         no text content is present
     */
    private String toolResponseContentsToMessage(Collection<? extends Object> contents) {
        String message = contents.stream()
                .map(c -> {
                    try {
                        return c instanceof TextContent tc ? tc.text()
                                : (c != null ? objectMapper.writeValueAsString(c) : "null");
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.joining("\n"));

        // Limit message length
        message = message.length() > config.audit().messageLength()
                ? message.substring(0, config.audit().messageLength()) + " ..."
                : message;
        return message;
    }

}
