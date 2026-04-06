package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search;

public class IsCollection implements Condition {

    @Override
    public StringBuilder render(StringBuilder sb, int indent) {
        sb.append(indent(indent)).append("<d:is-collection/>");
        return sb;
    }

    public static IsCollection isCollection() {
        return new IsCollection();
    }
}
