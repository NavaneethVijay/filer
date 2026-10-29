package com.navaneeth.filer.data.search

import com.navaneeth.filer.data.places.Gazetteer
import com.navaneeth.filer.data.places.PlaceQuery
import com.navaneeth.filer.ui.model.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Parsing with the real bundled place list, as the app does. */
class QueryParserPlacesTest {
    private val gazetteer = File("src/main/assets/places.tsv").inputStream().use(Gazetteer::parse)
    private val today = LocalDate.of(2026, 10, 24)
    private fun parse(q: String) = QueryParser.parse(q, gazetteer, today)

    @Test fun everyExampleParses() {
        listOf(
            "pdf files downloaded 2 days ago", "photos from Goa", "photos from Goa last December", "paris photos",
            "whatsapp images this week", "large videos", "invoice from whatsapp last week", "receipt for my scooter",
            "car insurance renewal", "photos of my car", "notes about system architecture", "pics in new york",
            "photos from holiday", "selfies taken in kerala in 2024", "a", "in", "from", "photos from", "  "
        ).forEach { parse(it) }
    }

    @Test fun places() {
        val goa = parse("photos from Goa last December")
        assertTrue(goa.place is PlaceQuery.Region)
        assertEquals("Goa", goa.place!!.label)
        assertTrue(FileCategory.IMAGES in goa.categories)
        assertTrue(goa.date != null)
        assertEquals("New York City", parse("pics in new york city").place?.label)
        assertTrue(parse("paris photos").place is PlaceQuery.City)
        assertNull(parse("photos from holiday").place)
        assertTrue(parse("pdf files downloaded 2 days ago").place == null)
    }

    @Test fun resolve() {
        assertEquals("Goa", gazetteer.resolve(15.4909, 73.8278)?.region)
        assertEquals("India", gazetteer.resolve(15.4909, 73.8278)?.country)
    }
}
