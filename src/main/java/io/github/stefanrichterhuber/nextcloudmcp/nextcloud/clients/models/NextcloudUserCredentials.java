package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models;

public record NextcloudUserCredentials(String loginName, String appPassword, String server) {
}