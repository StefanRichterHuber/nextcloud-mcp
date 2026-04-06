package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search;

public class Order implements Renderable {
    public enum Direction {
        ASC("d", "ascending"), DESC("d", "descending");

        private final String prefix;
        private final String value;

        private Direction(String prefix, String value) {
            this.prefix = prefix;
            this.value = value;
        }

        public String getValue() {
            return this.value;
        }

        public String getPrefix() {
            return this.prefix;
        }

    }

    private final Property property;
    private final Direction direction;

    public Order(Property property, Direction direction) {
        this.property = property;
        this.direction = direction;
    }

    public static Order asc(Property property) {
        return new Order(property, Direction.ASC);
    }

    public static Order desc(Property property) {
        return new Order(property, Direction.DESC);
    }

    @Override
    public StringBuilder render(StringBuilder sb, int indent) {
        /*
         * <d:order>
         * <d:prop>
         * <d:getlastmodified/>
         * </d:prop>
         * <d:descending/>
         * </d:order>
         */
        sb.append(indent(indent)).append("<d:order>\n");
        sb.append(indent(indent + 1)).append("<d:prop>\n");
        property.render(sb, indent + 2).append("\n");
        sb.append(indent(indent + 1)).append("</d:prop>\n");
        sb.append(indent(indent + 1)).append("<").append(direction.getPrefix()).append(":")
                .append(direction.getValue())
                .append("/>\n");
        sb.append(indent(indent)).append("</d:order>\n");
        return sb;
    }

}
