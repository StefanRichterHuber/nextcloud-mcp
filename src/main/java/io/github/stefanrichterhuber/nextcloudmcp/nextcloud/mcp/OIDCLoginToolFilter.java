package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.mcp;

import java.util.List;

import io.github.stefanrichterhuber.nextcloudmcp.config.NextcloudConfig;
import io.quarkiverse.mcp.server.FilterContext;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class OIDCLoginToolFilter implements ToolFilter {
    private static final List<String> APP_PASSWORD_SPECIFIC_TOOLS = List.of(LoginMCP.TOOL_INITIATE_LOGIN_NAME,
            LoginMCP.TOOL_DELETE_LOGIN_NAME);

    @Inject
    NextcloudConfig config;

    /**
     * Disables all tools specific for maintaining Nextcloud App Passwords when OIDC
     * token passthrough is enabled
     */
    @Override
    public boolean test(ToolInfo tool, FilterContext context) {
        if (config.userOidc() && APP_PASSWORD_SPECIFIC_TOOLS.contains(tool.name())) {
            return false;
        }
        return true;
    }
}
