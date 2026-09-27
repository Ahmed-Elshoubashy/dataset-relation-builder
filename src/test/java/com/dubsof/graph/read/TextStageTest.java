package com.dubsof.graph.read;

import com.dubsof.graph.ingest.FileKind;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Native text reading for each file kind; files without text ask for OCR. */
class TextStageTest {

    @Test
    void pdfWithATextLayer() throws Exception {
        String text = TextStage.readNative(FileKind.PDF, pdf("INVOICE No: INV-8034"));
        assertTrue(text.contains("INVOICE No: INV-8034"));
    }

    @Test
    void pdfWithoutTextNeedsOcr() throws Exception {
        byte[] scan = pdf(null);
        assertThrows(TextStage.NeedsOcr.class, () -> TextStage.readNative(FileKind.PDF, scan));
    }

    @Test
    void imagesNeedOcr() {
        assertThrows(TextStage.NeedsOcr.class, () -> TextStage.readNative(FileKind.PNG, new byte[] {1, 2, 3}));
        assertThrows(TextStage.NeedsOcr.class, () -> TextStage.readNative(FileKind.JPG, new byte[] {1, 2, 3}));
    }

    @Test
    void wordParagraphsAndTableRows() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        doc.createParagraph().createRun().setText("Dear Thomas,");
        XWPFTable table = doc.createTable(1, 2);
        table.getRow(0).getCell(0).setText("Case Sealer CS-110");
        table.getRow(0).getCell(1).setText("£3,344.00");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.write(out);
        doc.close();

        String text = TextStage.readNative(FileKind.DOCX, out.toByteArray());
        assertTrue(text.contains("Dear Thomas,"));
        assertTrue(text.contains("Case Sealer CS-110 | £3,344.00"));   // table cells joined with " | "
    }

    @Test
    void spreadsheetSheetsAndRows() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet("Prices");
        XSSFRow header = sheet.createRow(0);
        header.createCell(0).setCellValue("Item");
        header.createCell(1).setCellValue("Price");
        XSSFRow row = sheet.createRow(1);
        row.createCell(0).setCellValue("HL-6200");
        row.createCell(1).setCellValue(12500);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();

        String text = TextStage.readNative(FileKind.XLSX, out.toByteArray());
        assertTrue(text.contains("# sheet: Prices"));
        assertTrue(text.contains("Item | Price"));
        assertTrue(text.contains("HL-6200 | 12500"));   // whole numbers without ".0"
    }

    @Test
    void plainTextInUtf8OrLatin1() throws Exception {
        assertEquals("Total £10", TextStage.readNative(FileKind.TEXT, "Total £10".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Total £10", TextStage.readNative(FileKind.TEXT, "Total £10".getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void emailHeadersAndBody() throws Exception {
        String eml = "From: Sam Jones <sam@acmecorp.com>\r\nTo: thomas@meridianpackaging.co.uk\r\n"
                + "Subject: Site visit\r\nContent-Type: text/plain\r\n\r\nSee you on Monday.\r\n";
        String text = TextStage.readNative(FileKind.EML, eml.getBytes(StandardCharsets.UTF_8));
        assertTrue(text.startsWith("From: Sam Jones <sam@acmecorp.com>\n"));
        assertTrue(text.contains("Subject: Site visit\n"));
        assertTrue(text.contains("See you on Monday."));
    }

    /** A one-page PDF with this text, or with no text at all (like a scan) when {@code text} is null. */
    private static byte[] pdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            if (text != null) {
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
