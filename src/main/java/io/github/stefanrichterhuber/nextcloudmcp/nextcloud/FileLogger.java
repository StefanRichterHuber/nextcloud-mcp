package io.github.stefanrichterhuber.nextcloudmcp.nextcloud;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.stefanrichterhuber.nextcloudmcp.config.AppConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class FileLogger implements AuditLogger {
    @Inject
    Logger logger;

    @Inject
    AppConfig config;

    @Inject
    ObjectMapper objectMapper;

    @PostConstruct
    void init() {
        try {
            final File parentFile = config.audit().target().getParentFile();
            if (parentFile != null) {
                Files.createDirectories(parentFile.toPath());
            }
        } catch (IOException e) {
            logger.errorf(e, "Failed create parent directories for file %s", config.audit().target());
        }
    }

    @Override
    public void log(AuditLogEntry auditLogEntry) {
        try {
            final String json = objectMapper.writeValueAsString(auditLogEntry);
            synchronized (this) {
                Files.writeString(config.audit().target().toPath(), json + "\n", StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            logger.errorf(e, "Failed write to log file %s", config.audit().target());
        }
    }
}
