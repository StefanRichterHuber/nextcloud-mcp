package io.github.stefanrichterhuber.nextcloudmcp.nextcloud;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record AuditLogEntry(
        String user,
        String nextcloudUser,
        String nextcloudServer,
        String toolName,
        boolean success,
        List<CallArg> arguments,
        String message,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime called,
        // produces "PT5.000S"
        @JsonSerialize(using = ToStringSerializer.class) Duration duration) {
    public record CallArg(String name, JsonNode value) {
    }
}