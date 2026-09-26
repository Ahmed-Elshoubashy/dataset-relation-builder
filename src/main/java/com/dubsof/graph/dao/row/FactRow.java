package com.dubsof.graph.dao.row;

/** One row of the {@code facts} table: "mention src REL mention dst" within one file. */
public class FactRow {
    public long id;
    public long fileId;
    public long src;
    public String rel;
    public long dst;
}
