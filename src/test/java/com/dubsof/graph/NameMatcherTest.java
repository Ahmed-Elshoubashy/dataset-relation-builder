package com.dubsof.graph;

import com.dubsof.graph.resolve.NameMatcher;
import com.dubsof.graph.resolve.NameMatcher.Match;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every variant below was observed in the john-doe dataset. */
class NameMatcherTest {

    private static final NameMatcher NAMES = TestGraph.JOHN_DOE.names;

    private static final String[] CUSTOMERS = {
        "Acme Corporation", "Ashcombe Confectionery Ltd", "Blenheim Foods Group Ltd", "Castlemead Logistics Ltd",
        "Falcon Aerospace Components Ltd", "Ironbridge Automotive Ltd", "Kingsley Textiles Ltd",
        "Northgate Brewing Co.", "Redwood Timber & Joinery", "Sterling Pharma Ltd", "Vantage Electronics Inc",
        "Whitmore Dairy Products Ltd", "Meridian Packaging Systems Ltd",
    };

    /** Best-scoring customer for a name: {customer, method, score}, or null. */
    private static Object[] best(String name, boolean truncated) {
        Object[] best = null;
        for (String c : CUSTOMERS) {
            Match m = NAMES.matchCompany(name, c, truncated);
            if (m != null && (best == null || m.score > (Double) best[2])) {
                best = new Object[] {c, m.methodName(), m.score};
            }
        }
        return best;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "ACME Corp                  | Acme Corporation                | normalized",
        "Acme                       | Acme Corporation                | normalized",
        "Vantage Electronics, Inc.  | Vantage Electronics Inc         | normalized",
        "Redwood Timber and Joinery | Redwood Timber & Joinery        | normalized",
        "Iron Bridge Automotive     | Ironbridge Automotive Ltd       | spacing",
        "Castle Mead Logistics      | Castlemead Logistics Ltd        | spacing",
        "Red Wood Timber Joinery    | Redwood Timber & Joinery        | spacing",
        "Castlemead Log.            | Castlemead Logistics Ltd        | abbreviation",
        "Ashcombe Confec.           | Ashcombe Confectionery Ltd      | abbreviation",
        "Falcon Aero Components Ltd | Falcon Aerospace Components Ltd | abbreviation",
        "Northgate Brew Co          | Northgate Brewing Co.           | abbreviation",
        "Sterling Pharm. Ltd        | Sterling Pharma Ltd             | abbreviation",
        "Sterling Pharmaceuticals   | Sterling Pharma Ltd             | expansion",
        "Kingsly Textiles           | Kingsley Textiles Ltd           | typo",
        "Ashcome Confectionery      | Ashcombe Confectionery Ltd      | typo",
        "Blenhiem Foods             | Blenheim Foods Group Ltd        | truncation+typo",
        "Witmore Dairy              | Whitmore Dairy Products Ltd     | truncation+typo",
        "Redwood Timber             | Redwood Timber & Joinery        | truncation",
        "BFG Ltd                    | Blenheim Foods Group Ltd        | acronym",
    })
    void companyVariants(String variant, String expected, String method) {
        Object[] b = best(variant, false);
        assertEquals(expected, b[0]);
        assertEquals(method, b[1]);
        assertTrue((Double) b[2] >= 0.8);
    }

    @ParameterizedTest
    @CsvSource({"Coventry Safety Training Ltd", "Blackfriars Assurance Ltd", "Acme Robotics"})
    void unrelatedOrganisationsDoNotMatch(String name) {
        Object[] b = best(name, false);
        assertTrue(b == null || (Double) b[2] < 0.8);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        // the known name is the shorter one: either way round is a truncation
        "Whitmore Dairy               | Whitmore Dairy Products Ltd",
        "Whitmore Dairy Products Ltd  | Whitmore Dairy",
        "Bayview Dental Supplies Inc  | Bayview Dental",
    })
    void truncationWorksBothWays(String mention, String candidate) {
        Match match = NAMES.matchCompany(mention, candidate, false);
        assertNotNull(match);
        assertEquals("truncation", match.methodName());
        assertTrue(match.score >= 0.8, match.toString());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        // one word is not enough to be sure: "Acme Robotics" is not simply more words about "Acme"
        "Acme Robotics             | Acme",
        // a different next word is a different company, whichever name is longer
        "Redwood Timber Supplies   | Redwood Timber & Joinery",
        "Redwood Joinery           | Redwood Timber",
    })
    void longerNameNeedsTheWholeShorterName(String mention, String candidate) {
        Match match = NAMES.matchCompany(mention, candidate, false);
        assertTrue(match == null || match.score < 0.8, String.valueOf(match));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "acmecorp.com            | Acme Corporation",
        "ashcombeconfec.co.uk    | Ashcombe Confectionery Ltd",
        "falconaero.co.uk        | Falcon Aerospace Components Ltd",
        "ironbridgeauto.co.uk    | Ironbridge Automotive Ltd",
        "meridianpackaging.co.uk | Meridian Packaging Systems Ltd",
    })
    void emailDomains(String domain, String expected) {
        String found = null;
        for (String c : CUSTOMERS) {
            if (NAMES.matchDomain(domain, c) != null) {
                found = c;
            }
        }
        assertEquals(expected, found);
    }

    @Test
    void genericDomainMatchesNothing() {
        for (String c : CUSTOMERS) {
            assertNull(NAMES.matchDomain("gmail.com", c));
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Falcon Aerospace C | Falcon Aerospace Components Ltd",
        "Redwood Timber & J | Redwood Timber & Joinery",
        "Whitmore Dairy Pro | Whitmore Dairy Products Ltd",
    })
    void truncatedFilenames(String cut, String expected) {
        assertEquals(expected, best(cut, true)[0]);
    }

    @Test
    void oneLetterPrefixNeedsTruncationFlag() {
        Object[] b = best("Falcon Aerospace C", false);
        assertTrue(b == null || (Double) b[2] < 0.8);
    }

    @Test
    void accentsAndUmlautsCompareEqualToTheirPlainSpelling() {
        assertEquals(NameMatcher.personKey("Jose Mueller"), NameMatcher.personKey("José Müller"));
        assertEquals(NAMES.companyKey("Mueller GmbH"), NAMES.companyKey("Müller GmbH"));
        assertEquals("normalized", NAMES.matchCompany("Mueller GmbH", "Müller GmbH", false).methodName());
        assertNotNull(NAMES.matchDomain("mueller-gmbh.de", "Müller GmbH"));
    }

    @Test
    void companyNamesInRunningText() {
        assertEquals(java.util.Arrays.asList("Mueller GmbH", "Harbor Robotics Inc"), NAMES.findCompanyNames(
                "Spoke with José Müller from Mueller GmbH. Harbor Robotics Inc will quote the upgrade."));
    }

    @Test
    void limitedToIsNotACompany() {
        assertEquals(Arrays.asList(), NAMES.findCompanyNames("WARRANTIES, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF"));
        assertEquals(Arrays.asList("Kestrel Foods Limited"), NAMES.findCompanyNames("Invoice to Kestrel Foods Limited today."));
    }

    @Test
    void sentenceStartIsDroppedWhenTheRestIsNamedElsewhere() {
        assertEquals(Arrays.asList("Harbor Robotics Inc", "Harbor Robotics Inc"),
                NAMES.findCompanyNames("Ask Harbor Robotics Inc for a quote. We have worked with Harbor Robotics Inc before."));
        // alone, the first word may be part of the name: kept
        assertEquals(Arrays.asList("Harbor Robotics Inc"), NAMES.findCompanyNames("Harbor Robotics Inc will quote the cell."));
        assertEquals(Arrays.asList("Ask Harbor Robotics Inc"), NAMES.findCompanyNames("Ask Harbor Robotics Inc for a quote."));
    }

    @Test
    void foldedPersonKeysMatchEverySpellingOfAnUmlaut() {
        String folded = NameMatcher.personKeyFolded(NameMatcher.personKey("José Müller"));
        assertEquals(folded, NameMatcher.personKeyFolded(NameMatcher.personKey("Jose Mueller")));
        assertEquals(folded, NameMatcher.personKeyFolded(NameMatcher.personKey("Jose Muller")));
    }
}
