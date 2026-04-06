package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search;

public class Literal implements Value {
    private final String value;

    public Literal(String value) {
        this.value = value;
    }

    @Override
    public StringBuilder render(StringBuilder sb, int indent) {
        // <d:literal>12345</d:literal>
        sb.append(indent(indent)).append("<d:literal>").append(value).append("</d:literal>");
        return sb;
    }
}
