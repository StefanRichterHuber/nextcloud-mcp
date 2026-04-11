package io.github.stefanrichterhuber.nextcloudmcp.nextcloud;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.namespace.QName;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import org.apache.commons.io.IOUtils;
import org.apache.tika.parser.txt.CharsetDetector;
import org.apache.tika.parser.txt.CharsetMatch;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.jboss.logging.Logger;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Patch;
import com.github.difflib.patch.PatchFailedException;
import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.github.sardine.model.Multistatus;
import com.github.sardine.report.SardineReport;

import io.github.stefanrichterhuber.nextcloudmcp.auth.NextcloudAuthProvider;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.NextcloudRestClient;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.ByteArrayDataSource;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.FileQueryResult;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.FulltextSearchQuery;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.FulltextSearchResult;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.NextCloudFile;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.SardineDataSource;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search.Condition;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search.FileSelector;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search.FileSelector.FilterRule;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search.Order;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search.Property;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search.Query;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.tools.LineSeparatorDetector;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.tools.LineSeparatorDetector.LineSeparator;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.activation.DataSource;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;

@ApplicationScoped
public class NextcloudService {
    @Inject
    Logger logger;

    @Inject
    Sardine sardine;

    @Inject
    NextcloudAuthProvider authProvider;

    @Inject
    @CacheName("nextcloud-files")
    Cache filesCache;

    private record FilesByModificationDateKey(String path, Date modificationDate) {
    }

    public class NextCloudFileLock implements AutoCloseable {
        private final String token;
        private final String url;

        private NextCloudFileLock(String url, String token) {
            this.token = token;
            this.url = url;

        }

