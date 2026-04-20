package io.github.stefanrichterhuber.nextcloudmcp;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import ezvcard.VCard;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.NextcloudContactService;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.NextcloudContactService.Addressbook;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
public class NextcloudContactServerTest {

    @Inject
    NextcloudContactService service;

    @Test
    public void fetchAddressBooks() throws IOException {
        List<Addressbook> result = service.listAddressBooks();

        assertNotNull(result);

        for (Addressbook addressbook : result) {
            try {
                List<VCard> card = service.fetchContacts(addressbook);
                System.out.println(card);
            } catch (Exception e) {
                System.out.println("Failed to fetch addressbook " + addressbook + " : " + e);
            }
        }
    }
}
