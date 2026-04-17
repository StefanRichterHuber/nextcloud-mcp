package io.github.stefanrichterhuber.nextcloudmcp.nextcloud;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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

import biweekly.Biweekly;
import biweekly.ICalendar;
import io.github.stefanrichterhuber.nextcloudmcp.auth.NextcloudAuthProvider;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.NextcloudContactService.Addressbook;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class NextcloudCalendarService {
    @Inject
    Logger logger;

    @Inject
    Sardine sardine;

    @Inject
    NextcloudAuthProvider authProvider;

    public record Calendar(String displayname, String name, String href) {
    }

    /**
     * List all calendars of the current user
     * 
     * @throws IOException
     */
    public List<Calendar> listCalendars() throws IOException {
        final String user = authProvider.getUser();
        final String target = String.format("%s/remote.php/dav/calendars/%s/", authProvider.getServer(),
                user);

        final QName qnameSyncToken = new QName("DAV:", "sync-token", "d");
        final QName qnameDisplayName = new QName("DAV:", "displayname", "d");
        final Set<QName> properties = Set.of( //
                qnameDisplayName, //
                qnameSyncToken //
        );

        final List<DavResource> propfind = this.sardine.propfind(target, 1, properties);
        final List<Calendar> result = new ArrayList<>(propfind.size());
        for (DavResource r : propfind) {
            final String displayname = r.getDisplayName();
            if (displayname == null || displayname.isBlank()) {
                continue;
            }
            final String href = r.getHref().toString();
            // Remove final /
            String name = href.endsWith("/") ? href.substring(0, href.length() - 1) : href;
            name = name.substring(name.lastIndexOf("/") + 1);
            result.add(new Calendar(displayname, name, href));
        }
        return result;
    }

    public record WebDavCalendar(String href, String etag, ICalendar cal) {

    }

    /**
     * Fetches all {@link ICalendar}s within the give time range
     * 
     * @param calendar Calendar to fetch
     * @param start    Start of the time range
     * @param end      Ende of the time range
     * @return List of {@link ICalendar}
     * @throws IOException
     * @see https://github.com/mangstadt/biweekly
     */
    public List<WebDavCalendar> fetchCalendar(@Nonnull Calendar calendar,
            @Nonnull ZonedDateTime start, @Nonnull ZonedDateTime end)
            throws IOException {
        if (calendar == null) {
            return List.of();
        }
        return fetchCalendar(calendar.name(), start, end);
    }

    /**
     * Fetches all {@link ICalendar}s within the give time range
     * 
     * @param calendar Name of the calendar
     * @param start    Start of the time range
     * @param end      Ende of the time range
     * @return List of {@link ICalendar}
     * @throws IOException
     * @see https://github.com/mangstadt/biweekly
     */
    public List<WebDavCalendar> fetchCalendar(@Nonnull String calendar,
            @Nonnull ZonedDateTime start, @Nonnull ZonedDateTime end)
            throws IOException {
        final String user = authProvider.getUser();
        final String target = String.format("%s/remote.php/dav/calendars/%s/%s/", authProvider.getServer(), user,
                calendar);
        final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

        final String startStr = dtf.format(start.withZoneSameInstant(ZoneId.of("UTC"))); // 20220104T000000Z
        final String endStr = dtf.format(end.withZoneSameInstant(ZoneId.of("UTC"))); // 20230105T000000Z

        final List<WebDavCalendar> result = sardine.report(target, 1, new SardineReport<List<WebDavCalendar>>() {
            @Override
            public String toXml() throws IOException {
                return String.format(" <calendar-query xmlns:D=\"DAV:\" xmlns=\"urn:ietf:params:xml:ns:caldav\">\n" //
                        + "   <D:prop>\n"//
                        + "     <D:getetag/>\n" //
                        + "     <calendar-data />\n" //
                        + "   </D:prop>\n" //
                        + "   <filter>\n"//
                        + "     <comp-filter name=\"VCALENDAR\">\n" //
                        + "       <comp-filter name=\"VEVENT\">\n"//
                        + "         <time-range start=\"%s\" end=\"%s\"/>\n"//
                        + "       </comp-filter>\n" //
                        + "     </comp-filter>\n"//
                        + "  </filter>\n" //
                        + "</calendar-query>", startStr, endStr);
            }

            @Override
            public Object toJaxb() {
                // TODO Auto-generated method stub
                return null;
            }

            @Override
            public List<WebDavCalendar> fromMultistatus(Multistatus multistatus) {
                final List<WebDavCalendar> result = new ArrayList<>();
                for (var response : multistatus.getResponse()) {
                    final String href = response.getHref().get(0);
                    final String etag = response.getPropstat().stream().map(ps -> ps.getProp()).map(p -> p.getGetetag())
                            .findFirst().map(et -> et.getContent().get(0)).orElse(null);

                    result.addAll(response.getPropstat().stream().map(ps -> ps.getProp()) //
                            .filter(p -> p != null) //
                            .map(p -> p.getAny()) //
                            .flatMap(List::stream) //
                            .map(l -> l.getFirstChild()) //
                            .filter(l -> l != null) //
                            .map(n -> n.getNodeValue()) //
                            .filter(n -> n != null && !n.isBlank()) //
                            .map(n -> Biweekly.parse(n)) //
                            .flatMap(p -> p.all().stream()) //
                            .map(c -> new WebDavCalendar(href, etag, c)) //
                            .collect(Collectors.toList()));

                }
                return result;
            }
        });

        return result;
    }
}
