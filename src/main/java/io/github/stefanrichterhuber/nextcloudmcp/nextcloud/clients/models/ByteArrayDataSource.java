package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;

import jakarta.activation.DataSource;

public class ByteArrayDataSource implements DataSource {

    /**
     * Content type of file
     */
    private final String contentType;
    /**
     * Path of the file
     */
    private final String path;

    private final byte[] content;

    public ByteArrayDataSource(String path, String contentType, byte[] content) {
        this.path = path;
        this.contentType = contentType;
        this.content = content;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new ByteArrayInputStream(content);
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        throw new UnsupportedOperationException("Unimplemented method 'getOutputStream'");
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public String getName() {
        return Paths.get(path).getFileName().toString().replace("%20", " ");

    }

}
