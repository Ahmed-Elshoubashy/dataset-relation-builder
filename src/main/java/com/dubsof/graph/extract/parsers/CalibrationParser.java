package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.Extraction;

import static com.dubsof.graph.extract.parsers.ParserUtils.document;
import static com.dubsof.graph.extract.parsers.ParserUtils.field;
import static com.dubsof.graph.extract.parsers.ParserUtils.personWithOrg;
import static com.dubsof.graph.extract.parsers.ParserUtils.owner;

/** Calibration certificates (Certificate No: CAL-…) and the technician. */
public class CalibrationParser implements Parser {

    public boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project) {
        String no = field(text, "Certificate No", "(CAL-\\d+)");
        if (no == null) {
            return false;
        }
        Integer doc = document(ex, row, "calibration_cert", no, no,
                "instrument", field(text, "Instrument", "(.+)"), "serial", field(text, "Serial No", "(.+)"),
                "date", field(text, "Calibration Date", "(.+)"), "result", field(text, "Result", "(.+)"));
        String tech = field(text, "Technician", "(.+)");
        if (tech != null) {
            ex.fact(personWithOrg(ex, tech, "technician", owner(ex)), "AUTHORED", doc);
        }
        return true;
    }
}
