package com.dubsof.graph;

import com.dubsof.graph.db.Db;
import com.dubsof.graph.pipeline.Pipeline;
import com.dubsof.graph.read.OcrBackend;
import com.dubsof.graph.resolve.NameMatcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression test on the real john-doe sample: builds it twice, offline, with its profile and with none,
 * and checks the results known to be right. Slow (a few minutes), so it only runs with
 * {@code ./gradlew datasetTest}, and only when the dataset is there (../john-doe, ../../john-doe or ERKG_DATA_ROOT).
 */
@Tag("dataset")
class JohnDoeDatasetTest {

    /** The companies of john-doe: the owner, the 12 customers, an insurer and a training provider. */
    private static final int COMPANIES = 15;

    @TempDir
    static Path work;
    static Connection withProfile;
    static Connection withoutProfile;

    @BeforeAll
    static void build() throws Exception {
        File dataset = dataset();
        assumeTrue(dataset != null, "john-doe sample not found");
        withProfile = build(dataset, "john-doe.json", "with");
        withoutProfile = build(dataset, "none", "without");
    }

    @AfterAll
    static void close() throws Exception {
        for (Connection conn : new Connection[] {withProfile, withoutProfile}) {
            if (conn != null) {
                conn.close();
            }
        }
    }

    // ---------------------------------------------------------------- with profiles/john-doe.json

    @Test
    void withProfileTwelveCustomersPlusOwner() throws Exception {
        assertEquals(12, Db.number(withProfile, "SELECT COUNT(*) FROM entities WHERE etype = 'company' AND json_extract(attrs, '$.role') = 'customer'"));
        assertEquals(1, Db.number(withProfile, "SELECT COUNT(*) FROM entities WHERE etype = 'company' AND json_extract(attrs, '$.role') = 'owner'"));
        assertEquals(COMPANIES, companies(withProfile).size());
    }

    @Test
    void withProfileOneProjectPerJobFolder() throws Exception {
        assertEquals(38, Db.number(withProfile, "SELECT COUNT(*) FROM entities WHERE etype = 'project' AND json_extract(attrs, '$.source') = 'folder'"));
        assertEquals(38, Db.number(withProfile, "SELECT COUNT(*) FROM entities WHERE etype = 'project'"));
    }

    @Test
    void withProfileDocuments() throws Exception {
        assertTrue(Db.number(withProfile, "SELECT json_array_length(attrs, '$.files') FROM entities WHERE etype = 'document' AND key = 'QUO-5238'") >= 3);
        assertEquals(2, Db.number(withProfile, "SELECT COUNT(*) FROM entities WHERE key LIKE 'DWG-9296%'"));
    }

    @Test
    void withProfileEveryCompanyMentionResolved() throws Exception {
        assertEquals(0, unresolvedCompanyMentions(withProfile));
    }

    // ---------------------------------------------------------------- with no profile

    @Test
    void withoutProfileTheSameCompanies() throws Exception {
        // the same companies, named by the folder spelling or the most common one ("Vantage Electronics Inc",
        // not a first-seen typo); compared by company key, as legal suffixes and case may differ
        assertEquals(companyKeys(withProfile), companyKeys(withoutProfile));
    }

    @Test
    void withoutProfileNoDomainCompanies() throws Exception {
        assertEquals(0, Db.number(withoutProfile, "SELECT COUNT(*) FROM entities WHERE etype = 'company' AND key LIKE 'domain:%'"));
    }

    @Test
    void withoutProfileEveryCompanyMentionResolved() throws Exception {
        assertEquals(0, unresolvedCompanyMentions(withoutProfile));
    }

    @Test
    void withoutProfileProjectsComeFromTitlesInSeveralFiles() throws Exception {
        assertTrue(Db.number(withoutProfile, "SELECT COUNT(*) FROM entities WHERE etype = 'project'") > 0);
        assertEquals(0, Db.number(withoutProfile, "SELECT COUNT(*) FROM entities e WHERE etype = 'project'"
                + " AND (SELECT COUNT(DISTINCT file_id) FROM mentions m WHERE m.entity_id = e.id) < 2"));
    }

    @Test
    void withoutProfileDocuments() throws Exception {
        assertTrue(Db.number(withoutProfile, "SELECT json_array_length(attrs, '$.files') FROM entities WHERE etype = 'document' AND key = 'QUO-5238'") >= 3);
    }

    // ----------------------------------------------------------------

    private static Connection build(File dataset, String profile, String name) throws Exception {
        Pipeline.Options options = new Pipeline.Options();
        options.ocr = OcrBackend.NONE;
        options.profile = profile;
        Pipeline.Result result = Pipeline.build(dataset, work.resolve(name).resolve("graph.db").toFile(), options, (step, stage, detail) -> { });
        return Db.open(result.built, true);
    }

    private static File dataset() {
        String fromEnv = System.getenv("ERKG_DATA_ROOT");
        for (String path : new String[] {fromEnv, "../john-doe", "../../john-doe"}) {
            if (path != null && new File(path, "Customers").isDirectory()) {
                return new File(path);
            }
        }
        return null;
    }

    private static List<String> companies(Connection conn) throws Exception {
        return Db.list(conn, "SELECT name FROM entities WHERE etype = 'company' ORDER BY name", rs -> rs.getString(1));
    }

    private static TreeSet<String> companyKeys(Connection conn) throws Exception {
        NameMatcher names = TestGraph.JOHN_DOE.names;
        TreeSet<String> keys = new TreeSet<>();
        for (String name : companies(conn)) {
            keys.add(names.companyKey(name));
        }
        return keys;
    }

    private static long unresolvedCompanyMentions(Connection conn) throws Exception {
        return Db.number(conn, "SELECT COUNT(*) FROM mentions WHERE etype = 'company' AND entity_id IS NULL AND method != 'generic_domain'");
    }
}
