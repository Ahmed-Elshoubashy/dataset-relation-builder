package com.dubsof.graph.dataset;

import com.dubsof.graph.TestGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileTest {

    private static final Profile JOHN_DOE = TestGraph.johnDoeProfile();

    @Test
    void johnDoeJobFolderWithCategory() {
        FolderContext ctx = JOHN_DOE.folderContext(
                "Customers/Acme Corporation/JOB-2023-0003 Shrink Wrap Retrofit/Invoices/INV-8034.pdf");
        assertEquals("Customers", ctx.area);
        assertEquals("Acme Corporation", ctx.company);
        assertEquals("JOB-2023-0003 Shrink Wrap Retrofit", ctx.job);
        assertEquals("JOB-2023-0003", ctx.jobId);
        assertEquals("Shrink Wrap Retrofit", ctx.jobTitle);
        assertEquals("Invoices", ctx.category);
    }

    @Test
    void johnDoeFileDirectlyInAJobFolderHasNoCategory() {
        FolderContext ctx = JOHN_DOE.folderContext("Customers/Acme Corporation/JOB-2023-0003 Shrink Wrap Retrofit/notes.txt");
        assertEquals("JOB-2023-0003", ctx.jobId);
        assertNull(ctx.category);
    }

    @Test
    void johnDoeCustomerFolderWithoutAJob() {
        FolderContext ctx = JOHN_DOE.folderContext("Customers/Acme Corporation/Contracts/MSA.pdf");
        assertEquals("Acme Corporation", ctx.company);
        assertNull(ctx.jobId);
        assertNull(ctx.category);
    }

    @Test
    void pathsNoPatternMatchesOnlyGetTheirTopFolders() {
        FolderContext ctx = JOHN_DOE.folderContext("Admin/Scans/scan_001.png");
        assertEquals("Admin", ctx.area);
        assertEquals("Scans", ctx.category);
        assertNull(ctx.company);
        assertNull(ctx.jobId);
    }

    @Test
    void jobIdsInTextFollowTheFolderPattern() {
        assertTrue(JOHN_DOE.jobIdPattern.matcher("see JOB-2024-0001 for details").find());
        assertTrue(!JOHN_DOE.jobIdPattern.matcher("see JOB-24-1 for details").find());
    }

    @Test
    void defaultsHaveNoFolderHintsAndBroadNameLists() {
        Profile defaults = Profile.defaults();
        FolderContext ctx = defaults.folderContext("Customers/Acme Corporation/JOB-2023-0003 Shrink Wrap Retrofit/Invoices/x.pdf");
        assertNull(ctx.company);
        assertNull(ctx.jobId);
        assertNull(defaults.jobIdPattern);
        assertTrue(defaults.names.isGenericDomain("gmx.de"));
        assertEquals("muller", defaults.names.companyKey("Muller AG"));
    }

    @Test
    void anotherLayoutWithoutJobIds(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        File file = dir.resolve("profile.json").toFile();
        Files.write(file.toPath(), Arrays.asList(
                "{\"folderPatterns\": [\"Kunden/{company}/Projekte/{title}/**\"], \"owner\": \"Harbor Robotics Inc\"}"));
        Profile profile = Profile.read(file);
        FolderContext ctx = profile.folderContext("Kunden/Müller GmbH/Projekte/Palettierzelle/Angebot.pdf");
        assertEquals("Müller GmbH", ctx.company);
        assertEquals("Palettierzelle", ctx.jobId);     // no id in this layout: the title identifies the project
        assertEquals("Palettierzelle", ctx.job);
        assertEquals("Harbor Robotics Inc", profile.owner);
        assertNull(profile.jobIdPattern);
    }

    // ---------------------------------------------------------------- which profile a dataset gets

    @Test
    void datasetsOwnProfileWinsOverTheDialogChoice(@TempDir Path dir) throws Exception {
        File own = dir.resolve("profile.json").toFile();
        Files.write(own.toPath(), "{\"folderPatterns\": [\"Clients/{company}/**\"]}".getBytes("UTF-8"));
        assertEquals(own.getPath(), Profile.forDataset(dir.toFile(), "john-doe.json").source);
    }

    @Test
    void dialogChoiceIsUsedWithoutAProfileInTheDataset(@TempDir Path dir) throws Exception {
        assertTrue(Profile.forDataset(dir.toFile(), "john-doe.json").source.endsWith("john-doe.json"));
        assertEquals("defaults", Profile.forDataset(dir.toFile(), Profile.NONE).source);
    }

    @Test
    void onlyShippedProfilesCanBeChosen(@TempDir Path dir) {
        assertTrue(Profile.available().contains("john-doe.json"));
        assertThrows(IllegalArgumentException.class, () -> Profile.forDataset(dir.toFile(), "../build.gradle"));
    }

    @Test
    void shippedProfileIsSuggestedWhenItsFoldersMatch(@TempDir Path dir) throws Exception {
        for (String path : Arrays.asList("Customers/Acme Corporation/JOB-2023-0003 Shrink Wrap Retrofit/Invoices/INV-8034.pdf",
                "Customers/Acme Corporation/Contracts/MSA.pdf", "Admin/policy.docx")) {
            File file = dir.resolve(path).toFile();
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), new byte[] {1});
        }
        Profile suggested = Profile.suggest(dir.toFile());
        assertTrue(suggested.source.contains("john-doe.json"), suggested.source);
        assertTrue(suggested.source.contains("67%"), suggested.source);
    }

    @Test
    void nothingIsSuggestedForAnotherLayout() throws Exception {
        File generic = new File("src/test/resources/datasets/generic");
        assertNull(Profile.suggest(generic));
    }
}
