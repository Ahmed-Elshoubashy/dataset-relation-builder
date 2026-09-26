package com.dubsof.graph.dao.row;

import com.dubsof.graph.extract.RelationType;

/** One row of the {@code facts} table: "mention src REL mention dst" within one file. */
public class FactRow {
    public long id;
    public long fileId;
    public long src;
    public RelationType rel;
    public long dst;
}
