package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.stefanrichterhuber.nextcloudlib.runtime.NextcloudFileService;
import io.github.stefanrichterhuber.nextcloudlib.runtime.models.NextcloudFile;
import io.github.stefanrichterhuber.nextcloudmcp.audit.MCPAudit;
import io.github.stefanrichterhuber.nextcloudmcp.config.AppConfig;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.mcp.model.McpUiResourceCsp;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.mcp.model.UIResourceMeta;
import io.quarkiverse.mcp.server.MetaField;
import io.quarkiverse.mcp.server.MetaKey;
import io.quarkiverse.mcp.server.Resource;
import io.quarkiverse.mcp.server.ResourceContents;
import io.quarkiverse.mcp.server.ResourceManager.ResourceInfo;
import io.quarkiverse.mcp.server.TextResourceContents;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MCP tools and resource for the inline file-selector UI.
 *
 * <p>
 * Implements an <em>MCP App</em> (see {@link ConfigMCP} for background on the
 * mechanism) that lets the user browse their Nextcloud folder tree and mark
 * individual files. Marked files are registered as MCP resources via
 * {@link FilesMCP#registerFileAsResource(String)} so that they can afterwards
 * be referenced by the LLM like any other resource.
 *
 * <h2>How it works</h2>
 * <ol>
 * <li>The LLM calls the {@code file-selector-ui} tool. The tool response
 * carries a {@code ui.resourceUri} metadata field pointing to
 * {@value #RESOURCE_FILE_SELECTOR_UI_NAME} and returns the listing of the
 * root folder so the UI can render its initial view.</li>
 * <li>The MCP client fetches the {@code ui://file-selector} resource and
 * renders the HTML application inside the chat window.</li>
 * <li>As the user navigates folders, the UI calls the
 * {@code file-selector-list-folder} tool to fetch the contents of the folder
 * navigated into.</li>
 * <li>When the user submits their selection, the UI calls the
 * {@code file-selector-add-resources} tool with the list of picked file
 * paths, which registers each of them as an MCP resource.</li>
 * </ol>
 */
@ApplicationScoped
public class FileSelectorMCP {
    public static final String TOOL_FILE_SELECTOR_NAME = "file-selector-ui";
    public static final String TOOL_LIST_FOLDER_NAME = "file-selector-list-folder";
    public static final String TOOL_ADD_RESOURCES_NAME = "file-selector-add-resources";
    public static final String RESOURCE_FILE_SELECTOR_UI_NAME = "ui://file-selector";
    private static final String FILE_SELECTOR_RESOURCE_META = "{ \"resourceUri\": \""
            + RESOURCE_FILE_SELECTOR_UI_NAME + "\" }";

    // https://github.com/modelcontextprotocol/ext-apps/blob/main/specification/draft/apps.mdx
    private static final String APP_MIME_TYPE = "text/html;profile=mcp-app"; // MUST be "text/html;profile=mcp-app"

    @Inject
    Template fileselector;

    @Inject
    AppConfig appConfig;

    @Inject
    NextcloudFileService nextcloudService;

    @Inject
    MCPTool tool;

    @Inject
    FilesMCP filesMCP;

    /**
     * Provides the actual HTML for the file-selector MCP App, including all
     * required metadata to allow loading further resources and calling back to
     * this app.
     *
     * @return HTML Document as text resource
     * @throws Exception
     */
    @Resource(uri = RESOURCE_FILE_SELECTOR_UI_NAME)
    ResourceContents fileSelectorResource() throws Exception {
        final String appRootUrl = appConfig.rootUrl();
        final TemplateInstance instance = fileselector
                .data("href", appRootUrl);

        String html = instance.render();

        if (appConfig.fixResourceURIs()) {
            // Replace relative resource paths with absolute ones. Fix for
            // claude-ai-mcp issue 40
            html = html.replace("/static/bundle/", appRootUrl + "/static/bundle/");
        }

        final McpUiResourceCsp csp = new McpUiResourceCsp(List.of(appRootUrl), List.of(appRootUrl), List.of(),
                List.of(appRootUrl));
        final UIResourceMeta ui = new UIResourceMeta(csp, null, null, true);

        final Map<MetaKey, Object> meta = new HashMap<>();
        meta.put(MetaKey.from("ui"), ui);

        return new TextResourceContents(RESOURCE_FILE_SELECTOR_UI_NAME, html, APP_MIME_TYPE, meta);
    }

    /**
     * A single entry (file or folder) within a listed folder.
     *
     * @param name        the display name of the entry (last path segment).
     * @param path        the absolute Nextcloud path of the entry.
     * @param directory   {@code true} if the entry is a folder.
     * @param size        the file size in bytes, {@code null} for folders.
     * @param modified    last-modified time as a Unix timestamp in milliseconds.
     * @param contentType the MIME type reported by WebDAV, {@code null} for
     *                    folders.
     */
    @RegisterForReflection
    public record FolderEntry(String name, String path, boolean directory, Long size, Long modified,
            String contentType) {
    }

    /**
     * The contents of a single folder, as shown by the file-selector UI.
     *
     * @param path       the absolute path of the listed folder.
     * @param parentPath the absolute path of the parent folder, or {@code null}
     *                   if {@code path} is already the root folder.
     * @param entries    the immediate children of the folder, folders first, then
     *                   files, both alphabetically.
     */
    @RegisterForReflection
    public record FolderListing(String path, String parentPath, List<FolderEntry> entries) {
    }

    @MetaField(name = "ui", type = MetaField.Type.JSON, value = FILE_SELECTOR_RESOURCE_META)
    @Tool(name = TOOL_FILE_SELECTOR_NAME, title = "Select Nextcloud files to expose as resources", description = "MCP App to browse the user's Nextcloud files and mark files to be registered as MCP resources", structuredContent = true)
    @MCPAudit
    public FolderListing fileSelector() {
        tool.assertUserLoggedIn();
        return listFolder("/");
    }

    /**
     * Tool used internally by the file-selector UI to navigate into a folder.
     *
     * @param path the folder path to list.
     * @return the listing of the given folder.
     */
    @Tool(name = TOOL_LIST_FOLDER_NAME, title = "List folder for file selector", description = "Lists the contents of a folder for the file selector MCP App. Only used internally by the file selector UI.", structuredContent = true)
    @MCPAudit
    public FolderListing listFolderTool(
            @ToolArg(name = "path", description = "The folder path to list. For example, '/' for the root directory or '/Documents' for the Documents folder.") String path) {
        tool.assertUserLoggedIn();
        return listFolder(path);
    }

    private FolderListing listFolder(String path) {
        final String normalizedPath = normalizePath(path);
        final String selfKey = stripSlashes(normalizedPath);

        try {
            final List<NextcloudFile> files = nextcloudService.listFiles(normalizedPath, 1);

            final List<FolderEntry> entries = new ArrayList<>();
            for (NextcloudFile file : files) {
                final String key = stripSlashes(file.path());
                if (key.equals(selfKey)) {
                    // The folder itself, returned by the WebDAV depth-1 listing, not a child entry
                    continue;
                }
                final boolean directory = file.path().endsWith("/");
                final String entryPath = "/" + key;
                final String name = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
                final String contentType = !directory && file.dataSource() != null
                        ? file.dataSource().getContentType()
                        : null;
                final Long size = !directory ? file.contentLength() : null;
                final Long modified = file.modified() != null ? file.modified().getTime() : null;
                entries.add(new FolderEntry(name, entryPath, directory, size, modified, contentType));
            }

            entries.sort((a, b) -> {
                if (a.directory() != b.directory()) {
                    return a.directory() ? -1 : 1;
                }
                return a.name().compareToIgnoreCase(b.name());
            });

            final String parentPath = "/".equals(normalizedPath) ? null : parentOf(normalizedPath);
            return new FolderListing(normalizedPath, parentPath, entries);
        } catch (IOException e) {
            throw new ToolCallException(String.format("Failed to list folder '%s': %s", path, e.getMessage()));
        }
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        if (path.contains("..")) {
            throw new ToolCallException("Path must not contain '..' to prevent directory traversal");
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return p;
    }

    private static String stripSlashes(String path) {
        String p = path;
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String parentOf(String normalizedPath) {
        final String stripped = stripSlashes(normalizedPath);
        final int idx = stripped.lastIndexOf('/');
        return idx < 0 ? "/" : "/" + stripped.substring(0, idx);
    }

    /**
     * A file that was successfully registered as an MCP resource.
     *
     * @param path        the Nextcloud path that was registered.
     * @param resourceUri the URI of the resulting MCP resource.
     */
    public record AddedResource(String path, String resourceUri) {
    }

    /**
     * Result of registering a batch of user-selected files as MCP resources.
     *
     * @param added  the files that were successfully registered.
     * @param failed a map from Nextcloud path to error message, for files that
     *               could not be registered.
     */
    @RegisterForReflection
    public record AddResourcesResult(List<AddedResource> added, Map<String, String> failed) {
    }

    /**
     * Tool to register the files marked by the user in the file-selector UI as
     * MCP resources.
     *
     * @param paths the Nextcloud paths of the files to register.
     * @return the outcome of the registration, listing both the added resources
     *         and the paths that failed.
     */
    @Tool(name = TOOL_ADD_RESOURCES_NAME, title = "Add selected files as MCP resources", description = "Registers the given Nextcloud file paths as MCP resources so they can be referenced by the LLM. Only used internally by the file selector UI.", structuredContent = true)
    @MCPAudit
    public AddResourcesResult addResources(
            @ToolArg(name = "paths", description = "List of Nextcloud file paths to register as MCP resources") List<String> paths) {
        tool.assertUserLoggedIn();

        final List<AddedResource> added = new ArrayList<>();
        final Map<String, String> failed = new HashMap<>();

        if (paths != null) {
            for (String path : paths) {
                try {
                    final ResourceInfo info = filesMCP.registerFileAsResource(path);
                    added.add(new AddedResource(path, info.uri()));
                } catch (Exception e) {
                    failed.put(path, e.getMessage());
                }
            }
        }
        return new AddResourcesResult(added, failed);
    }
}
