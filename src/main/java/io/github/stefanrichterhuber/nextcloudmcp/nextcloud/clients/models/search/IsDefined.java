package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search;

/**
 * Check if a value is not null
 */
public class IsDefined implements Condition {

    private final Value value;

    public IsDefined(Value value) {
        this.value = value;
    }

    @Override
    public StringBuilder render(StringBuilder sb, int indent) {

        sb.append(indent(indent)).append("<").append("d").append(":").append("is-defined")
                .append(">\n");

        if (value instanceof Property) {
            sb.append(indent(indent + 1)).append("<d:prop>\n");
            value.render(sb, indent + 2).append("\n");
            sb.append(indent(indent + 1)).append("</d:prop>\n");
        } else {
            value.render(sb, indent + 1).append("\n");
        }

        sb.append(indent(indent)).append("<").append("d").append(":").append("is-defined")
                .append(">\n");
        return sb;
    }

}
