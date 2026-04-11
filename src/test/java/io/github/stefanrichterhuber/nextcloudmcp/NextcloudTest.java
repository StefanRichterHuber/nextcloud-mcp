package io.github.stefanrichterhuber.nextcloudmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.github.difflib.patch.PatchFailedException;

import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.NextcloudService;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.FileQueryResult;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.NextCloudFile;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search.Condition;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search.Property;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search.Query;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
public class NextcloudTest {

    private final static String TEST_TEXT1 = """
            # Ode to the Cloud

            Up in the servers, quiet and vast,
            Where files are stored and memories last,
            A markdown file in a folder sleeps,
            While Nextcloud faithfully its promise keeps.

            Through tunnels of light the data flows,
            Past patches and diffs, the revision grows,
            Each change a whisper, each save a breath,
            A document lives its little life past death.

            So here's to the cloud, both humble and bright,
            That keeps our small poems through day and through night.

                        """;

    private final static String PATCH1 = """
            -- a/poem.txt
            +++ b/poem.txt
            @@ -1 +1 @@
            -# Ode to the Cloud
            +# Hello to the cloud
            """;

    private final static String TEST_TEXT2 = """
            Ode to the Cloud

            O Cloud, you silver shelf above my desk,
            where markdown files drift soft as morning mist —
            no hard drive spins, no cable knots, no risk
            of coffee spilled on all that I have kissed

            with careful keystrokes into being. You
            hold every revision, every draft,
            each clumsy edit timestamped, kept true,
            the whole embarrassing creative craft.

            You speak in WebDAV, answer ETags,
            demand preconditions be precisely met —
            a bureaucrat in vapour, filing flags
            on every write I haven't patched quite yet.

            And still I love you, Cloud, with all your rules:
            you remember everything I am too human to.
                        """;

    @Inject
    NextcloudService service;

    @Test
    public void searchFilesTest() {
        // Exercises the JAXB unmarshalling path that was hardened against XXE
        Query query = Query.select(Property.DISPLAY_NAME, Property.GET_CONTENT_TYPE, Property.GET_ETAG)
                .from("/Claude")
                .where(Condition.isFile());

        FileQueryResult result = service.search(query);

        assertNotNull(result);
        assertNotNull(result.getFiles());
    }

    @Test
    public void basicFileAccessTest() throws IOException {
        List<NextCloudFile> files = service.listFiles("/Claude", -1);

        assertNotNull(files);
    }

    @Test
    public void overwriteFileWithEtagTest() throws IOException {
        String filename = "/Claude/" + UUID.randomUUID().toString() + ".md";
        service.uploadFile(filename, "text/markdown",
                new ByteArrayInputStream(TEST_TEXT1.getBytes(StandardCharsets.UTF_8)));
        try {
            NextCloudFile rev1 = service.getFile(filename);
            assertNotNull(rev1);
            String etag = rev1.etag();

            service.uploadFile(filename, "text/markdown",
                    new ByteArrayInputStream(TEST_TEXT2.getBytes(StandardCharsets.UTF_8)), etag, (String) null);

            NextCloudFile rev2 = service.getFile(filename);
            assertNotNull(rev2);
        } finally {
            service.deleteFile(filename, null, (String) null);
        }
    }

    @Test
    public void patchFileWithEtagTest() throws IOException, PatchFailedException {
        String filename = "/Claude/" + UUID.randomUUID().toString() + ".md";

        service.uploadFile(filename, "text/markdown",
                new ByteArrayInputStream(TEST_TEXT1.getBytes(StandardCharsets.UTF_8)));

        try {

            NextCloudFile rev1 = service.getFile(filename);
            assertNotNull(rev1);
            String rev1Text = rev1.readToString(StandardCharsets.UTF_8);
            assertEquals(TEST_TEXT1, rev1Text);

            final List<String> patchContent = Arrays.asList(PATCH1.split("\n"));
            Patch<String> patch = UnifiedDiffUtils.parseUnifiedDiff(patchContent);

            service.applyContentPatch(rev1, patch, 4);

            NextCloudFile rev2 = service.getFile(filename);
            assertNotNull(rev2);

            String rev2Text = rev2.readToString(StandardCharsets.UTF_8);
            assertTrue(rev2Text.startsWith("# Hello to the cloud"));

        } finally {
            service.deleteFile(filename, null, (String) null);
        }
    }

    @Test
    public void deleteFileWithEtagTest() throws IOException {
        String filename = "/Claude/" + UUID.randomUUID().toString() + ".md";

        service.uploadFile(filename, "text/markdown",
                new ByteArrayInputStream(TEST_TEXT1.getBytes(StandardCharsets.UTF_8)));

        NextCloudFile rev1 = service.getFile(filename);
        assertNotNull(rev1);
        String etag = rev1.etag();
        assertNotNull(etag);

        service.deleteFile(filename, etag, (String) null);

    }

}
