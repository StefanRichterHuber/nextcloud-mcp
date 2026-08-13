package io.github.stefanrichterhuber.nextcloudmcp.config;

import java.io.File;
import java.nio.file.Path;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "app")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface AppConfig {

    @WithDefault("http://localhost:8080")
    String rootUrl();

    UserRepository userRepository();

    /**
     * All relative resource URIs in the MCP response are automatically rewritten to
     * absolute URLs pointing to the MCP server. This is useful for MCP clients that
     * do not support relative resource URIs (like claude)
     * 
     * @return
     */
    @WithDefault("true")
    boolean fixResourceURIs();

    public interface UserRepository {
        @WithDefault("users.json")
        Path file();
    }

    /**
     * Audit tool calls
     */
    AuditLog audit();

    public interface AuditLog {
        @WithDefault("false")
        boolean enabled();

        @WithDefault("audit.log")
        File target();

        @WithDefault("500")
        int messageLength();
    }
}
