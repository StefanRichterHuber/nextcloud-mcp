package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.w3c.dom.Element;

import com.github.sardine.Sardine;
import com.github.sardine.model.Multistatus;
import com.github.sardine.model.Prop;
import com.github.sardine.model.Response;

import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search.Property;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.activation.DataSource;

@RegisterForReflection
public class FileQueryResult {
    public static class QueriedFile {
        private final Response response;

        private QueriedFile(Response response) {
            this.response = response;
        }

        public Response getResponse() {
            return this.response;
        }

        /**
         * Returns the absolute file path of the file (including webdav prefix and user
         * name)
         * 
         * @return
         */
        public String getAbsolutePath() {
            return this.response.getHref().get(0);
        }

        /**
         * Returns the relative file path for the user
         * 
         * @return
         */
        public String getPath() {
            // Path without nextcloud prefix
            String path = getAbsolutePath().replaceFirst("/remote.php/dav/files/", "");
            // Path without user name prefix
            path = path.substring(path.indexOf("/") + 1);

            return path;
        }

        public String toString() {
            return this.getAbsolutePath();
        }

        private Prop getProp() {
            return this.getResponse().getPropstat().get(0).getProp();
        }

        private Optional<Object> extractAnyValue(String property) {
            Element e = this.getProp().getAny().stream().filter(a -> a.getLocalName().equals(property)).findFirst()
                    .orElse(null);

            if (e != null) {
                if (e.getChildNodes().getLength() == 1) {
                    String content = e.getFirstChild().getTextContent();
                    return "".equals(content) ? Optional.empty() : Optional.of(content);
                } else if (e.getChildNodes().getLength() > 0) {
                    final List<Object> values = new ArrayList<>();
                    for (int i = 0; i < e.getChildNodes().getLength(); i++) {
                        values.add(e.getChildNodes().item(i).getTextContent());
                    }
                    return Optional.of(values);
                } else {
                    final String content = e.getTextContent();
                    return "".equals(content) ? Optional.empty() : Optional.of(content);
                }
            }
            return Optional.empty();
        }

        public Optional<Object> getProperty(
                io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search.Property p) {
            return getProperty(p.getName());
        }

        private Optional<Object> getProperty(String name) {
            final String localName = name.contains(":") ? name.split(":")[1] : name;

            switch (localName) {
                case "getlastmodified":
                    return Optional.ofNullable(this.getProp().getGetlastmodified().getContent().get(0));
                case "displayname":
                    return Optional.ofNullable(this.getProp().getDisplayname().getContent().get(0));
                case "creationdate":
                    return Optional.ofNullable(this.getProp().getCreationdate().getContent().get(0));
                case "getcontentlanguage":
                    return Optional.ofNullable(this.getProp().getGetcontentlanguage().getContent().get(0));
                case "getcontentlength":
                    return Optional.ofNullable(this.getProp().getGetcontentlength().getContent().get(0));
                case "getcontenttype":
                    return Optional.ofNullable(this.getProp().getGetcontenttype().getContent().get(0));
                case "getetag":
                    return Optional.ofNullable(this.getProp().getGetetag().getContent().get(0));
                case "lockdiscovery":
                    return Optional.ofNullable(this.getProp().getLockdiscovery().getActivelock());
                case "resourcetype":
                    return Optional.ofNullable(this.getProp().getResourcetype().getPrincipal());
                case "supportedlock":
                    return Optional.ofNullable(this.getProp().getSupportedlock().getLockentry());
                case "supported-report-set":
                    return Optional.ofNullable(this.getProp().getSupportedReportSet().getSupportedReport());
                case "quota-available-bytes":
                    return Optional.ofNullable(this.getProp().getQuotaAvailableBytes().getContent().get(0));
                case "quota-used-bytes":
                    return Optional.ofNullable(this.getProp().getQuotaUsedBytes().getContent().get(0));
                case "systemtag":
                    return getProperty("system-tags");
                default:
                    return extractAnyValue(localName);
            }
        }

        /**
         * Converts this to a NextCloudFile
         * 
         * @param sardine
         * @param user
         * @return
         */
        public NextCloudFile toNextCloudFile(Sardine sardine, String user) {
            final Date lastModified = getProperty(Property.GET_LAST_MODIFIED).map(Object::toString)
                    .map(Long::parseLong).map(Date::new).orElse(null);
            final Integer fileId = getProperty(Property.GET_LAST_MODIFIED).map(Object::toString)
                    .map(Integer::parseInt).orElse(null);
            final String etag = getProperty(Property.GET_ETAG).map(Object::toString).orElse(null);
            final Long contentLength = getProperty(Property.GET_CONTENT_LENGTH).map(Object::toString)
                    .map(Long::parseLong).orElse(null);
            final String contentType = getProperty(Property.GET_CONTENT_TYPE).map(Object::toString).orElse(null);
            final String filePath = getAbsolutePath();
            final DataSource ds = new SardineDataSource(sardine, filePath, contentType);

            final NextCloudFile file = new NextCloudFile(fileId, user, getPath(),
                    etag, lastModified, ds,
                    contentLength);
            return file;
        }

    }

    private final Multistatus multistatus;
    private final List<QueriedFile> files;

    private FileQueryResult(Multistatus multistatus) {
        this.multistatus = multistatus;
        this.files = multistatus.getResponse().stream().map(QueriedFile::new).toList();
    }

    public static FileQueryResult of(Multistatus multistatus) {
        return new FileQueryResult(multistatus);
    }

    public Multistatus getMultistatus() {
        return this.multistatus;
    }

    public List<QueriedFile> getFiles() {
        return this.files;
    }

    public String toString() {
        return getFiles().toString();
    }

}