        @Override
        public void close() {
            try {
                NextcloudService.this.sardine.unlock(url, token);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public String token() {
            return token;
        }

        public String url() {
            return url;
        }

        public NextCloudFile getFile() {
            return NextcloudService.this.getFile(url());
        }

    }

    /**
     * Get the sanitized WebDav file path for the given user and file
     * 
     * @param path Relative file path
     * @return
     */
    private String getWebDavFilePath(String path) {
        final String user = this.getCurrentUser();

        if (path == null || path.isBlank()) {
            final String target = String.format("%s/remote.php/dav/files/%s", authProvider.getServer(), user);
            return target;
        } else if (path.startsWith(String.format("%s/remote.php/dav/files/%s", authProvider.getServer(), user))) {
            return path;
        } else if (path.startsWith(String.format("%s/remote.php/dav/versions/", authProvider.getServer()))) {
            return path;
        } else {
            // Replace leading /
            path = path.startsWith("/") ? path.substring(1) : path;
            path = path.replace(" ", "%20");
            final String target = String.format("%s/remote.php/dav/files/%s/%s", authProvider.getServer(), user, path);
            return target;
        }
    }

    /**
     * Returns the current user - necessary to build the correct path for all
     * further operations
     * 
     * @return User name
     */
    private String getCurrentUser() {
        return authProvider.getUser();
    }

    /**
     * Downloads the file
     * 
     * @param path Relative file path
     * @return {@link NextCloudFile} found, or null if file does not exists
     * @throws IOException
     */
    public NextCloudFile getFile(@Nullable String path) {
        final String target = getWebDavFilePath(path);
        final List<NextCloudFile> results = getFileByInternalPath(target);

        final NextCloudFile result = results.isEmpty() ? null : results.get(0);
        return result;
    }

    /**
     * Returns a file by its <br>
     * internal</br>
     * path (could be the file itself, the file by its id or file version(s))
     * 
     * @param target Internal target path
     * @return List of files found
     * @see https://docs.nextcloud.com/server/latest/developer_manual/client_apis/WebDAV/basic.html
     */
    private List<NextCloudFile> getFileByInternalPath(@Nonnull String target) {
        try {
            final Set<QName> properties = Set.of( //
                    new QName("http://owncloud.org/ns", "fileid", "oc"), //
                    new QName("DAV:", "getetag", "d"), //
                    new QName("DAV:", "getlastmodified", "d"), //
                    new QName("DAV:", "getcontentlength", "d"), //
                    new QName("DAV:", "getcontenttype", "d") //
            );
            final List<DavResource> propfind = this.sardine.propfind(target, 1, properties);
            final List<NextCloudFile> result = propfind.stream().map(this::davResourceToNextCloudFile).toList();
            return result;
        } catch (IOException e) {
            logger.warnf(e, "Failed to list file(s) '%s' of user '%s'", target, this.getCurrentUser());
        }
        return Collections.emptyList();
    }

    /**
     * Utility method to convert da DavResource to a NextCloudFile
     * 
     * @param davResource
     * @return
     */
    private NextCloudFile davResourceToNextCloudFile(@Nonnull DavResource davResource) {
        if (davResource != null) {
            final String user = this.getCurrentUser();
            final String etag = davResource.getEtag();
            final String contentType = davResource.getContentType();
            final Date modified = davResource.getModified();
            final Long contentLength = davResource.getContentLength();
            final Integer fileId = Optional.ofNullable(davResource.getCustomProps().get("fileid"))
                    .filter(str -> !str.isBlank())
                    .map(Integer::parseInt).orElse(null);
            final String path = String.format("%s%s", authProvider.getServer(), davResource.getHref().toString());
            final DataSource ds = new SardineDataSource(this.sardine, path, contentType);

            final String filePath = path.replace(getWebDavFilePath(null) + "/", "");
            return new NextCloudFile(fileId, user, filePath, etag, modified, ds, contentLength);
        } else {
            return null;
        }
    }

    /**
     * Returns all reviions of the file with the given path
     * 
     * @param path Path of the file
     * @return List of revisions as NextCloudFile
     */
    public List<NextCloudFile> listFileRevisions(@Nonnull String path) {
        final NextCloudFile latest = getFile(path);
        if (latest != null) {
            // For some strange reasone the latest file revision could not be downloaded
            // from the list of revisions -> download by filename
            List<NextCloudFile> result = listFileRevisions(latest.fileId());
            final Date latestRev = result.stream().filter(f -> f.modified() != null).map(f -> f.modified())
                    .max(Date::compareTo)
                    .orElse(null);
            if (latestRev != null) {
                return result.stream().map(file -> {
                    if (Objects.equals(file.modified(), latestRev)) {
                        // Replace with latest file revision to ensure that the content is available for
                        // the latest revision
                        return latest;
                    } else {
                        return (NextCloudFile) file;
                    }
                }).toList();
            } else {
                return result;
            }
        } else {
            return Collections.emptyList();
        }
    }

    /**
     * Returns all revisions of the file with the given id
     * 
     * @param fileId ID of the file
     * @return List of revisions as NextCloudFile
     */
    private List<NextCloudFile> listFileRevisions(final int fileId) {
        final String user = this.getCurrentUser();
        final String target = String.format("%s/remote.php/dav/versions/%s/versions/%d", authProvider.getServer(), user,
                fileId);

        List<NextCloudFile> result = getFileByInternalPath(target);
        return result;
    }

    /**
     * Gets the content of a file revision (== etag)
     * 
     * @param fileId     ID of the file
     * @param revisionId ID of the reviion
     * @return
     */
    public NextCloudFile getFileRevision(long fileId, @Nonnull String revisionId) {
        final String user = this.getCurrentUser();
        final String target = String.format("%s/remote.php/dav/versions/%s/versions/%d/%s", authProvider.getServer(),
                user,
                fileId, revisionId);
        final List<NextCloudFile> results = getFileByInternalPath(target);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Gets the content of a file revision (== etag)
     * 
     * @param path       Path of the file
     * @param revisionId ID of the reviion. If null, the latest file revision is
     *                   returned
     * @return
     */
    public NextCloudFile getFileRevision(@Nonnull String path, @Nullable String revisionId) {
        final NextCloudFile latest = getFile(path);
        if (revisionId == null || revisionId.isBlank()) {
            return latest;
        }
        if (latest != null) {
            return getFileRevision(latest.fileId(), revisionId);
        } else {
            return null;
        }
    }

    /**
     * Creates a new NextCloudFile with a the file content already downloaded
     * instead of being laizly available
     * 
     * @param file
     * @return
     */
    private NextCloudFile downloadFileImmediately(NextCloudFile file) throws IOException {
        if (file == null) {
            return null;
        }
        String etag = file.etag();
        String contentType = file.dataSource().getContentType();
        Date modDate = file.modified();
        // Really ensure that the content of the file revision we fetched earlier is
        // downloaded
        Map<String, String> headers = new HashMap<>();

        if (file.path().startsWith(String.format("%s/remote.php/dav/versions/", authProvider.getServer()))) {
            // If we download a file version, no need to use etags, because the file content
            // cannot be changed anymore -> no need to use etags to ensure that the content
            // is still the same as when we fetched the metadata

        } else {
            if (etag != null) {
                headers.put("If-Match", etag);
            }
        }
        try (InputStream is = this.sardine.get(getWebDavFilePath(file.path()), headers)) {
            byte[] content = is.readAllBytes();
            ByteArrayDataSource ds = new ByteArrayDataSource(file.path(), contentType, content);
            return new NextCloudFile(file.fileId(), contentType, file.path(), etag, modDate, ds,
                    file.contentLength());
        }
    }

    /**
     * Returns the File revision for a given revision date. Files are cached for
     * better performance
     * 
     * @param path             File path
     * @param modificationDate Modification date
     * @return File found
     */
    @Retry(maxRetries = 4)
    public NextCloudFile getFileByModifyDate(@Nonnull String path, @Nullable Date modificationDate) {
        if (modificationDate == null || modificationDate.getTime() == 0) {
            // Load latest revision
            final NextCloudFile result = this.getFile(path);

            if (result != null) {
                final Date modDate = result.modified();
                final NextCloudFile cachedResult = this.filesCache
                        .get(new FilesByModificationDateKey(path, modDate), k -> {
                            final NextCloudFile src = result;

                            try {
                                NextCloudFile cached = this.downloadFileImmediately(src);
                                return cached;
                            } catch (IOException e) {
                                /*
                                 * Errors could happen if either the file is deleted or updated between
                                 * downloading the metadata and the file content
                                 * handle this gracefully by retry
                                 */
                                throw new RuntimeException(e);
                            }
                        }).await()
                        .indefinitely();
                return cachedResult;
            }

            return result;
        } else {
            final NextCloudFile result = this.filesCache
                    .get(new FilesByModificationDateKey(path, modificationDate), k -> {
                        // Sorted by revision date
                        final List<NextCloudFile> revisions = this.listFileRevisions(path).stream()
                                .filter(rev -> rev.modified() != null)
                                .sorted((r1, r2) -> r1.modified().compareTo(r2.modified())).toList();
                        // Find the revision with the the given modification date
                        NextCloudFile found = revisions.stream().filter(f -> k.modificationDate().equals(f.modified()))
                                .findFirst()
                                .orElse(null);

                        if (found != null) {
                            try {
                                found = this.downloadFileImmediately(found);
                            } catch (IOException e) {
                                /*
                                 * Errors could happen if either the file is deleted or updated between
                                 * downloading the metadata and the file content
                                 * handle this gracefully by retry
                                 */
                                throw new RuntimeException(e);
                            }
                        }
                        return found;
                    }).await()
                    .indefinitely();
            return result;
        }
    }

    /**
     * Return the differences in the file content (for text-files!) between two
     * files
     * 
     * @param f1 First file
     * @param f2 Second file
     * @return Patch found
     */
    public Patch<String> getContentPatch(NextCloudFile f1, NextCloudFile f2) {
        if (Objects.equals(f1, f2) || f1 == null || f2 == null) {
            return DiffUtils.diff(Collections.emptyList(), Collections.emptyList());
        }
        if (Objects.equals(f1.fileId(), f2.fileId()) && Objects.equals(f1.etag(), f2.etag())) {
            return DiffUtils.diff(Collections.emptyList(), Collections.emptyList());
        }

        final DataSource d1 = f1.dataSource();
        final DataSource d2 = f2.dataSource();

        final String contentType1 = d1.getContentType();
        final String contentType2 = d2.getContentType();

        if (contentType1.startsWith("text/") && contentType2.startsWith("text/")) {
            try (InputStream is1 = d1.getInputStream(); InputStream is2 = d2.getInputStream()) {
                final String c1 = IOUtils.toString(is1, StandardCharsets.UTF_8);
                final String c2 = IOUtils.toString(is2, StandardCharsets.UTF_8);

                final List<String> c1Lines = List.of(c1.split("\r?\n|\r"));
                final List<String> c2Lines = List.of(c2.split("\r?\n|\r"));

                final Patch<String> patch = DiffUtils.diff(c1Lines, c2Lines);
                return patch;
            } catch (Exception e) {
                throw new RuntimeException(e);

            }

        } else {
            this.logger.warnf("Tried to create patch for files (1: %s 2: %s) with content type `%s` and `%s`",
                    f1.path(), f2.path(),
                    d1.getContentType(), d2.getContentType());
            return DiffUtils.diff(Collections.emptyList(), Collections.emptyList());
        }
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
            return null;
        }
    }

    /**
     * Utiltiy method to the detect the line seperator of a file
     * 
     * @param content
     * @return
     */
    private String detectLineSeparator(final String content) {
        LineSeparator ls = LineSeparatorDetector.detectDominantLineSeparator(content);
        switch (ls) {
            case UNIX:
                return "\n";
            case WINDOWS:
                return "\r\n";
            case OLD_MAC:
                return "\r";
            case MIXED:
                return "\n";
            case NONE:
                return "\n";
            default:
                return "\n";
        }
    }

    /**
     * Applies the given patch to the given file and uploads the file again
     * 
     * @param file      File to patch
     * @param patch     Patch to apply
     * @param lockToken Optional lock token to update a locked file
     * @param fuzz      Fuzz factor for the patch. Roughly how many
     *                  lines the patch definition can be off from the actual source
     * @throws IOException
     * @throws PatchFailedException
     */
    public void applyContentPatch(NextCloudFile file, Patch<String> patch, int fuzz,
            @Nullable String lockToken) throws IOException, PatchFailedException {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(patch, "patch must not be null");

        final DataSource ds = file.dataSource();
        final String fileName = file.path();
        final String contentType = ds.getContentType();
        final String etag = file.etag();

        if (contentType != null && contentType.startsWith("text/")) {
            try (InputStream is = ds.getInputStream()) {
                final byte[] rawContent = is.readAllBytes();
                final Charset charset = detectCharset(rawContent).orElseGet(() -> {
                    logger.warnf("Unable to detect charset of text file %s -> fall back to UTF-8", fileName);
                    return StandardCharsets.UTF_8;
                });
                final String content = new String(rawContent, charset);
                final String lineSplitter = detectLineSeparator(content);
                final List<String> contentLines = List.of(content.split("\r?\n|\r"));
                final List<String> patchedContentLines = fuzz > 0 ? patch.applyFuzzy(contentLines, fuzz)
                        : patch.applyTo(contentLines);
                // Write back file
                final String patchedContent = patchedContentLines.stream().collect(Collectors.joining(lineSplitter));
                final InputStream ois = new ByteArrayInputStream(patchedContent.getBytes(charset));

                uploadFile(fileName, contentType, ois, etag, lockToken);
            }
        } else {
            throw new PatchFailedException("File " + file.path() + " is not of content type text/*");
        }
    }

    /**
     * Applies the given patch to the given file and uploads the file again
     * 
     * @param file      File to patch
     * @param patch     Patch to apply
     * @param lockToken Optional lock token to update a locked file
     * @param fuzz      Fuzz factor for the patch. Roughly how many
     *                  lines the patch definition can be off from the actual source
     * @throws IOException
     * @throws PatchFailedException
     */
    public void applyContentPatch(NextCloudFile file, Patch<String> patch, int fuzz)
            throws IOException, PatchFailedException {
        applyContentPatch(file, patch, fuzz, (String) null);
    }

    /**
     * Applies the given patch to the given file and uploads the file again
     * 
     * @param file         File to patch
     * @param patch        Patch to apply
     * @param lockOptional lock to update a locked file
     * @param fuzz         Fuzz factor for the patch. Roughly how many
     *                     lines the patch definition can be off from the actual
     *                     source
     * @throws IOException
     * @throws PatchFailedException
     */
    public void applyContentPatch(NextCloudFile file, Patch<String> patch, int fuzz,
            @Nullable NextCloudFileLock lock) throws IOException, PatchFailedException {
        final String lockTocken = lock != null ? lock.token() : null;
        applyContentPatch(file, patch, fuzz, lockTocken);
    }

    /**
     * List all files in the given path
     * 
     * @param path  Relative file path. Null for root dir
     * @param depth List depth. -1 for infinite recursion
     * @return List of Nextcloud files found
     * @throws IOException
     */
    public List<NextCloudFile> listFiles(@Nullable String path, int depth)
            throws IOException {
        final Set<QName> qproperties = Set.of( //
                new QName("http://owncloud.org/ns", "fileid", "oc"), //
                new QName("DAV:", "getetag", "d"), //
                new QName("DAV:", "getlastmodified", "d"), //
                new QName("DAV:", "getcontentlength", "d"), //
                new QName("DAV:", "getcontenttype", "d"), //
                new QName("DAV:", "displayname", "d") //
        );

        final String target = getWebDavFilePath(path);
        final List<DavResource> propfind = this.sardine.propfind(target, depth, qproperties);

        final List<NextCloudFile> result = propfind.stream().map(this::davResourceToNextCloudFile).toList();
        return result;
    }

    /**
     * List all files in the given path with the given selector applied
     * 
     * @param path  Relative file path. Null for root dir
     * @param depth List depth. -1 for infinite recursion
     * @param rules List of rules to apply
     * @return List of Nextcloud files found
     * @throws IOException
     */
    public List<NextCloudFile> listFiles(@Nullable String path, int depth, List<FilterRule> rules) throws IOException {
        if (rules == null || rules.isEmpty()) {
            return listFiles(path, depth);
        }

        FileSelector selector = FileSelector.list(Property.FILE_ID, Property.GET_ETAG, Property.GET_CONTENT_TYPE,
                Property.GET_LAST_MODIFIED, Property.GET_CONTENT_LENGTH);
        for (final FilterRule rule : rules) {
            selector = selector.withFilter(rule.property(), rule.value());
        }
        final FileQueryResult fqr = listFiles(path, depth, selector);
        if (fqr != null) {
            final List<NextCloudFile> result = fqr.getFiles().stream()
                    .map(f -> f.toNextCloudFile(sardine, this.getCurrentUser())).toList();
            return result;
        } else {
            return Collections.emptyList();
        }

    }

    /**
     * List alle files in the given path with the given selector applied
     * 
     * @param path     Relative file path. Null for root dir
     * @param selector Properties to select and additional filter conditions to
     *                 apply
     * @param depth    List depth. -1 for infinite recursion
     * @return FileQueryResult with the result
     * @throws IOException
     */
    public FileQueryResult listFiles(@Nullable String path, int depth, @Nonnull FileSelector selector)
            throws IOException {
        final String target = getWebDavFilePath(path);

        final FileQueryResult result = sardine.report(target, depth, new SardineReport<FileQueryResult>() {

            @Override
            public String toXml() throws IOException {
                return selector.toXML();
            }

            @Override
            public Object toJaxb() {
                return null;
            }

            @Override
            public FileQueryResult fromMultistatus(Multistatus multistatus) {
                return FileQueryResult.of(multistatus);
            }
        });
        return result;
    }

    /**
     * Performs a search operation on the file server
     * 
     * @param query Search query to execute, must not be null
     * @return FileQueryResult containing the result of the search
     * @see https://docs.nextcloud.com/server/19/developer_manual/client_apis/WebDAV/search.html
     */
    public FileQueryResult search(@Nonnull Query query) {
        final String fromPrefix = String.format("/files/%s/", this.getCurrentUser());

        // For convience set a from as user root folder, if not set
        if (query.getFrom() == null || query.getFrom().isBlank()) {
            final List<Property> select = query.getSelect();
            final String from = fromPrefix;
            final Condition condition = query.getWhere();
            final List<Order> order = query.getOrderBy();
            final Integer limit = query.getLimit();

            Query q = Query.select(select).from(from).where(condition).orderBy(order);
            if (limit != null) {
                q = q.limit(limit);
            }

            return search(q);
        }

        // Check if query valid
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(query.getWhere(), "query.where must not be null");
        Objects.requireNonNull(query.getFrom(), "query.from must not be null");

        // At the moment only search queries for files are supported meaning the the
        // scope should always start with files/$username.
        if (!query.getFrom().startsWith(fromPrefix)) {
            logger.errorf("Search queries must start with '/files/$username/: %s --> Prefix is added", query.getFrom());

            final List<Property> select = query.getSelect();
            final String from = fromPrefix
                    + (query.getFrom().startsWith("/") ? query.getFrom().substring(1) : query.getFrom());
            final Condition condition = query.getWhere();
            final List<Order> order = query.getOrderBy();
            final Integer limit = query.getLimit();

            Query q = Query.select(select).from(from).where(condition).orderBy(order);
            if (limit != null) {
                q = q.limit(limit);
            }

            return search(q);
        }

        final NextcloudRestClient client = QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(authProvider.getServer()))
                .followRedirects(true)
                .build(NextcloudRestClient.class);

        logger.debugf("Requested search: %s", query);
        final String result = client.search(query);

        try {
            final SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(true);
            spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            final SAXSource saxSource = new SAXSource(
                    spf.newSAXParser().getXMLReader(),
                    new InputSource(new StringReader(result)));
            final JAXBContext context = JAXBContext.newInstance(Multistatus.class);
            final Multistatus status = (Multistatus) context.createUnmarshaller().unmarshal(saxSource);

            return FileQueryResult.of(status);
        } catch (JAXBException | ParserConfigurationException | SAXException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * If the full-text search module is installed, one can perform fulltext-search
     * queries
     * 
     * @param query Search query to execute
     * @return result of the search query
     */
    public FulltextSearchResult fulltextSearch(final FulltextSearchQuery query) {
        final NextcloudRestClient client = QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(authProvider.getServer()))
                .followRedirects(true)
                .build(NextcloudRestClient.class);

        final FulltextSearchResult result = client.fulltextsearch(query);
        return result;
    }

    /**
     * Upload the file
     * 
     * @param path    Relative path of the file
     * @param content Content to upload
     * @throws IOException
     */
    public void uploadFile(String path, @Nonnull InputStream content) throws IOException {
        final String url = getWebDavFilePath(path);
        sardine.put(url, content);
    }

    /**
     * Upload the file
     * 
     * @param path        Relative path of the file
     * @param contentType Content type of the file
     * @param content     Content to upload
     * @throws IOException
     */
    public void uploadFile(String path, @Nullable String contentType, @Nonnull InputStream content) throws IOException {
        final String url = getWebDavFilePath(path);
        sardine.put(url, content, contentType);
    }

    /**
     * Upload the file
     * 
     * @param path        Relative path of the file
     * @param contentType Optional content type of the file
     * @param content     Content to upload
     * @param etag        Optional etag of the original content. If set, file upload
     *                    only
     *                    succeeds if ETAG matches ( file unchanged )
     * @param lockToken   Optional Lock token to transmit to indicate we
     *                    handle this upload within an existing lock
     * @throws IOException
     */
    @SuppressWarnings("unused")
    public void uploadFile(String path, @Nullable String contentType, @Nonnull InputStream content,
            @Nullable String etag,
            @Nullable String lockToken)
            throws IOException {
        final String url = getWebDavFilePath(path);
        final Map<String, String> headers = new HashMap<>();
        if (etag != null) {
            headers.put("If-Match", etag);
        }
        if (lockToken != null && false) {
            // FIXME: Does not work currently with nextcloud
            headers.put("If", String.format("</remote.php/dav/files/%s/%s> (<%s> [%s])", authProvider.getUser(), path,
                    lockToken, etag));
        }
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        sardine.put(url, content, headers);
    }

    /**
     * Upload the file
     * 
     * @param path        Relative path of the file
     * @param contentType Optional content type of the file
     * @param content     Content to upload
     * @param etag        Optional etag of the original content. If set, file upload
     *                    only
     *                    succeeds if ETAG matches ( file unchanged )
     * @param lock        Optional Lock to transmit to indicate we
     *                    handle this upload within an existing lock
     * @throws IOException
     */
    public void uploadFile(String path, @Nullable String contentType, @Nonnull InputStream content,
            @Nullable String etag,
            @Nullable NextCloudFileLock lock)
            throws IOException {
        final String lockTocken = lock != null ? lock.token() : null;
        uploadFile(path, contentType, content, etag, lockTocken);
    }

    /**
     * Deletes the file
     * 
     * @param path      Relative path of the file
     * @param content   Content to upload
     * @param etag      Optional etag of the original content. If set, file delete
     *                  only
     *                  succeeds if ETAG matches ( file unchanged )
     * @param lockToken Optional Lock token to transmit to indicate we
     *                  handle this delete within an existing lock
     * @throws IOException
     */
    public void deleteFile(String path, @Nullable String etag, @Nullable String lockToken)
            throws IOException {
        final String url = getWebDavFilePath(path);
        final Map<String, String> headers = new HashMap<>();
        if (etag != null) {
            headers.put("If-Match", etag);
        }
        if (lockToken != null) {
            headers.put("If", String.format("(<%s>)", lockToken));
        }
        sardine.delete(url, null);
    }

    /**
     * Deletes the file
     * 
     * @param path    Relative path of the file
     * @param content Content to upload
     * @param etag    Optional etag of the original content. If set, file delete
     *                only
     *                succeeds if ETAG matches ( file unchanged )
     * @param lock    Optional Lock to transmit to indicate we
     *                handle this delete within an existing lock
     * @throws IOException
     */
    public void deleteFile(String path, @Nullable String etag, @Nullable NextCloudFileLock lock)
            throws IOException {
        final String lockTocken = lock != null ? lock.token() : null;
        deleteFile(path, etag, lockTocken);
    }

    /**
     * Moves a file from one destination to the other. File id stays the same!
     * 
     * @param path       Relative source path of the file (including filename!)
     * @param targetPath Relative target path of the file (including filename!)
     */
    public void moveFile(String path, String targetPath) {
        final String src = getWebDavFilePath(path);
        final String target = getWebDavFilePath(targetPath);
        try {
            sardine.move(src, target);
        } catch (IOException e) {
            logger.warnf(e, "Failed to move file '%s' to '%s'", path, targetPath);
            throw new RuntimeException(e);
        }
    }

    /**
     * Locks the given file remote on the Nextcloud server. Requires the
     * 'files_lock' app to be active
     * 
     * @param path relative path of file
     * @return {@link NextCloudFileLock}
     * @throws RuntimeException
     */
    public NextCloudFileLock lockFile(String path) throws IOException {
        final String url = getWebDavFilePath(path);
        final String token = sardine.lock(url);
        return new NextCloudFileLock(url, token);
    }

}
