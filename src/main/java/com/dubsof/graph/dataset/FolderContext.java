package com.dubsof.graph.dataset;

/**
 * What the folder a file sits in says about it: the customer company, the project (job) and the
 * kind of document. Every field can be null; with no profile only {@code area} and {@code category} are set.
 */
public final class FolderContext {

    /** The top folder ("Admin", "Sales"). */
    public String area;
    /** The customer the folder belongs to. */
    public String company;
    /** The project folder's name as it is on disk (kept whole: some document keys include it). */
    public String job;
    /** The project's id (e.g. a job number), or its title when the layout has no id. */
    public String jobId;
    /** The project's title, or null. */
    public String jobTitle;
    /** The kind of document the folder holds ("Invoices", "Drawings"). */
    public String category;
}
