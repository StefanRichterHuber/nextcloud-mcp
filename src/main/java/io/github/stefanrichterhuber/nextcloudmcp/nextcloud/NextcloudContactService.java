package io.github.stefanrichterhuber.nextcloudmcp.nextcloud;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.namespace.QName;

import org.jboss.logging.Logger;

import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.github.sardine.model.Multistatus;
import com.github.sardine.report.SardineReport;

import ezvcard.Ezvcard;
import ezvcard.VCard;
import io.github.stefanrichterhuber.nextcloudmcp.auth.NextcloudAuthProvider;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class NextcloudContactService {
    @Inject
    Logger logger;

    @Inject
    Sardine sardine;

    @Inject
    NextcloudAuthProvider authProvider;

    public record Addressbook(String displayname, String name, String href) {
    }

    /**
     * List all address books of the current user
     * 
     * @throws IOException
     */
    public List<Addressbook> listAddressBooks() throws IOException {
        final String user = authProvider.getUser();
        final String target = String.format("%s/remote.php/dav/addressbooks/users/%s/", authProvider.getServer(),
                user);

        final QName qnameSyncToken = new QName("DAV:", "sync-token", "d");
        final QName qnameDisplayName = new QName("DAV:", "displayname", "d");
        final Set<QName> properties = Set.of( //
                qnameDisplayName, //
                qnameSyncToken //
        );

        final List<DavResource> propfind = this.sardine.propfind(target, 1, properties);
        final List<Addressbook> result = new ArrayList<>(propfind.size());
        for (DavResource r : propfind) {
            final String displayname = r.getDisplayName();
            if (displayname == null || displayname.isBlank()) {
                continue;
            }
            final String href = r.getHref().toString();
            // Remove final /
            String name = href.endsWith("/") ? href.substring(0, href.length() - 1) : href;
            name = name.substring(name.lastIndexOf("/") + 1);
            result.add(new Addressbook(displayname, name, href));
        }
        return result;
    }

    /**
     * Fetches all contacts for the given user and addressbook
     * 
     * @param addressbook Addressbook to fetch
     * @throws IOException
     * @see https://sabre.io/dav/building-a-carddav-client/
     * @see https://github.com/mangstadt/ez-vcard
     */
    public List<VCard> fetchContacts(@Nonnull Addressbook addressbook) throws IOException {
        if (addressbook == null) {
            return List.of();
        }
        return fetchContacts(addressbook.name());
    }

    /**
     * Fetches all contacts for the given user and addressbook
     * 
     * @param addressbook Name of the addressbook
     * @throws IOException
     * @see https://sabre.io/dav/building-a-carddav-client/
     * @see https://github.com/mangstadt/ez-vcard
     */
    public List<VCard> fetchContacts(@Nonnull String addressbook) throws IOException {
        if (addressbook == null || addressbook.isBlank()) {
            return List.of();
        }
        final String user = authProvider.getUser();
        addressbook = addressbook.replace(" ", "%20");
        final String target = String.format("%s/remote.php/dav/addressbooks/users/%s/%s/", authProvider.getServer(),
                user, addressbook);

        final List<VCard> cards = sardine.report(target, 1, new SardineReport<List<VCard>>() {

            @Override
            public String toXml() throws IOException {
                return "<card:addressbook-query xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" //
                        + "    <d:prop>\n" //
                        + "        <d:getetag />\n" //
                        + "        <card:address-data />\n" //
                        + "    </d:prop>\n" //
                        + "</card:addressbook-query>"; //
            }

            @Override
            public Object toJaxb() {
                // TODO Auto-generated method stub
                return null;
            }

            @Override
            public List<VCard> fromMultistatus(Multistatus multistatus) {
                final List<VCard> result = multistatus.getResponse().stream() //
                        .map(r -> r.getPropstat()) //
                        .flatMap(List::stream) //
                        .map(ps -> ps.getProp()) //
                        .filter(p -> p != null) //
                        .map(p -> p.getAny()) //
                        .flatMap(List::stream) //
                        .map(l -> l.getFirstChild()) //
                        .filter(l -> l != null) //
                        .map(n -> n.getNodeValue()) //
                        .filter(n -> n != null && !n.isBlank()) //
                        .map(n -> Ezvcard.parse(n)) //
                        .flatMap(p -> p.all().stream()) //
                        .collect(Collectors.toList());
                return result;
            }
        });

        return cards;
    }
}
