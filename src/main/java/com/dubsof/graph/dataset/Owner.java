package com.dubsof.graph.dataset;

/**
 * The organisation whose file share is being analysed. Its name is on most of its own documents
 * (letterheads, signatures), so the pipeline treats it specially: it is a known company from the start,
 * its staff are linked to it, and it is left out where it would link everything to everything.
 * Both fields are null when no owner was found or given.
 */
public final class Owner {

    /** No owner: every step that uses the owner skips it. */
    public static final Owner NONE = new Owner(null, null);

    /** "Harbor Robotics Inc"; or the domain when only a sender domain was found; null when unknown. */
    public final String name;
    /** Its main e-mail domain ("harborrobotics.com"), or null. */
    public final String domain;
    /** Where it came from, for the analysis log: "letterhead on 212 PDFs; ...", "from the Analyse dialog"; or why there is none. */
    public final String reason;

    public Owner(String name, String domain) {
        this(name, domain, null);
    }

    public Owner(String name, String domain, String reason) {
        this.name = name;
        this.domain = domain;
        this.reason = reason;
    }

    public boolean isKnown() {
        return name != null;
    }

    @Override
    public String toString() {
        return name == null ? "none" : domain == null ? name : name + " (" + domain + ")";
    }
}
