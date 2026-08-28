package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.mcp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.tika.parser.txt.CharsetDetector;
import org.apache.tika.parser.txt.CharsetMatch;
import org.jboss.logging.Logger;

import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import com.github.difflib.patch.PatchFailedException;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.stefanrichterhuber.nextcloudlib.runtime.NextcloudCommentService;
import io.github.stefanrichterhuber.nextcloudlib.runtime.NextcloudFileDiffService;
import io.github.stefanrichterhuber.nextcloudlib.runtime.NextcloudFileService;
import io.github.stefanrichterhuber.nextcloudlib.runtime.NextcloudSystemTagService;
import io.github.stefanrichterhuber.nextcloudlib.runtime.NextcloudUserService;
import io.github.stefanrichterhuber.nextcloudlib.runtime.models.Comment;
import io.github.stefanrichterhuber.nextcloudlib.runtime.models.FulltextSearchQuery;
import io.github.stefanrichterhuber.nextcloudlib.runtime.models.FulltextSearchResult;
import io.github.stefanrichterhuber.nextcloudlib.runtime.models.NextcloudFile;
import io.github.stefanrichterhuber.nextcloudlib.runtime.models.SystemTag;
import io.github.stefanrichterhuber.nextcloudmcp.audit.MCPAudit;
import io.github.stefanrichterhuber.nextcloudmcp.config.NextcloudConfig;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.EmbeddingService;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.UserRepository;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.mcp.FilesMCP.SearchFileResults.SearchFileResult;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.mcp.FilesMCP.SearchinFileResult.SearchMatch;
import io.quarkiverse.mcp.server.BlobResourceContents;
import io.quarkiverse.mcp.server.McpLog;
import io.quarkiverse.mcp.server.MetaKey;
import io.quarkiverse.mcp.server.ResourceManager;
import io.quarkiverse.mcp.server.ResourceManager.ResourceInfo;
import io.quarkiverse.mcp.server.ResourceResponse;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.TextResourceContents;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.tika.TikaParser;
import jakarta.activation.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MCP tools for Nextcloud file operations.
 *
 * <p>
 * This bean exposes the core file-management capabilities of the Nextcloud MCP
 * server as
 * individual MCP tools. All tools require the calling user to have completed
 * the Nextcloud
 * login flow first (enforced via {@link #assertUserLoggedIn()}).
 *
 * <p>
 * The tools are designed around a collaborative editing workflow:
 * <ol>
 * <li>Browse and read files with {@code list-files} and
 * {@code get-file-content}.</li>
 * <li>Inspect change history with {@code get-file-revisions}.</li>
 * <li>Compute a human-reviewable diff between any two revisions with
 * {@code create-file-diff}.</li>
 * <li>Apply an approved patch back to the latest revision with
 * {@code apply-file-patch}, which causes Nextcloud to create a new revision
 * automatically.</li>
 * </ol>
 *
 * <p>
 * Visibility of files is governed by {@link #isVisibleFile(NextcloudFile)}:
 * only files
 * whose MIME type appears in {@link #visibleContentTypes} are surfaced to the
 * LLM. Per-user
 * file-pattern and content-type filtering
 * ({@link UserRepository.UserAccessConfig}) is
 * partially implemented; see inline TODOs for the remaining work.
 */
@ApplicationScoped
public class FilesMCP {
    public static final String TOOL_SEARCH_FILES_NAME = "search-files";
    public static final String TOOL_SEARCH_FILE_NAME = "search-in-file";
    public static final String TOOL_GET_FILE_COMMENTS = "get-file-comments";
    public static final String TOOL_ADD_FILE_COMMENT = "add-file-comment";
    public static final String TOOL_CREATE_FILE_DIFF_NAME = "create-file-diff";
    public static final String TOOL_DELETE_FILE_NAME = "delete-file";
    public static final String TOOL_WRITE_FILE_NAME = "write-file";
    public static final String TOOL_APPLY_FILE_PATCH_NAME = "apply-file-patch";
    public static final String TOOL_LIST_FILES_NAME = "list-files";
    public static final String TOOL_GET_FILE_TAGS = "get-file-tags";
    public static final String TOOL_ADD_FILE_TAGS = "add-file-tags";
    public static final String TOOL_REMOVE_FILE_TAGS = "remove-file-tags";
    private static final String TOOL_LIST_FILES_DESCRIPTION = "Lists files in the user's Nextcloud account. User must be logged in to use this tool.";

    @Inject
    UserRepository userRepository;

    @Inject
    ResourceManager resourceManager;

    @Inject
    NextcloudFileService nextcloudService;

    @Inject
    NextcloudSystemTagService tagService;

    @Inject
    EmbeddingService embeddingService;

    @Inject
    TikaParser parser;

    @Inject
    NextcloudFileDiffService diffService;

    @Inject
    NextcloudCommentService commentService;

    @Inject
    NextcloudConfig config;

    @Inject
    Logger logger;

    @Inject
    MCPTool tools;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    NextcloudUserService userService;

    // TODO make configurable per user or through some ui or config file, e.g. to
    // also show certain file types that are currently hidden
    private static final List<String> visibleContentTypes = List.of("text/", "application/json", "application/xml",
            "application/javascript",
            "application/xhtml+xml");

    /**
     * Registers a Nextcloud file as a named MCP resource so that MCP clients can
     * fetch it by URI later. If a resource with the same ID already exists it is
     * returned unchanged (idempotent).
     *
     * <p>
     * The resource ID is {@code <url>@<modifiedMillis>}, which encodes both the
     * file path and the exact revision timestamp so that different revisions of the
     * same file map to distinct resource URIs.
     *
     * @param url  the Nextcloud WebDAV path of the file.
     * @param file the file metadata and data source.
     * @return the {@link ResourceInfo} for the registered (or already existing)
     *         resource.
     */
    @SuppressWarnings("unused")
    private ResourceInfo registerNextcloudFileResource(String url, NextcloudFile file) {

        final String resourceId = String.format("%s@%d", url, file.modified().getTime());
        final ResourceInfo existing = resourceManager.getResource(resourceId);
        if (existing != null) {
            return existing;
        }

        final int size = file.contentLength() != null ? file.contentLength().intValue() : 0;
        return resourceManager
                .newResource(resourceId)
                .setSize(size)
                .setUri(resourceId)
                .setTitle(resourceId)
                .setDescription(String.format("File '%s' from Nextcloud with modified date %d", url,
                        file.modified().getTime()))
                .setMetadata(getNextcloudFileMetadata(file))
                .setHandler(args -> getNextcloudFileResource(url, file))

                .register();
    }

    /**
     * Reads a Nextcloud file and returns it as a typed MCP
     * {@link ResourceResponse}.
     *
     * <p>
     * Text-like MIME types (starting with {@code text/}, or matching
     * {@code application/json}, {@code application/xml},
     * {@code application/javascript},
     * {@code application/xhtml+xml}) are returned as a {@link TextResourceContents}
     * with
     * charset auto-detected via Tika. All other types are Base64-encoded and
     * returned as
     * a {@link BlobResourceContents}.
     *
     * @param url  the Nextcloud WebDAV path used as the resource URI.
     * @param file the file metadata and data source.
     * @return a resource response containing either the text or the encoded binary
     *         content.
     * @throws ToolCallException if the file content cannot be read.
     */
    private ResourceResponse getNextcloudFileResource(String url, NextcloudFile file) {
        // Check if Text or blob and set content correctly
        DataSource dataSource = file.dataSource();
        try (InputStream is = dataSource.getInputStream()) {
            byte[] content = is.readAllBytes();

            if (dataSource.getContentType().startsWith("text/")
                    || dataSource.getContentType().equals("application/json")
                    || dataSource.getContentType().equals("application/xml")
                    || dataSource.getContentType().equals("application/javascript")
                    || dataSource.getContentType().equals("application/xhtml+xml")) {
                Charset cs = detectCharset(content).orElse(StandardCharsets.UTF_8);
                String text = new String(content, cs);
                TextResourceContents result = new TextResourceContents(url, text, dataSource.getContentType(),
                        getNextcloudFileMetadata(file));

                return new ResourceResponse(result);
            } else {
                String encoded = Base64.getEncoder().encodeToString(content);
                BlobResourceContents result = new BlobResourceContents(url, encoded,
                        dataSource.getContentType(),
                        getNextcloudFileMetadata(file));
                return new ResourceResponse(result);
            }

        } catch (IOException e) {
            throw new ToolCallException(e);
        }
    }

    /**
     * Builds the MCP metadata map for a Nextcloud file.
     *
     * <p>
     * The following keys are populated when present on the file:
     * <ul>
     * <li>{@code nextcloud/fileId} — the Nextcloud internal file ID.</li>
     * <li>{@code nextcloud/modified} — last-modified time as a Unix timestamp in
     * milliseconds.</li>
     * <li>{@code nextcloud/contentType} — the MIME type reported by WebDAV.</li>
     * <li>{@code nextcloud/size} — the file size in bytes.</li>
     * </ul>
     *
     * @param file the Nextcloud file whose metadata should be collected.
     * @return a map of {@link MetaKey} to metadata values, never {@code null}.
     */
    private static Map<MetaKey, Object> getNextcloudFileMetadata(NextcloudFile file) {
        Map<MetaKey, Object> metadata = new HashMap<>();
        if (file.fileId() != null)
            metadata.put(MetaKey.from("nextcloud/fileId"), file.fileId());
        if (file.modified() != null)
            metadata.put(MetaKey.from("nextcloud/modified"), file.modified().getTime());
        if (file.dataSource() != null && file.dataSource().getContentType() != null)
            metadata.put(
                    MetaKey.from("nextcloud/contentType"), file.dataSource().getContentType());
        if (file.contentLength() != null)
            metadata.put(MetaKey.from("nextcloud/size"), file.contentLength());

        return metadata;
    }

    /**
     * Decides whether a Nextcloud file should be surfaced to the LLM.
     *
     * <p>
     * A file is considered visible when its MIME type starts with one of the
     * prefixes
     * in {@link #visibleContentTypes}. Files without a data source or without a
     * declared
     * content type are always hidden.
     *
     * <p>
     * <b>Known limitations:</b> per-user file-pattern filtering (hidden patterns
     * and
     * visible-pattern allowlists from {@link UserRepository.UserAccessConfig}) is
     * not yet
     * applied here. The relevant logic is commented out pending a fix for revision
     * paths,
     * which carry only a file ID rather than a human-readable name.
     *
     * @param file the Nextcloud file to evaluate.
     * @return {@code true} if the file should be shown to the LLM, {@code false}
     *         otherwise.
     */
    private boolean isVisibleFile(NextcloudFile file) {
        // TODO make this configurable through some ui or config file, e.g. to also show
        // certain file types that are currently hidden
        DataSource dataSource = file.dataSource();
        if (dataSource == null || dataSource.getContentType() == null) {
            return false;
        }

        // First check for hidden file patterns, if it matches any of them, return false
        String fileName = file.path();

        // FIXME: Does not work for file version, because these do not have the file
        // name in the path, but only the file id. Maybe we can get the file name from
        // the metadata or content of the file?
        // if (hiddenFilePatterns.stream().map(pattern -> pattern.replace(".",
        // "\\.").replace("*", ".*"))
        // .anyMatch(p -> fileName.matches(p))) {
        // logger.debugf("File '%s' is hidden because it matches hidden file pattern:
        // %s", fileName,
        // hiddenFilePatterns);
        // return false;
        // }

        // if (!filePatterns.stream().map(pattern -> pattern.replace(".",
        // "\\.").replace("*", ".*"))
        // .anyMatch(p -> fileName.matches(p))) {
        // logger.debugf("File '%s' is hidden because it does not match any visible file
        // pattern: %s", fileName,
        // filePatterns);
        // return false;
        // }

        // Check if content type is in the list of visible content types, if not return
        // false
        if (!visibleContentTypes.stream().anyMatch(ct -> dataSource.getContentType().startsWith(ct))) {
            logger.debugf("File '%s' is hidden because it does not match any supported content type: %s", fileName,
                    visibleContentTypes);
            return false;
        }
        return true;
    }

    /**
     * Path-only visibility check used by the full-text search result filter.
     *
     * <p>
     * This overload operates on a plain file path string rather than a full
     * {@link NextcloudFile} object (search results do not carry a data source).
     * Per-user file-pattern filtering is not yet implemented here.
     *
     * @param file the Nextcloud file path to evaluate (e.g.
     *             {@code /Documents/notes.md}).
     * @return {@code true} always — pattern-based filtering is not yet implemented.
     */
    private boolean isVisibleFile(String file) {
        // TODO implement
        return true;
    }

    /**
     * Utility method to the detect the Charset of a file
     * 
     * @param content binary file content
     * @return Charset or null, if none detected
     */
    private Optional<Charset> detectCharset(byte[] content) {
        final CharsetDetector cd = new CharsetDetector();
        cd.setText(content);
        final CharsetMatch cm = cd.detect();
        if (cm != null) {
            final String cs = cm.getName();
            return Optional.ofNullable(Charset.forName(cs));
        } else {
            return Optional.empty();
        }
    }

    /**
     * Utility method to validate a file path string, throwing a ToolCallException
     * if the path is invalid. Currently checks for emptiness and for paths starting
     * with {@code ..} to prevent directory traversal attacks.
     * 
     * @param filePath the file path to validate
     * @throws ToolCallException
     */
    private void assertFilePathValid(String filePath) throws ToolCallException {
        if (filePath == null || filePath.isBlank()) {
            throw new ToolCallException("File path must not be empty");
        }
        if (filePath.contains("..")) {
            throw new ToolCallException("File path must not contain '..' to prevent directory traversal");
        }
    }

    /**
     * Loads a file with an optional reviision marker (like @[timestamp] or @latest)
     * attached to the file name
     * 
     * @param filePath Path of the file
     * @parma latestOnly only the latest revision of the file is supported at all
     * @return
     * @throws IOException
     */
    private NextcloudFile readFileByPathWithRevision(String filePath, boolean latestOnly) throws IOException {
        assertFilePathValid(filePath);
        Date revision = null;
        if (filePath.contains("@")) {
            String[] parts = filePath.split("@");
            filePath = parts[0];
            if (parts.length >= 2) {
                String revPart = parts[parts.length - 1];
                if (latestOnly || revPart.equals("latest")) {
                    revision = null; // latest revision
                } else {
                    try {
                        revision = new Date(Long.parseLong(revPart));
                    } catch (NumberFormatException e) {
                        throw new ToolCallException(String
                                .format("Invalid revision format: '%s'. Use '@latest' or '@<timestamp>'.", revPart));
                    }
                }
            }
        }

        final NextcloudFile file = nextcloudService.getFileByModifyDate(filePath, revision);
        if (file == null || !isVisibleFile(file)) {
            throw new ToolCallException(String.format("File '%s' with modified date %d not found", filePath,
                    revision != null ? revision.getTime() : 0));
        }

        return file;
    }

    @RegisterForReflection
    public record FileListResult(String searchPath, List<FileListEntry> files) {
        public record FileListEntry(String path, String resourceId, Map<MetaKey, Object> metadata) {
        }

        public static FileListResult fromNextcloudFiles(String searchPath, List<NextcloudFile> files) {
            final List<FileListEntry> result = new ArrayList<>();
            for (NextcloudFile file : files) {
                final String path = file.path();
                final String resourceId = String.format("%s@%d", file.path(), file.modified().getTime());
                final Map<MetaKey, Object> metadata = getNextcloudFileMetadata(file);
                result.add(new FileListEntry(path, resourceId, metadata));
            }
            return new FileListResult(searchPath, result);
        }
    }

    @Tool(name = TOOL_LIST_FILES_NAME, title = "List files in folder", description = TOOL_LIST_FILES_DESCRIPTION, structuredContent = true, annotations = @Tool.Annotations(title = "List files", destructiveHint = false, readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    @MCPAudit
    public FileListResult listFiles(
            @ToolArg(name = "path", description = "The path to list files from. For example, '/' for the root directory or '/Documents' for the Documents folder.") String path) {
        tools.assertUserLoggedIn();
        assertFilePathValid(path);

        try {
            final List<NextcloudFile> files = nextcloudService.listFiles(path, -1).stream()
                    .filter(file -> !file.path().endsWith("/")).filter(this::isVisibleFile).toList();
            return FileListResult.fromNextcloudFiles(path, files);
        } catch (IOException e) {
            logger.errorf(e, "Failed to list files in folder '%s'", path);
            throw new ToolCallException("Failed to list files: " + e.getMessage());
        }
    }

    @Tool(name = "get-file-revisions", title = "Get file revisions", description = "Gets the revisions of a file in the user's Nextcloud account. User must be logged in to use this tool.", structuredContent = true, annotations = @Tool.Annotations(title = "Get file revisions", destructiveHint = false, readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    @MCPAudit
    public FileListResult getFileRevisions(
            @ToolArg(name = "filePath", description = "The path of the file to get revisions from. For example, '/Documents/file.txt'.") String filePath) {
        tools.assertUserLoggedIn();

        assertFilePathValid(filePath);
        filePath = stripRevision(filePath);
        try {
            final List<NextcloudFile> revisions = nextcloudService.listFileRevisions(filePath);
            if (revisions == null || revisions.isEmpty()) {
                throw new ToolCallException(String.format("No revisions found for file '%s'", filePath));
            }
            return FileListResult.fromNextcloudFiles(filePath, revisions);
        } catch (IOException e) {
            throw new ToolCallException(e);
        }
    }

    @Tool(name = "get-file-content", title = "Get file content", description = "Gets the content of a file as text or blob resource. User must be logged in to use this tool.", structuredContent = false, annotations = @Tool.Annotations(title = "Get file content", destructiveHint = false, readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    @MCPAudit
    public ToolResponse getFileContent(
            @ToolArg(name = "filePath", description = "The path of the file to get the content from. For example, '/Documents/file.txt'. A revision date can be specified by appending '@' followed by the timestamp. '@latest' can be used to get the latest revision.") String filePath,
            McpLog log) {
        tools.assertUserLoggedIn();
        assertFilePathValid(filePath);

        try {
            final NextcloudFile file = readFileByPathWithRevision(filePath, false);
            log.info("Found file %s, start downloading ...", filePath);
            // Read file
            try (InputStream is = file.dataSource().getInputStream()) {
                final byte[] content = is.readAllBytes();
                final Charset cs = detectCharset(content).orElse(StandardCharsets.UTF_8);
                final String text = new String(content, cs);
                log.info("Found file %s, file downloaded.", filePath);
                return ToolResponse.success(new TextContent(text));

            }
        } catch (IOException e) {
            throw new ToolCallException(e);
        }
    }

    /**
     * Serialises a list of diff deltas into a unified (git-style) patch string.
     *
     * <p>
     * The output follows the standard unified diff format:
     * 
     * <pre>
     * --- fileName1
     * +++ fileName2
     * &#64;@ -&lt;src-pos&gt;,&lt;src-size&gt; +&lt;tgt-pos&gt;,&lt;tgt-size&gt; @@
     * -removed line
     * +added line
     * </pre>
     *
     * @param deltas    the list of change deltas produced by
     *                  {@link com.github.difflib.DiffUtils}.
     * @param fileName1 label for the original file (used in the {@code ---} header
     *                  line).
     * @param fileName2 label for the modified file (used in the {@code +++} header
     *                  line).
     * @return the complete unified diff as a string.
     */
    private static String deltasToGitPatch(List<AbstractDelta<String>> deltas, String fileName1, String fileName2) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(fileName1).append("\n");
        sb.append("+++ ").append(fileName2).append("\n");

        for (AbstractDelta<String> delta : deltas) {
            sb.append("@@ -").append(delta.getSource().getPosition() + 1).append(",").append(delta.getSource().size())
                    .append(" +").append(delta.getTarget().getPosition() + 1).append(",")
                    .append(delta.getTarget().size()).append(" @@\n");
            for (String line : delta.getSource().getLines()) {
                sb.append("-").append(line).append("\n");
            }
            for (String line : delta.getTarget().getLines()) {
                sb.append("+").append(line).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Utility method to strip the revision part from a file path, if present. For
     * example, it converts "/Documents/file.txt@latest" to "/Documents/file.txt".
     * This is used in tools that operate on the latest revision of a file, ignoring
     * any revision markers.
     * 
     * @param filePath The file path
     * @return The file path without any revision marker
     */
    private static String stripRevision(String filePath) {
        if (filePath != null && filePath.contains("@")) {
            final String[] parts = filePath.split("@");
            filePath = parts[0];
        }
        return filePath;
    }

    @Tool(name = TOOL_CREATE_FILE_DIFF_NAME, title = "Create file diff", description = "Creates a diff between two files in the git patch format. User must be logged in to use this tool.", annotations = @Tool.Annotations(title = "Create file diff", destructiveHint = false, readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    @MCPAudit
    public ToolResponse createFileDiff(
            @ToolArg(name = "firstFile", description = "The path of the first file to create the diff from. For example, '/Documents/file.txt'. A revision date can be specified by appending '@' followed by the timestamp. '@latest' can be used to get the latest revision.") String firstFile,
            @ToolArg(name = "secondFile", description = "The path of the second file to create the diff from. For example, '/Documents/file.txt'. A revision date can be specified by appending '@' followed by the timestamp. '@latest' can be used to get the latest revision.") String secondFile) {
        tools.assertUserLoggedIn();
        assertFilePathValid(firstFile);
        assertFilePathValid(secondFile);

        try {
            final NextcloudFile firstFileContent = readFileByPathWithRevision(firstFile, false);
            final NextcloudFile secondFileContent = readFileByPathWithRevision(secondFile, false);

            Patch<String> patch = diffService.getContentPatch(firstFileContent, secondFileContent);
            final String gitPatch = deltasToGitPatch(patch.getDeltas(),
                    String.format("%s", firstFile),
                    String.format("%s", secondFile));

            return ToolResponse.success(gitPatch);
        } catch (IOException e) {
            throw new ToolCallException(e);
        }
    }

    @Tool(name = TOOL_DELETE_FILE_NAME, title = "Deletes a file", description = "Deletes the latest revision of a file", annotations = @Tool.Annotations(title = "Delete file", destructiveHint = true, readOnlyHint = false, idempotentHint = false, openWorldHint = false))
    @MCPAudit
    public ToolResponse deleteFile(
            @ToolArg(name = "filePath", description = "The path of the file to delete. For example, '/Documents/file.txt'. Any revision date (using '@latest' or '@<timestamp>') added to the filename will be ignored and only the latest revision will be deleted!") String filePath) {

        tools.assertUserLoggedIn();
        assertFilePathValid(filePath);
        filePath = stripRevision(filePath);
        try {
            final NextcloudFile org = nextcloudService.getFile(filePath);
            if (org == null) {
                return ToolResponse
                        .error(String.format("File '%s' can not be deleted", filePath));
            }
            if (!isVisibleFile(org)) {
                return ToolResponse
                        .error(String.format("File '%s' can not be deleted", filePath));
            }
            nextcloudService.deleteFile(filePath, org.etag(), (String) null);
        } catch (IOException e) {
            logger.errorf(e, "File '%s' can not be deleted", filePath);
            return ToolResponse
                    .error(String.format("File '%s' can not be deleted", filePath));
        }
        return ToolResponse.success("Successfully deleted file");
    }

    @Tool(name = TOOL_WRITE_FILE_NAME, title = "Writes a text file", description = "Overwrites / Creates the latest revision of a textfile", annotations = @Tool.Annotations(title = "Write file", destructiveHint = true, readOnlyHint = false, idempotentHint = false, openWorldHint = false))
    @MCPAudit
    public ToolResponse writeFile(
            @ToolArg(name = "filePath", description = "The path of the file to write. For example, '/Documents/file.txt'. Any revision date (using '@latest' or '@<timestamp>') added to the filename will be ignored and only the latest revision will be written!") String filePath,
            @ToolArg(name = "content", description = "New file content") String content,
            @ToolArg(name = "content-type", description = "Content type of the new document. e.g text/markdown") String contentType,
            @ToolArg(name = "overwrite", description = "Overwrite existing file. Defaults to false", defaultValue = "false") boolean overwrite,
            @ToolArg(name = "charset", description = "Charset for the file content. Defaults to UTF-8", defaultValue = "UTF-8") String charset) {
        tools.assertUserLoggedIn();
        assertFilePathValid(filePath);
        filePath = stripRevision(filePath);
        try {
            final NextcloudFile org = nextcloudService.getFile(filePath);
            final Charset cs = Optional.ofNullable(charset).filter(c -> !c.isBlank()).map(Charset::forName)
                    .orElse(StandardCharsets.UTF_8);
            final byte[] rawContent = content.getBytes(cs);

            if (org != null && !isVisibleFile(org)) {
                return ToolResponse
                        .error(String.format("File '%s' can not be written", filePath));
            }

            if (org != null && !overwrite) {
                return ToolResponse
                        .error(String.format("File '%s' can not be overwritten", filePath));
            }

            nextcloudService.uploadFile(filePath, contentType, new ByteArrayInputStream(rawContent));
            final NextcloudFile newFile = nextcloudService.getFile(filePath);
            final String resourceId = String.format("%s@%d", filePath, newFile.modified().getTime());
            return ToolResponse.success("Successfully written file. New revision: " + resourceId);
        } catch (IOException e) {
            logger.errorf(e, "File '%s' can not be written", filePath);
            return ToolResponse
                    .error(String.format("File '%s' can not be written", filePath));
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            logger.errorf(e, "File '%s' can not be written. Charset '%s' not supported", filePath, charset);
            return ToolResponse
                    .error(String.format("Charset '%s' not supported", charset));
        }

    }

    @Tool(name = TOOL_APPLY_FILE_PATCH_NAME, title = "Applies patch to text file", description = "Applies a git-style patch to the given latest(!) revision of a text file", annotations = @Tool.Annotations(title = "Apply file patch", destructiveHint = true, readOnlyHint = false, idempotentHint = false, openWorldHint = false))
    @MCPAudit
    public ToolResponse applyPatch(
            @ToolArg(name = "filePath", description = "The path of the file to apply the patch. For example, '/Documents/file.txt'. Any revision date (using '@latest' or '@<timestamp>') added to the filename will be ignored and only the latest revision will be patche!") String filePath,
            @ToolArg(name = "patch", description = "Git-style patch to apply") String patch,
            @ToolArg(name = "fuziness", description = "Fuzz factor for the patch. Roughly how many lines the patch definition can be off from the actual source", defaultValue = "4") int fuzziness) {
        tools.assertUserLoggedIn();
        assertFilePathValid(filePath);
        try {
            final NextcloudFile file = readFileByPathWithRevision(filePath, true);

            Patch<String> parsedPatch = null;
            if (patch != null && !patch.isBlank()) {
                final List<String> patchContent = Arrays.asList(patch.split("\n"));
                parsedPatch = UnifiedDiffUtils.parseUnifiedDiff(patchContent);
            } else {
                parsedPatch = new Patch<String>();
            }

            diffService.applyContentPatch(file, parsedPatch, fuzziness);
            // Read back file to get latest revision
            final NextcloudFile patchedFile = readFileByPathWithRevision(filePath, true);
            final String resourceId = String.format("%s@%d", filePath, patchedFile.modified().getTime());
            return ToolResponse.success("Successfully patched file. New revision: " + resourceId);

        } catch (PatchFailedException e) {
            throw new ToolCallException("Failed to apply patch to file " + filePath + ":" + e, e);
        } catch (IOException e) {
            throw new ToolCallException("Failed to read / write file " + filePath + ":" + e, e);
        }
    }

    /**
     * Result of a full-text search query across all files, containing the original
     * query and a list of matching files with excerpts and metadata.
     */
    @RegisterForReflection
    public record SearchFileResults(String query, List<SearchFileResult> results) {
        public record SearchFileResult(String filePath, List<String> excerpts, List<String> tags, long modified,
                long contentSize) {
        }
    }

    @Tool(name = TOOL_SEARCH_FILES_NAME, title = "Fulltext search on all files", description = "Performs full text search on all files", structuredContent = true, annotations = @Tool.Annotations(title = "Search files", destructiveHint = false, readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    @MCPAudit
    public SearchFileResults searchFiles(
            @ToolArg(name = "query", description = "Fulltext search query") String query,
            @ToolArg(name = "results", defaultValue = "20", description = "Maximum number of results to return. Defaults to '20'") Integer results) {
        tools.assertUserLoggedIn();
        final int pageSize = 20;
        int page = 1;
        final List<SearchFileResult> files = new ArrayList<>(results);
        search: while (true) {

            try {
                final FulltextSearchResult fullTextSearchResult = nextcloudService
                        .fulltextSearch(FulltextSearchQuery.search(query, page, pageSize));

                if (fullTextSearchResult.result().isEmpty()) {
                    break search;
                }
                for (FulltextSearchResult.Result result : fullTextSearchResult.result()) {
                    if (result.documents().isEmpty()) {
                        break search;
                    }

                    for (FulltextSearchResult.Result.Document document : result.documents()) {
                        final String path = document.info().path();

                        // Check if the LLM is allowed to see this file at all
                        if (isVisibleFile(path)) {
                            final List<String> excerpts = document.excerpts().stream().map(ex -> ex.excerpt()).toList();
                            final long modified = document.info().mtime();
                            final SearchFileResults.SearchFileResult file = new SearchFileResults.SearchFileResult(
                                    path,
                                    excerpts,
                                    document.tags(),
                                    modified,
                                    document.info().size());

                            files.add(file);

                            // Check if the requested number of results is reached
                            if (files.size() == results) {
                                break search;
                            }
                        }
                    }
                }

                // Not yet enought files found, try another search page
                page++;

            } catch (Exception e) {
                logger.errorf(e, "Failed to execute fulltext search: %s", e.getMessage());
                break search;
            }
        }
        return new SearchFileResults(query, files);
    }

    /**
     * Result of a query for comments attached to a file, containing the original
     * file path and a list of comments with metadata.
     * 
     * @param filePath the path of the file for which the comments were queried
     * @param comments the list of comments attached to the file
     */
    @RegisterForReflection
    public record FileComments(String filePath, List<Comment> comments) {

    }

    @Tool(name = TOOL_GET_FILE_COMMENTS, title = "Get all comments of a file", structuredContent = true, description = "Get all comments users attachted to a file", annotations = @Tool.Annotations(title = "Get file comments", destructiveHint = false, readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    @MCPAudit
    public FileComments getFileComments(
            @ToolArg(name = "filePath", description = "The path of the file to get the comments from. For example, '/Documents/file.txt'. Only the latest file revision is supported") String filePath) {
        tools.assertUserLoggedIn();
        assertFilePathValid(filePath);
        try {
            final NextcloudFile file = readFileByPathWithRevision(filePath, true);
            final List<Comment> comments = this.commentService.getCommentsOfFile(file);
            return new FileComments(filePath, comments);
        } catch (IOException e) {
            logger.errorf(e, "File '%s' could not be read", filePath);
            throw new ToolCallException(String.format("File '%s' could not be read", filePath));
        }
    }

    @Tool(name = TOOL_ADD_FILE_COMMENT, title = "Add comment to file", description = "Add comment to file", annotations = @Tool.Annotations(title = "Add file comment", destructiveHint = false, readOnlyHint = false, idempotentHint = false, openWorldHint = false))
    @MCPAudit
    public ToolResponse addFileComment(
            @ToolArg(name = "filePath", description = "The path of the file to add a comment to. For example, '/Documents/file.txt'. Only the latest file revision is supported") String filePath,
            @ToolArg(name = "comment", description = "Comment to add to the file") String comment) {
        tools.assertUserLoggedIn();
        assertFilePathValid(filePath);
        try {
            final NextcloudFile file = readFileByPathWithRevision(filePath, true);
            this.commentService.addCommentToFile(file, comment);
            return ToolResponse.success("Comment added successfully");
        } catch (IOException e) {
            logger.errorf(e, "File '%s' could not be commented", filePath);
            return ToolResponse
                    .error(String.format("File '%s' could not be commented", filePath));
        }
    }

    public record FileTags(String filePath, List<String> tags) {

    }

    @Tool(name = TOOL_GET_FILE_TAGS, title = "Get file tags", description = "Get the tags for a file", structuredContent = true, annotations = @Tool.Annotations(title = "Get file tags", destructiveHint = false, readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    @MCPAudit
    public FileTags getFileTags(
            @ToolArg(name = "filePath", description = "The path of the file to get the file tags for. For example, '/Documents/file.txt'. Only the latest file revision is supported") String filePath) {
        assertFilePathValid(filePath);
        tools.assertUserLoggedIn();
        try {
            final NextcloudFile file = readFileByPathWithRevision(filePath, true);
            final List<String> tags = this.tagService.listSystemTagsOfFile(file).stream().map(st -> st.displayName())
                    .toList();
            return new FileTags(filePath, tags);
        } catch (IOException e) {
            logger.errorf(e, "File '%s' could not be read", filePath);
            throw new ToolCallException(String.format("File '%s' could not be read", filePath));
        }
    }

    @MCPAudit
    @Tool(name = TOOL_ADD_FILE_TAGS, title = "Add file tags", description = "Add tags to a file", annotations = @Tool.Annotations(title = "Add file tags", destructiveHint = false, readOnlyHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse addFileTags(
            @ToolArg(name = "filePath", description = "The path of the file to add tags to. For example, '/Documents/file.txt'. Only the latest file revision is supported") String filePath,
            @ToolArg(name = "tags", description = "List of tags to add to the file") Set<String> tags) {
        tools.assertUserLoggedIn();
        assertFilePathValid(filePath);
        try {
            final NextcloudFile file = readFileByPathWithRevision(filePath, true);
            // Complete list of all system tags available
            final List<SystemTag> existingTags = new ArrayList<>(this.tagService.listSystemTags());
            // List of tags already attached to the file
            final List<SystemTag> fileTags = this.tagService.listSystemTagsOfFile(file);

            for (String tag : tags) {
                // Search for the global system tag matching the tag name
                SystemTag systemTag = existingTags.stream().filter(st -> st.displayName().equals(tag)).findFirst()
                        .orElse(null);

                // First check if all tags exist, if not create them
                if (systemTag == null) {
                    systemTag = this.tagService.addSystemTag(tag, true, true, true);
                    existingTags.add(systemTag);
                }

                // Then check the tags already exist on the file, if not add them
                if (!fileTags.stream().anyMatch(st -> st.displayName().equals(tag))) {
                    this.tagService.addTagToFile(file, systemTag);
                }
            }
            return ToolResponse.success("Tags added successfully");
        } catch (IOException e) {
            logger.errorf(e, "File '%s' could not be tagged", filePath);
            return ToolResponse
                    .error(String.format("File '%s' could not be tagged", filePath));
        }
    }

    @MCPAudit
    @Tool(name = TOOL_REMOVE_FILE_TAGS, title = "Remove file tags", description = "Remove tags from a file", annotations = @Tool.Annotations(title = "Remove file tags", destructiveHint = false, readOnlyHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse removeFileTags(
            @ToolArg(name = "filePath", description = "The path of the file to remove tags from. For example, '/Documents/file.txt'. Only the latest file revision is supported") String filePath,
            @ToolArg(name = "tags", description = "List of tags to remove from the file") Set<String> tags) {
        tools.assertUserLoggedIn();
        assertFilePathValid(filePath);
        try {
            final NextcloudFile file = readFileByPathWithRevision(filePath, true);
            // List of tags already attached to the file
            final List<SystemTag> fileTags = this.tagService.listSystemTagsOfFile(file);

            for (String tag : tags) {
                // Search for the global system tag matching the tag name
                SystemTag systemTag = fileTags.stream().filter(st -> st.displayName().equals(tag)).findFirst()
                        .orElse(null);

                if (systemTag != null) {
                    this.tagService.removeTagFromFile(file, systemTag);
                }
            }
            return ToolResponse.success("Tags removed successfully");
        } catch (IOException e) {
            logger.errorf(e, "File '%s' could not be tagged", filePath);
            return ToolResponse
                    .error(String.format("File '%s' could not be tagged", filePath));
        }
    }

    @RegisterForReflection
    public record SearchinFileResult(String filePath, String query, List<SearchMatch> matches) {
        public record SearchMatch(double score, String text) {
        }
    }

    @Tool(name = TOOL_SEARCH_FILE_NAME, title = "Search within file", description = "Uses a local embedding store to do a semantic search on the file (only available for text files!)", structuredContent = true, annotations = @Tool.Annotations(title = "Create file diff", destructiveHint = false, readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    @MCPAudit
    public SearchinFileResult searchInFile(
            @ToolArg(name = "filePath", description = "The path of the file to get the content from. For example, '/Documents/file.txt'. A revision date can be specified by appending '@' followed by the timestamp. '@latest' can be used to get the latest revision.") String filePath,
            @ToolArg(name = "query", description = "Search query to perform on the embeddings of the file content") String query,
            @ToolArg(name = "results", defaultValue = "20", description = "Maximum number of results expected. Defaults to 20") Integer results,
            @ToolArg(name = "threshold", defaultValue = "0.6", description = "Threshold for the score (from 0.0 to 1.0) to ensure only relevant results are transmited. Defaults to 0.6") Double threshold) {

        try {
            tools.assertUserLoggedIn();
            assertFilePathValid(filePath);
            final NextcloudFile file = this.readFileByPathWithRevision(filePath, false);

            final InMemoryEmbeddingStore<TextSegment> embeddingStore = embeddingService
                    .createInMemoryEmbeddingStoreForNextcloudFile(file);

            final EmbeddingSearchResult<TextSegment> relevant = embeddingService
                    .searchEmbeddingStore(
                            embeddingStore,
                            query, threshold, results);

            final List<SearchMatch> matches = relevant.matches().stream()
                    .map((em) -> new SearchMatch(em.score(), em.embedded().text()))
                    .toList();

            return new SearchinFileResult(filePath, query, matches);
        } catch (IOException e) {
            logger.errorf(e, "File '%s' could not be indexed", filePath);
            throw new ToolCallException(String.format("File '%s' could not be indexed", filePath));
        }
    }
}
