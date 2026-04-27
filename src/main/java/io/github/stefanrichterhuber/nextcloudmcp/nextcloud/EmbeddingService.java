package io.github.stefanrichterhuber.nextcloudmcp.nextcloud;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.stefanrichterhuber.nextcloudlib.runtime.models.NextcloudFile;
import jakarta.activation.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class EmbeddingService {

    @Inject
    EmbeddingModel embeddingModel;

    /**
     * Searches the given embedding store for query
     * 
     * @param embeddingStore
     * @param query
     * @param minScore
     * @param maxResults
     * @return
     */
    public EmbeddingSearchResult<TextSegment> searchEmbeddingStore(EmbeddingStore<TextSegment> embeddingStore,
            String query, Double minScore, int maxResults) {
        final Embedding queryEmbedding = embeddingModel.embed(query).content();
        final EmbeddingSearchResult<TextSegment> relevant = embeddingStore.search(EmbeddingSearchRequest.builder()
                .minScore(minScore).queryEmbedding(queryEmbedding).maxResults(maxResults).build());
        return relevant;
    }

    /**
     * Creates an in-memory embedding store for the given Nextcloud file
     * 
     * @param file
     * @return
     * @throws IOException
     */
    public InMemoryEmbeddingStore<TextSegment> createInMemoryEmbeddingStoreForNextcloudFile(NextcloudFile file)
            throws IOException {
        final InMemoryEmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
        if (file != null) {
            final List<TextSegment> segments = createTextSegmentsFromNextcloudFile(file);
            final List<Embedding> embeddings = segments.stream().map(ts -> embeddingModel.embed(ts).content()).toList();

            embeddingStore.addAll(embeddings, segments);
            return embeddingStore;
        }
        return embeddingStore;
    }

    @Produces
    @ApplicationScoped
    public EmbeddingModel getEmbeddingModel() {
        final EmbeddingModel embeddingModel = new AllMiniLmL6V2QuantizedEmbeddingModel();
        return embeddingModel;
    }

    /**
     * Creates a Document from a Nextcloudfile
     * 
     * @param file File to read
     * @return Document created / null if no document found
     * @throws IOException
     */
    public Document createDocumentFromNextcloudFile(NextcloudFile file) throws IOException {
        if (file != null) {
            final DataSource ds = file.dataSource();
            final String contentType = ds.getContentType();

            final Metadata metadata = new Metadata() //
                    .put("name", ds.getName())
                    .put("file", file.path())
                    .put("content-type", contentType)
                    .put("modified", file.modified().getTime())
                    .put("etag", file.etag());
            if (contentType != null && contentType.startsWith("text/")) {
                try (InputStream is = ds.getInputStream()) {
                    final String content = IOUtils.toString(is, StandardCharsets.UTF_8);
                    final Document doc = Document.document(content, metadata);
                    return doc;
                }
            } else {
                try (InputStream is = ds.getInputStream()) {
                    final String content = new Tika().parseToString(is);
                    final Document doc = Document.document(content, metadata);
                    return doc;
                } catch (TikaException e) {
                    throw new IOException(e);
                }
            }
        } else {
            return null;
        }
    }

    /**
     * Reads a Nextcloud file into a List of TextSegments
     * 
     * @param file
     * @return
     * @throws IOException
     */
    public List<TextSegment> createTextSegmentsFromNextcloudFile(NextcloudFile file) throws IOException {
        final Document doc = createDocumentFromNextcloudFile(file);
        if (doc != null) {
            final DocumentSplitter splitter = DocumentSplitters.recursive(800, 400);
            final List<TextSegment> segments = splitter.split(doc);
            return segments;
        }
        return Collections.emptyList();
    }
}
