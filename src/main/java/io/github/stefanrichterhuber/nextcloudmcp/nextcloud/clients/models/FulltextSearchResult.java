package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models;

import java.util.Date;
import java.util.List;

import com.github.sardine.Sardine;

import jakarta.activation.DataSource;

/**
 * @see https://github.com/nextcloud/fulltextsearch/wiki/Include-Full-text-search-in-your-client
 */
public record FulltextSearchResult(
        List<Result> result,
        int status,
        Request request,
        String version) {

    public record Result(
            Provider provider,
            Platform platform,
            List<Document> documents,
            List<String> info,
            Meta meta) {
        public record Provider(String id, String name) {
        }

        public record Platform(String id, String name) {
        }

        public record Meta(boolean timedOut, int time, int count, int total, int maxScore) {
        }

        public record Document(
                String id,
                String providerId,
                Access access,
                long modifiedTime,
                String title,
                String link,
                String index,
                String source,
                Info info,
                String hash,
                long contentSize,
                List<String> tags,
                List<String> metatags,
                List<String> subtags,
                List<String> more,
                List<Excerpt> excerpts,
                String score

        ) {
            public record Access(String ownerId, String viewerId, List<String> users, List<String> groups,
                    List<String> circles, List<String> links) {
            }

            public record Info(String webdav, String path, String type, String file, String dir, String mime,
                    boolean favorite, long size, long mtime, String etag, int permissions, Unified unified) {
                public record Unified(String thumbUrl, String icon) {
                }
            }

            public record Excerpt(String source, String excerpt) {
            }

            public NextCloudFile toNextCloudFile(final String server, final Sardine sardine) {
                final String user = access().viewerId();
                final String etag = info().etag();
                final String contentType = info().mime();
                final Date modified = new Date(info().mtime());
                final Long contentLength = (long) info().size();
                final Integer fileId = Integer.parseInt(id());
                final String path = String.format("%s%s", server, info().path());
                final DataSource ds = new SardineDataSource(sardine, path, contentType);
                return new NextCloudFile(fileId, user, path, etag, modified, ds, contentLength);
            }
        }
    }

    public record Request(
            List<String> providers,
            String author,
            String search,
            boolean empty_search,
            int page,
            int size,
            List<String> parts,
            List<String> queries,
            Options options,
            List<String> metatags,
            List<String> subtags,
            List<String> tags

    ) {
        public record Options(String files_local, String files_external, String files_extension) {
        }
    }

}
