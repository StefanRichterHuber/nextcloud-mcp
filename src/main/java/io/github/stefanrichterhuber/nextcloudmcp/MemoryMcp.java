package io.github.stefanrichterhuber.nextcloudmcp;

import io.quarkiverse.mcp.server.Tool;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MemoryMcp {
    private static final String TO_LOWER_CASE_DESCRIPTION = """
            Converts the string value to lower case
            """;

    @Tool(description = TO_LOWER_CASE_DESCRIPTION)
    String toLowerCase(String value) {
        return value.toLowerCase();
    }
}
