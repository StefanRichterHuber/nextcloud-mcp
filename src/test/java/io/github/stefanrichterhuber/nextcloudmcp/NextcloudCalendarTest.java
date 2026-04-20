package io.github.stefanrichterhuber.nextcloudmcp;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.NextcloudCalendarService;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.NextcloudCalendarService.Calendar;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.NextcloudCalendarService.WebDavCalendar;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
public class NextcloudCalendarTest {

    @Inject
    NextcloudCalendarService service;

    @Test
    public void testFetchCalendars() throws IOException {
        List<Calendar> calendars = service.listCalendars();

        assertNotNull(calendars);

        for (Calendar c : calendars) {
            List<WebDavCalendar> cals = service.fetchCalendar(c, ZonedDateTime.now().minusDays(10),
                    ZonedDateTime.now());

            for (WebDavCalendar wdc : cals) {
                System.out.println(wdc);
            }

        }
    }
}
