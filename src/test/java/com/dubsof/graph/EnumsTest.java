package com.dubsof.graph;

import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.MentionRole;
import com.dubsof.graph.extract.RelationType;
import com.dubsof.graph.ingest.FileKind;
import com.dubsof.graph.ingest.FileStatus;
import com.dubsof.graph.read.OcrBackend;
import com.dubsof.graph.read.TextSource;
import com.dubsof.graph.resolve.AdjudicatorType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The enums keep the exact text stored in the database and used by the API. These checks make sure every
 * value survives a write and a read, and that the texts the existing data uses have not changed.
 */
class EnumsTest {

    @Test
    void everyValueRoundTrips() {
        for (FileKind v : FileKind.values()) {
            assertEquals(v, FileKind.fromValue(v.value()));
        }
        for (FileStatus v : FileStatus.values()) {
            assertEquals(v, FileStatus.fromValue(v.value()));
        }
        for (TextSource v : TextSource.values()) {
            assertEquals(v, TextSource.fromValue(v.value()));
        }
        for (OcrBackend v : OcrBackend.values()) {
            assertEquals(v, OcrBackend.fromValue(v.value()));
        }
        for (EntityType v : EntityType.values()) {
            assertEquals(v, EntityType.fromValue(v.value()));
        }
        for (RelationType v : RelationType.values()) {
            assertEquals(v, RelationType.fromValue(v.value()));
        }
        for (MentionRole v : MentionRole.values()) {
            assertEquals(v, MentionRole.fromValue(v.value()));
        }
        for (AdjudicatorType v : AdjudicatorType.values()) {
            assertEquals(v, AdjudicatorType.fromValue(v.value()));
        }
    }

    @Test
    void storedTextsAreUnchanged() {
        assertEquals("needs_ocr", FileStatus.NEEDS_OCR.value());
        assertEquals("company", EntityType.COMPANY.value());
        assertEquals("ISSUED_TO", RelationType.ISSUED_TO.value());
        assertEquals("bill_to", MentionRole.BILL_TO.value());
        assertEquals("email_domain", MentionRole.EMAIL_DOMAIN.value());
        assertEquals("native", TextSource.NATIVE.value());
    }

    @Test
    void unknownTextIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> EntityType.fromValue("customer"));
        assertThrows(IllegalArgumentException.class, () -> OcrBackend.fromValue("bogus"));
        assertThrows(IllegalArgumentException.class, () -> RelationType.fromValue("OWNS"));
    }

    @Test
    void unreadFilesHaveNoTextSource() {
        assertNull(TextSource.fromValue(null));
    }

    @Test
    void ocrBackendsNameTheirTextSource() {
        assertEquals(TextSource.CLAUDE, OcrBackend.CLAUDE.textSource());
        assertEquals(TextSource.TESSERACT, OcrBackend.TESSERACT.textSource());
        assertNull(OcrBackend.NONE.textSource());
    }

    @Test
    void companyRolesAreRankedFolderFirstEmailDomainLast() {
        assertEquals(0, MentionRole.FOLDER.companyRank());
        assertTrue(MentionRole.BILL_TO.companyRank() < MentionRole.FILENAME.companyRank());
        assertTrue(MentionRole.FILENAME.companyRank() < MentionRole.EMAIL_DOMAIN.companyRank());
        // roles that never name a company come after every company role
        assertTrue(MentionRole.ATTN.companyRank() > MentionRole.EMAIL_DOMAIN.companyRank());
    }
}
