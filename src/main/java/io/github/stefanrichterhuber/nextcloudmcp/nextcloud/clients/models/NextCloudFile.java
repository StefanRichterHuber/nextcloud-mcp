package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Date;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.activation.DataSource;

@RegisterForReflection
public record NextCloudFile(Integer fileId, String user, String path, String etag, Date modified, DataSource dataSource,
        Long contentLength) {

    /**
     * Reads the whole content of this Nextcloud file as text with the given Charset
     * 
     * @param cs Charset to use
     * @return Text
     * @throws IOException
     */
    public String readToString(Charset cs) throws IOException {
        try (InputStream is = dataSource.getInputStream()) {
            byte[] content = is.readAllBytes();
            return new String(content, cs);
        }
    }

}
