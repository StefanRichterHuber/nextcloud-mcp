package eu.stefanhuber.aiagent.nextcloud.model;

import java.util.Date;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.activation.DataSource;

@RegisterForReflection
public record NextCloudFile(Integer fileId, String user, String path, String etag, Date modified, DataSource dataSource,
                Long contentLength) {

}
