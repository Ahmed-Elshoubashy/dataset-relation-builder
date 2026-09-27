package com.dubsof.graph.dataset;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** skipDirectories patterns, written like .gitignore lines. */
class DirectoryPatternTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        // "/name": that folder at the root only
        "/Software          | Software/tools/build.sh                 | true",
        "/Software          | Software/readme.md                      | true",
        "/Software          | Admin/Software/notes.txt                | false",
        "/Software          | Software.pdf                            | false",
        "/Software          | SoftwareLicences/list.xlsx              | false",
        "/Software/         | Software/readme.md                      | true",
        "/Software          | Software/tools.zip::a/b.pdf             | true",
        // a bare name: a folder with that name at any depth
        "node_modules       | node_modules/x/index.js                 | true",
        "node_modules       | web/app/node_modules/x/index.js         | true",
        "node_modules       | web/app/my_node_modules/index.js        | false",
        // a path with '/': from the root, * inside one folder name
        "Customers/*/Archive | Customers/Acme/Archive/INV-1.pdf       | true",
        "Customers/*/Archive | Customers/Acme/2023/Archive/INV-1.pdf  | false",
        "Customers/*/Archive | Archive/INV-1.pdf                      | false",
        // ** any number of folders
        "**/backup          | backup/a.pdf                            | true",
        "**/backup          | Admin/old/backup/a.pdf                  | true",
        "**/backup          | Admin/notbackup/a.pdf                   | false",
        "Old*               | Admin/Old 2019/a.pdf                    | true",
        "Old?               | Admin/Old1/a.pdf                        | true",
        "Old?               | Admin/Old12/a.pdf                       | false",
    })
    void matches(String pattern, String path, boolean skipped) {
        assertEquals(skipped, new DirectoryPattern(pattern).matches(path), pattern + " on " + path);
    }
}
