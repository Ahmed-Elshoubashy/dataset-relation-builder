package com.dubsof.graph.dataset;

import com.dubsof.graph.resolve.NameMatcher;

/**
 * What the pipeline knows about the dataset it is analysing, decided once per analysis and passed to
 * every stage: its owner and its conventions (profile). Each analysis has its own, so two analyses
 * never share settings.
 */
public final class Dataset {

    public final Owner owner;
    public final Profile profile;
    /** Company names compared with the profile's legal suffixes and free e-mail providers. */
    public final NameMatcher names;

    public Dataset(Owner owner, Profile profile) {
        this.owner = owner;
        this.profile = profile;
        this.names = profile.names;
    }

    /** Nothing known: no owner, the default profile. */
    public static Dataset unknown() {
        return new Dataset(Owner.NONE, Profile.defaults());
    }
}
