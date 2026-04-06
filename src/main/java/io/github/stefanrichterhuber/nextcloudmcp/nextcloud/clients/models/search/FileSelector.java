package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the `oc:filter-files` query to list files in a directory
 */
public class FileSelector {
    public static record FilterRule(Property property, String value) {
    }

    private final List<Property> properties = new ArrayList<>();
    private final List<FilterRule> filterRules = new ArrayList<>();

    private FileSelector() {
    }

    public static FileSelector list(List<Property> properties) {
        FileSelector filter = new FileSelector();
        filter.properties.addAll(properties);
        return filter;
    }

    public static FileSelector list(Property... properties) {
        return list(List.of(properties));
    }

    private String indent(int indent) {
        return " ".repeat(indent * 4);
    }

    public String toXML() {
        StringBuilder sb = new StringBuilder();
        sb.append(
                "<oc:filter-files  xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\" xmlns:nc=\"http://nextcloud.org/ns\" xmlns:ocs=\"http://open-collaboration-services.org/ns\">\n");

        sb.append(indent(1)).append("<d:prop>").append("\n");
        for (Property property : properties) {
            property.render(sb, 2).append("\n");
        }
        sb.append(indent(1)).append("</d:prop>").append("\n");

        sb.append(indent(1)).append("<oc:filter-rules>").append("\n");
        for (FilterRule rule : filterRules) {
            sb.append(indent(2)).append("<").append(rule.property.getPrefix()).append(":")
                    .append(rule.property().getName()).append(">");
            sb.append(rule.value());
            sb.append("</").append(rule.property.getPrefix()).append(":")
                    .append(rule.property().getName()).append(">");
            sb.append("\n");
        }

        sb.append(indent(1)).append("</oc:filter-rules>").append("\n");

        sb.append("</oc:filter-files>").append("\n");
        return sb.toString();
    }

    public FileSelector withFilter(Property property, String value) {
        this.filterRules.add(new FilterRule(property, value));
        return this;

    }

    public String toString() {
        return toXML();
    }
}
