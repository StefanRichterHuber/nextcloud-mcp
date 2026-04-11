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

import org.apache.tika.parser.txt.CharsetDetector;
import org.apache.tika.parser.txt.CharsetMatch;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import com.github.difflib.patch.PatchFailedException;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.EmbeddingService;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.NextcloudService;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.UserRepository;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.FulltextSearchQuery;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.FulltextSearchResult;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.NextCloudFile;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.NextcloudUserCredentials;
import io.quarkiverse.mcp.server.BlobResourceContents;
import io.quarkiverse.mcp.server.Content;
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
import io.quarkus.tika.TikaParser;
import jakarta.activation.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MCP tools for Nextcloud file operations.
 *
 * <p>This bean exposes the core file-management capabilities of the Nextcloud MCP server as
 * individual MCP tools. All tools require the calling user to have completed the Nextcloud
 * login flow first (enforced via {@link #assertUserLoggedIn()}).
 *
 * <p>The tools are designed around a collaborative editing workflow:
 * <ol>
 *   <li>Browse and read files with {@code list-files} and {@code get-file-content}.</li>
 *   <li>Inspect change history with {@code get-file-revisions}.</li>
 *   <li>Compute a human-reviewable diff between any two revisions with
 *       {@code create-file-diff}.</li>
 *   <li>Apply an approved patch back to the latest revision with
 *       {@code apply-file-patch}, which causes Nextcloud to create a new revision
 *       automatically.</li>
 * </ol>
 *
 * <p>Visibility of files is governed by {@link #isVisibleFile(NextCloudFile)}: only files
 * whose MIME type appears in {@link #visibleContentTypes} are surfaced to the LLM. Per-user
 * file-pattern and content-type filtering ({@link UserRepository.UserAccessConfig}) is
 * partially implemented; see inline TODOs for the remaining work.
 */
@ApplicationScoped
public class FilesMCP {
    public static final String TOOL_SEARCH_FILES_NAME = "search-files";
    public static final String TOOL_SEARCH_FILE_NAME = "search-in-file";
    public static final String TOOL_CREATE_FILE_DIFF_NAME = "create-file-diff";
    public static final String TOOL_DELETE_FILE_NAME = "delete-file";
    public static final String TOOL_WRITE_FILE_NAME = "write-file";
    public static final String TOOL_APPLY_FILE_PATCH_NAME = "apply-file-patch";
    public static final String TOOL_LIST_FILES_NAME = "list-files";
    private static final String TOOL_LIST_FILES_DESCRIPTION = "Lists files in the user's Nextcloud account. User must be logged in to use this tool.";

    @Inject
    UserRepository userRepository;

    @Inject
    ResourceManager resourceManager;

    @Inject
    NextcloudService nextcloudService;

    @Inject
    EmbeddingService embeddingService;

    @Inject
    TikaParser parser;

    @Inject
    Logger logger;

    @Inject
    ObjectMapper om;

    // TODO make configurable per user or through some ui or config file, e.g. to
    // also show certain file types that are currently hidden
    private List<String> visibleContentTypes = List.of("text/", "application/json", "application/xml",
            "application/javascript",
            "application/xhtml+xml");

    /**
     * Registers a Nextcloud file as a named MCP resource so that MCP clients can
     * fetch it by URI later. If a resource with the same ID already exists it is
     * returned unchanged (idempotent).
     *
     * <p>The resource ID is {@code <url>@<modifiedMillis>}, which encodes both the
     * file path and the exact revision timestamp so that different revisions of the
     * same file map to distinct resource URIs.
     *
     * @param url  the Nextcloud WebDAV path of the file.
     * @param file the file metadata and data source.
     * @return the {@link ResourceInfo} for the registered (or already existing) resource.
     */
    @SuppressWarnings("unused")
    private ResourceInfo registerNextcloudFileResource(String url, NextCloudFile file) {

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
     * Reads a Nextcloud file and returns it as a typed MCP {@link ResourceResponse}.
     *
     * <p>Text-like MIME types (starting with {@code text/}, or matching
     * {@code application/json}, {@code application/xml}, {@code application/javascript},
     * {@code application/xhtml+xml}) are returned as a {@link TextResourceContents} with
     * charset auto-detected via Tika. All other types are Base64-encoded and returned as
     * a {@link BlobResourceContents}.
     *
     * @param url  the Nextcloud WebDAV path used as the resource URI.
     * @param file the file metadata and data source.
     * @return a resource response containing either the text or the encoded binary content.
     * @throws ToolCallException if the file content cannot be read.
     */
    private ResourceResponse getNextcloudFileResource(String url, NextCloudFile file) {
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
     * <p>The following keys are populated when present on the file:
     * <ul>
     *   <li>{@code nextcloud/fileId} — the Nextcloud internal file ID.</li>
     *   <li>{@code nextcloud/modified} — last-modified time as a Unix timestamp in
     *       milliseconds.</li>
     *   <li>{@code nextcloud/contentType} — the MIME type reported by WebDAV.</li>
     *   <li>{@code nextcloud/size} — the file size in bytes.</li>
     * </ul>
     *
     * @param file the Nextcloud file whose metadata should be collected.
     * @return a map of {@link MetaKey} to metadata values, never {@code null}.
     */
    private Map<MetaKey, Object> getNextcloudFileMetadata(NextCloudFile file) {
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
     * <p>A file is considered visible when its MIME type starts with one of the prefixes
     * in {@link #visibleContentTypes}. Files without a data source or without a declared
     * content type are always hidden.
     *
     * <p><b>Known limitations:</b> per-user file-pattern filtering (hidden patterns and
     * visible-pattern allowlists from {@link UserRepository.UserAccessConfig}) is not yet
     * applied here. The relevant logic is commented out pending a fix for revision paths,
     * which carry only a file ID rather than a human-readable name.
     *
     * @param file the Nextcloud file to evaluate.
     * @return {@code true} if the file should be shown to the LLM, {@code false} otherwise.
     */
    private boolean isVisibleFile(NextCloudFile file) {
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
     * <p>This overload operates on a plain file path string rather than a full
     * {@link NextCloudFile} object (search results do not carry a data source).
     * Per-user file-pattern filtering is not yet implemented here.
     *
     * @param file the Nextcloud file path to evaluate (e.g. {@code /Documents/notes.md}).
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
     * Asserts that the user is logged in with Nextcloud credentials. If not, throws
     * a ToolCallException with instructions on how to log in.
     */
    private void assertUserLoggedIn() {
        Optional<NextcloudUserCredentials> credentials = userRepository.getCredentialsForCurrentUser();
        if (!credentials.isPresent()) {
            throw new ToolCallException(
                    "User is not logged in with Nextcloud credentials. Use tool '" + LoginMCP.TOOL_INITIATE_LOGIN_NAME
                            + "' to start the login flow.");
        }
    }

    /**
     * Creates a {@link TextContent} item for a single Nextcloud file.
     *
     * <p>The text value is set to the resource ID ({@code <url>@<modifiedMillis>}) rather
     * than the file's actual content, because Claude.ai does not yet render embedded
     * resource links in tool responses. The resource ID is sufficient for the LLM to
     * request the content via a subsequent {@code get-file-content} call.
     *
     * @param url  the Nextcloud WebDAV path of the file.
     * @param file the file metadata.
     * @return a {@link TextContent} carrying the resource ID and Nextcloud metadata.
     */
    private Content textContentFrom(String url, NextCloudFile file) {
        final String resourceId = String.format("%s@%d", url, file.modified().getTime());
        return new TextContent(resourceId, getNextcloudFileMetadata(file), null);
    }

    /**
     * Creates a list of {@link TextContent} items for a collection of Nextcloud files,
     * excluding directories and files that fail the {@link #isVisibleFile(NextCloudFile)}
     * check.
     *
     * @param url   the Nextcloud WebDAV path prefix (passed through to
     *              {@link #textContentFrom(String, NextCloudFile)}).
     * @param files the files to convert; directories (paths ending with {@code /}) are
     *              skipped automatically.
     * @return a list of visible file content items, never {@code null}.
     */
    private List<Content> textContentFrom(String url, Iterable<NextCloudFile> files) {
        List<Content> resourceLinks = new ArrayList<>();
        for (NextCloudFile file : files) {
            if (!file.path().endsWith("/") && isVisibleFile(file)) { // skip directories
                resourceLinks.add(textContentFrom(url, file));
            }
        }
        return resourceLinks;
    }

    @Tool(name = TOOL_LIST_FILES_NAME, description = TOOL_LIST_FILES_DESCRIPTION, annotations = @Tool.Annotations(title = "List files", destructiveHint = false, readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse listFiles(
            @ToolArg(name = "path", description = "The path to list files from. For example, '/' for the root directory or '/Documents' for the Documents folder.") String path) {
        assertUserLoggedIn();

        try {
            final List<NextCloudFile> files = nextcloudService.listFiles(path, -1);
            List<Content> content = new ArrayList<>();
            for (NextCloudFile file : files) {
                if (!file.path().endsWith("/") && isVisibleFile(file)) { // skip directories
                    content.add(textContentFrom(file.path(), file));
                }
            }

            return ToolResponse.success(content);
        } catch (IOException e) {
            return ToolResponse.error("Failed to list files: " + e.getMessage());
        }
    }

    @Tool(name = "get-file-revisions", description = "Gets the revisions of a file in the user's Nextcloud account. User must be logged in to use this tool.", annotations = @Tool.Annotations(title = "Get file revisions", destructiveHint = false, readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse getFileRevisions(
            @ToolArg(name = "filePath", description = "The path of the file to get revisions from. For example, '/Documents/file.txt'.") String filePath) {
        assertUserLoggedIn();

        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.error("File path cannot be empty");
        }
        if (filePath.contains("@")) {
            String[] parts = filePath.split("@");
            filePath = parts[0];
        }

        final List<NextCloudFile> revisions = nextcloudService.listFileRevisions(filePath);
        if (revisions == null || revisions.isEmpty()) {
            return ToolResponse.error(String.format("No revisions found for file '%s'", filePath));
        }
        return ToolResponse.success(textContentFrom(filePath, revisions));
    }

    @Tool(name = "get-file-content", description = "Gets the content of a file as text or blob resource. User must be logged in to use this tool.", annotations = @Tool.Annotations(title = "Get file content", destructiveHint = false, readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse getFileContent(
            @ToolArg(name = "filePath", description = "The path of the file to get the content from. For example, '/Documents/file.txt'. A revision date can be specified by appending '@' followed by the timestamp. '@latest' can be used to get the latest revision.") String filePath) {
        assertUserLoggedIn();

        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.error("File path cannot be empty");
        }
        Date revision = null;
        if (filePath.contains("@")) {
            String[] parts = filePath.split("@");
            filePath = parts[0];
            if (parts[1].equals("latest")) {
                revision = null; // latest revision
            } else {
                try {
                    revision = new Date(Long.parseLong(parts[1]));
                } catch (NumberFormatException e) {
                    return ToolResponse.error("Invalid revision format. Use '@latest' or '@<timestamp>'.");
                }
            }
        }

        final NextCloudFile file = nextcloudService.getFileByModifyDate(filePath, revision);
        if (file == null || !isVisibleFile(file)) {
            return ToolResponse
                    .error(String.format("File '%s' with modified date %d not found", filePath,
                            revision != null ? revision.getTime() : 0));
        }

        // Read file
        try (InputStream is = file.dataSource().getInputStream()) {
            byte[] content = is.readAllBytes();
            Charset cs = detectCharset(content).orElse(StandardCharsets.UTF_8);
            String text = new String(content, cs);

            return ToolResponse.success(new TextContent(text));

        } catch (IOException e) {
            throw new ToolCallException(e);
        }

        // return ToolResponse.success(contentFrom(filePath, file));
    }

    /**
     * Serialises a list of diff deltas into a unified (git-style) patch string.
     *
     * <p>The output follows the standard unified diff format:
     * <pre>
     * --- fileName1
     * +++ fileName2
     * @@ -&lt;src-pos&gt;,&lt;src-size&gt; +&lt;tgt-pos&gt;,&lt;tgt-size&gt; @@
     * -removed line
     * +added line
     * </pre>
     *
     * @param deltas    the list of change deltas produced by {@link com.github.difflib.DiffUtils}.
     * @param fileName1 label for the original file (used in the {@code ---} header line).
     * @param fileName2 label for the modified file (used in the {@code +++} header line).
     * @return the complete unified diff as a string.
     */
    private static String deltasToGitPatch(List<AbstractDelta<String>> deltas, String fileName1, String fileName2) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- " + fileName1 + "\n");
        sb.append("+++ " + fileName2 + "\n");

        for (AbstractDelta<String> delta : deltas) {
            sb.append("@@ -" + (delta.getSource().getPosition() + 1) + "," + delta.getSource().size() + " +"
                    + (delta.getTarget().getPosition() + 1) + "," + delta.getTarget().size() + " @@\n");
            for (String line : delta.getSource().getLines()) {
                sb.append("-" + line + "\n");
            }
            for (String line : delta.getTarget().getLines()) {
                sb.append("+" + line + "\n");
            }
        }

        return sb.toString();
    }

    @Tool(name = TOOL_CREATE_FILE_DIFF_NAME, description = "Creates a diff between two files in the git patch format. User must be logged in to use this tool.", annotations = @Tool.Annotations(title = "Create file diff", destructiveHint = false, readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse createFileDiff(
            @ToolArg(name = "firstFile", description = "The path of the first file to create the diff from. For example, '/Documents/file.txt'. A revision date can be specified by appending '@' followed by the timestamp. '@latest' can be used to get the latest revision.") String firstFile,
            @ToolArg(name = "secondFile", description = "The path of the second file to create the diff from. For example, '/Documents/file.txt'. A revision date can be specified by appending '@' followed by the timestamp. '@latest' can be used to get the latest revision.") String secondFile) {
        assertUserLoggedIn();

        if (firstFile == null || firstFile.isBlank()) {
            return ToolResponse.error("File path of first file cannot be empty");
        }
        if (secondFile == null || secondFile.isBlank()) {
            return ToolResponse.error("File path of second file cannot be empty");
        }

        Date firstRevision = null;
        if (firstFile.contains("@")) {
            String[] parts = firstFile.split("@");
            firstFile = parts[0];
            if (parts[1].equals("latest")) {
                firstRevision = null; // latest revision
            } else {
                try {
                    firstRevision = new Date(Long.parseLong(parts[1]));
                } catch (NumberFormatException e) {
                    return ToolResponse
                            .error("Invalid revision format for first file. Use '@latest' or '@<timestamp>'.");
                }
            }
        }

        Date secondRevision = null;
        if (secondFile.contains("@")) {
            String[] parts = secondFile.split("@");
            secondFile = parts[0];
            if (parts[1].equals("latest")) {
                secondRevision = null; // latest revision
            } else {
                try {
                    secondRevision = new Date(Long.parseLong(parts[1]));
                } catch (NumberFormatException e) {
                    return ToolResponse
                            .error("Invalid revision format for second file. Use '@latest' or '@<timestamp>'.");
                }
            }
        }

        final NextCloudFile firstFileContent = nextcloudService.getFileByModifyDate(firstFile, firstRevision);
        final NextCloudFile secondFileContent = nextcloudService.getFileByModifyDate(secondFile, secondRevision);

        if (firstFileContent == null || !isVisibleFile(firstFileContent)) {
            return ToolResponse.error(String.format("First file '%s' with modified date %d not found", firstFile,
                    firstRevision != null ? firstRevision.getTime() : 0));
        }
        if (secondFileContent == null || !isVisibleFile(secondFileContent)) {
            return ToolResponse.error(String.format("Second file '%s' with modified date %d not found", secondFile,
                    secondRevision != null ? secondRevision.getTime() : 0));
        }

        Patch<String> patch = nextcloudService.getContentPatch(firstFileContent, secondFileContent);
        final String gitPatch = deltasToGitPatch(patch.getDeltas(),
                String.format("%s@%d", firstFile, firstRevision != null ? firstRevision.getTime() : 0),
                String.format("%s@%d", secondFile, secondRevision != null ? secondRevision.getTime() : 0));

        return ToolResponse.success(gitPatch);
    }

    @Tool(name = TOOL_DELETE_FILE_NAME, title = "Deletes a file", description = "Deletes the latest revision of a file", annotations = @Tool.Annotations(title = "Delete file", destructiveHint = true, readOnlyHint = false, idempotentHint = false, openWorldHint = false))
    public ToolResponse deleteFile(
            @ToolArg(name = "filePath", description = "The path of the file to delete. For example, '/Documents/file.txt'. Any revision date (using '@latest' or '@<timestamp>') added to the filename will be ignored and only the latest revision will be deleted!") String filePath) {

        assertUserLoggedIn();
        if (filePath.contains("@")) {
            String[] parts = filePath.split("@");
            filePath = parts[0];
        }
        final NextCloudFile org = nextcloudService.getFile(filePath);
        if (org == null) {
            return ToolResponse
                    .error(String.format("File '%s' can not be deleted", filePath));
        }
        if (!isVisibleFile(org)) {
            return ToolResponse
                    .error(String.format("File '%s' can not be deleted", filePath));
        }
        try {
            nextcloudService.deleteFile(filePath, org.etag(), (String) null);
        } catch (IOException e) {
            logger.errorf(e, "File '%s' can not be deleted", filePath);
            return ToolResponse
                    .error(String.format("File '%s' can not be deleted", filePath));
        }
        return ToolResponse.success("Successfully deleted file");
    }

    @Tool(name = TOOL_WRITE_FILE_NAME, title = "Writes a text file", description = "Overwrites / Creates the latest revision of a textfile", annotations = @Tool.Annotations(title = "Write file", destructiveHint = true, readOnlyHint = false, idempotentHint = false, openWorldHint = false))
    public ToolResponse writeFile(
            @ToolArg(name = "filePath", description = "The path of the file to write. For example, '/Documents/file.txt'. Any revision date (using '@latest' or '@<timestamp>') added to the filename will be ignored and only the latest revision will be written!") String filePath,
            @ToolArg(name = "content", description = "New file content") String content,
            @ToolArg(name = "content-type", description = "Content type of the new document. e.g text/markdown") String contentType,
            @ToolArg(name = "overwrite", description = "Overwrite existing file. Defaults to false", defaultValue = "false") boolean overwrite,
            @ToolArg(name = "charset", description = "Charset for the file content. Defaults to UTF-8", defaultValue = "UTF-8") String charset) {
        assertUserLoggedIn();
        if (filePath.contains("@")) {
            String[] parts = filePath.split("@");
            filePath = parts[0];
        }
        try {
            final NextCloudFile org = nextcloudService.getFile(filePath);
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
            final NextCloudFile newFile = nextcloudService.getFile(filePath);
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
    public ToolResponse applyPatch(
            @ToolArg(name = "filePath", description = "The path of the file to apply the patch. For example, '/Documents/file.txt'. Any revision date (using '@latest' or '@<timestamp>') added to the filename will be ignored and only the latest revision will be patche!") String filePath,
            @ToolArg(name = "patch", description = "Git-style patch to apply") String patch,
            @ToolArg(name = "fuziness", description = "Fuzz factor for the patch. Roughly how many lines the patch definition can be off from the actual source", defaultValue = "4") int fuzziness) {
        assertUserLoggedIn();

        if (filePath.contains("@")) {
            String[] parts = filePath.split("@");
            filePath = parts[0];
        }

        final NextCloudFile file = nextcloudService.getFileByModifyDate(filePath, null);
        if (file == null || !isVisibleFile(file)) {
            return ToolResponse
                    .error(String.format("File '%s' not found", filePath));
        }

        Patch<String> parsedPatch = null;
        if (patch != null && !patch.isBlank()) {
            final List<String> patchContent = Arrays.asList(patch.split("\n"));
            parsedPatch = UnifiedDiffUtils.parseUnifiedDiff(patchContent);
        } else {
            parsedPatch = new Patch<String>();
        }

        try {
            nextcloudService.applyContentPatch(file, parsedPatch, fuzziness);
            // Read back file to get latest revision
            final NextCloudFile patchedFile = this.nextcloudService.getFileByModifyDate(filePath, null);
            final String resourceId = String.format("%s@%d", filePath, patchedFile.modified().getTime());
            return ToolResponse.success("Successfully patched file. New revision: " + resourceId);

        } catch (PatchFailedException e) {
            throw new ToolCallException("Failed to apply patch to file " + filePath + ":" + e, e);
        } catch (IOException e) {
            throw new ToolCallException("Failed to read / write file " + filePath + ":" + e, e);
        }
    }

    @Tool(name = TOOL_SEARCH_FILES_NAME, title = "Fulltext search on all files", description = "Performs full text search on all files", annotations = @Tool.Annotations(title = "Search files", destructiveHint = false, readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse searchFiles(
            @ToolArg(name = "query", description = "Fulltext search query") String query,
            @ToolArg(name = "results", defaultValue = "20", description = "Maximum number of results to return. Defaults to '20'") Integer results) {

        final int pageSize = 20;
        int page = 1;
        final List<Map<String, Object>> files = new ArrayList<>(results);
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
                            final Map<String, Object> file = Map.of(
                                    "modified", modified,
                                    "file", path,
                                    "excerpts", excerpts,
                                    "tags", document.tags(),
                                    "contentSize", document.info().size());

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
                logger.errorf(e, "Failed to execute fulltext search: %s", e);
                break search;
            }
        }

        List<TextContent> tc = files.stream()
                .map(m -> {
                    try {
                        return this.om.writeValueAsString(m);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                })
                .map(v -> new TextContent(v))
                .toList();
        return ToolResponse.success(tc);

    }

    @Tool(name = TOOL_SEARCH_FILE_NAME, title = "Search within file", description = "Uses a local embedding store to do a semantic search on the file (only available for text files!)", annotations = @Tool.Annotations(title = "Create file diff", destructiveHint = false, readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ToolResponse searchInFile(
            @ToolArg(name = "filePath", description = "The path of the file to get the content from. For example, '/Documents/file.txt'. A revision date can be specified by appending '@' followed by the timestamp. '@latest' can be used to get the latest revision.") String filePath,
            @ToolArg(name = "query", description = "Search query to perform on the embeddings of the file content") String query,
            @ToolArg(name = "results", defaultValue = "20", description = "Maximum number of results expected. Defaults to 20") Integer results,
            @ToolArg(name = "threshold", defaultValue = "0.6", description = "Threshold for the score (from 0.0 to 1.0) to ensure only relevant results are transmited. Defaults to 0.6") Double threshold) {

        assertUserLoggedIn();
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.error("File path cannot be empty");
        }
        Date revision = null;
        if (filePath.contains("@")) {
            String[] parts = filePath.split("@");
            filePath = parts[0];
            if (parts[1].equals("latest")) {
                revision = null; // latest revision
            } else {
                try {
                    revision = new Date(Long.parseLong(parts[1]));
                } catch (NumberFormatException e) {
                    return ToolResponse.error("Invalid revision format. Use '@latest' or '@<timestamp>'.");
                }
            }
        }

        final NextCloudFile file = nextcloudService.getFileByModifyDate(filePath, revision);
        if (file == null || !isVisibleFile(file)) {
            return ToolResponse
                    .error(String.format("File '%s' with modified date %d not found", filePath,
                            revision != null ? revision.getTime() : 0));
        }

        try {
            final InMemoryEmbeddingStore<TextSegment> embeddingStore = embeddingService
                    .createInMemoryEmbeddingStoreForNextcloudFile(file);

            final EmbeddingSearchResult<TextSegment> relevant = embeddingService
                    .searchEmbeddingStore(
                            embeddingStore,
                            query, threshold, results);

            final List<TextContent> matches = relevant.matches().stream()
                    .map(this::fromEmbeddingMatch)
                    .toList();

            return ToolResponse.success(matches);
        } catch (IOException e) {
            logger.errorf(e, "File '%s' with modified date %d could not be indexed", filePath,
                    revision != null ? revision.getTime() : 0);
            return ToolResponse
                    .error(String.format("File '%s' with modified date %d could not be indexed", filePath,
                            revision != null ? revision.getTime() : 0));
        }

    }

    /**
     * Converts a single embedding match into a JSON-encoded {@link TextContent} item
     * suitable for returning in a tool response.
     *
     * <p>The JSON object contains two fields:
     * <ul>
     *   <li>{@code score} — the cosine-similarity score (0.0–1.0) between the query
     *       embedding and the matched segment.</li>
     *   <li>{@code text} — the raw text of the matched segment from the file.</li>
     * </ul>
     *
     * @param em the embedding match to convert.
     * @return a {@link TextContent} containing the serialised JSON.
     * @throws RuntimeException if the JSON serialisation fails.
     */
    private TextContent fromEmbeddingMatch(EmbeddingMatch<TextSegment> em) {
        Map<String, Object> result = Map.of(
                "score", em.score(),
                "text", em.embedded().text());

        try {
            return new TextContent(this.om.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
