package com.dubsof.graph.pipeline;

import com.dubsof.graph.dataset.DirectoryPattern;
import com.dubsof.graph.dataset.FolderPattern;
import com.dubsof.graph.dataset.Owner;
import com.dubsof.graph.dataset.Profile;
import com.dubsof.graph.read.OcrBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the owner comes from (Pipeline.chooseOwner), and when OwnerDetector finds none. */
class OwnerTest {

    private static final Owner DETECTED = new Owner("Foo Corp", "foo.com", "domain on 80% of e-mails (40)");

    @Test
    void theDialogOwnerBeatsEverything() {
        Pipeline.Options options = options("Bar Inc", "Server Ltd");
        assertEquals("Bar Inc", Pipeline.chooseOwner(options, profile("Dataset GmbH", true), DETECTED).name);
    }

    @Test
    void theDatasetsOwnProfileBeatsTheServerSetting() {
        Owner owner = Pipeline.chooseOwner(options(null, "Server Ltd"), profile("Dataset GmbH", true), DETECTED);
        assertEquals("Dataset GmbH", owner.name);
        assertEquals("from the dataset's profile.json", owner.reason);
    }

    @Test
    void theServerSettingBeatsAChosenOrSuggestedProfile() {
        assertEquals("Server Ltd", Pipeline.chooseOwner(options(null, "Server Ltd"), profile("Shipped GmbH", false), DETECTED).name);
        assertEquals("Shipped GmbH", Pipeline.chooseOwner(options(null, null), profile("Shipped GmbH", false), DETECTED).name);
    }

    @Test
    void withNothingGivenTheDetectedOwnerIsUsed() {
        assertEquals(DETECTED, Pipeline.chooseOwner(options(null, null), Profile.defaults(), DETECTED));
    }

    @Test
    void aGivenOwnerOnlyKeepsADomainThatMatchesIt() {
        // "Bar Inc" has nothing to do with the detected foo.com
        Owner bar = Pipeline.chooseOwner(options("Bar Inc", null), Profile.defaults(), DETECTED);
        assertNull(bar.domain);
        // "Foo Corporation" does
        assertEquals("foo.com", Pipeline.chooseOwner(options("Foo Corporation", null), Profile.defaults(), DETECTED).domain);
        // ERKG_OWNER_DOMAIN goes with ERKG_OWNER only
        Pipeline.Options server = options(null, "Server Ltd");
        server.serverOwnerDomain = "server.example";
        assertEquals("server.example", Pipeline.chooseOwner(server, Profile.defaults(), DETECTED).domain);
        server.owner = "Bar Inc";
        assertNull(Pipeline.chooseOwner(server, Profile.defaults(), DETECTED).domain);
    }

    @Test
    void aFewVendorEmailsGiveNoOwner(@TempDir Path dir) throws Exception {
        Path data = Files.createDirectories(dir.resolve("data"));
        for (int i = 1; i <= 2; i++) {
            Files.write(data.resolve("offer" + i + ".eml"), ("From: Sam Vendor <sam@vendor-supplies.com>\n"
                    + "To: Me <me@gmail.com>\nSubject: Offer " + i + "\n\nHello,\n\nour offer is attached.\n\n"
                    + "Regards,\nSam Vendor\nSales, Vendor Supplies Ltd\n").getBytes("UTF-8"));
        }
        Pipeline.Options options = options(null, null);
        options.ocr = OcrBackend.NONE;
        Pipeline.Result result = Pipeline.build(data.toFile(), dir.resolve("graph.db").toFile(), options, (step, stage, detail) -> { });
        assertFalse(result.owner.isKnown(), String.valueOf(result.owner.name));
        assertTrue(result.owner.reason.contains("vendor-supplies.com on 2"), result.owner.reason);
    }

    private static Pipeline.Options options(String dialogOwner, String serverOwner) {
        Pipeline.Options options = new Pipeline.Options();
        options.owner = dialogOwner;
        options.serverOwner = serverOwner;
        options.serverOwnerDomain = null;
        return options;
    }

    private static Profile profile(String owner, boolean fromDataset) {
        return new Profile("test", fromDataset, new ArrayList<FolderPattern>(), new ArrayList<DirectoryPattern>(), null, owner, null,
                Profile.defaults().names, new LinkedHashMap<String, String>());
    }
}
