package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import com.github.sardine.DavResource;
import com.github.sardine.Sardine;

import jakarta.activation.DataSource;

/**
 * Creates a {@link DataSource} from a WebDav remote file.
 */
public class SardineDataSource implements DataSource {
    /**
     * Sardine instance to access WebDav server
     */
    private final Sardine sardine;
    /**
     * Content type of file
     */
    private String contentType;
    /**
     * Path of the file
     */
    private final String file;

    /**
     * Creates a new {@link SardineDataSource}
     * 
     * @param sardine     {@link Sardine} instance to access WebDav server
     * @param targetFile  Path of the file
     * @param contentType (Expected) Content type of the file. Can be null, in this
     *                    case {@link #getContentType()} lazily fetches the content
     *                    type on first access.
     */
    public SardineDataSource(Sardine sardine, String targetFile, String contentType) {
        this.sardine = sardine;
        this.contentType = contentType;
        this.file = targetFile;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return new ByteArrayOutputStream() {
            private boolean isClosed = false;

            @Override
            public void close() throws IOException {
                if (!isClosed) {
                    final byte[] result = this.toByteArray();
                    if (contentType != null) {
                        sardine.put(file, result, contentType);
                    } else {
                        sardine.put(file, result);
                    }
                    isClosed = true;
                }
            }
        };
    }

    @Override
    public String getName() {
        return Paths.get(file).getFileName().toString().replace("%20", " ");
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return sardine.get(file);
    }

    @Override
    public String getContentType() {
        if (contentType == null) {
            try {
                final List<DavResource> propfind = sardine.propfind(file, 1, Collections.emptySet());
                if (!propfind.isEmpty()) {
                    final DavResource davResource = propfind.get(0);
                    this.contentType = davResource.getContentType();
                } else {
                    throw new RuntimeException("Unable to find file " + file);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return contentType;
    }
}