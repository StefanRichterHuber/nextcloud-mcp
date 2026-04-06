package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search;

import javax.xml.namespace.QName;

public class Property implements Value {
    public static final Property DISPLAY_NAME = new Property("d", "displayname");
    public static final Property GET_CONTENT_TYPE = new Property("d", "getcontenttype");
    public static final Property GET_LAST_MODIFIED = new Property("d", "getlastmodified");
    public static final Property SIZE = new Property("oc", "size");
    /**
     * set to 1 to select favorite files
     */
    public static final Property FAVORITE = new Property("oc", "favorite");
    public static final Property FILE_ID = new Property("oc", "fileid");
    public static final Property RESOURCE_TYPE = new Property("d", "resourcetype");
    public static final Property GET_CONTENT_LENGTH = new Property("d", "getcontentlength");
    public static final Property CHECKSUMS = new Property("oc", "checksums");
    public static final Property PERMISSIONS = new Property("oc", "permissions");
    public static final Property GET_ETAG = new Property("d", "getetag");
    public static final Property OWNER_ID = new Property("oc", "owner-id");
    public static final Property OWNER_DISPLAY_NAME = new Property("oc", "owner-display-name");
    public static final Property DATA_FINGERPRINT = new Property("oc", "data-fingerprint");
    public static final Property HAS_PREVIEW = new Property("oc", "has-preview");
    /**
     * Use this to select system tags, use {@link #SYSTEM_TAG_ID} to filter tags by
     * id.
     */
    public static final Property SYSTEM_TAG_ID = new Property("oc", "systemtag");

    /**
     * Use this to select system tags, use {@link #SYSTEM_TAG_ID} to filter tags by
     * id.
     */
    public static final Property SYSTEM_TAGS = new Property("nc", "system-tags");

    public static final Property MOUNT_TYPE = new Property("nc", "mount-type");
    public static final Property IS_ENCRYPTED = new Property("nc", "is-encrypted");
    public static final Property SHARE_PERMISSIONS = new Property("ocs", "share-permissons");
    public static final Property SHARE_ATTRIBUTES = new Property("nc", "share-attributes");
    public static final Property TAGS = new Property("oc", "tags");
    public static final Property LOCK = new Property("nc", "lock");
    public static final Property LOCK_OWNER = new Property("nc", "lock-owner");
    public static final Property LOCK_OWNER_DISPLAY_NAME = new Property("nc", "lock-owner-displayname");

    private final String name;
    private final String prefix;

    public Property(String prefix, String name) {
        this.name = name;
        this.prefix = prefix;
    }

    public String getName() {
        return this.name;
    }

    public String getPrefix() {
        return this.prefix;
    }

    public QName toQNAme() {
        String url = switch (this.prefix) {
            case "oc" -> "http://owncloud.org/ns";
            case "d" -> "DAV:";
            case "nc" -> "http://nextcloud.org/ns";
            default -> {
                throw new IllegalStateException("Unknown webdav property perfix: " + this.prefix);
            }
        };

        return new QName(url, this.name, this.prefix);
    }

    @Override
    public StringBuilder render(StringBuilder sb, int indent) {
        // <oc:fileid/>
        sb.append(indent(indent)).append("<").append(prefix).append(":").append(name).append("/>");
        return sb;
    }

}
