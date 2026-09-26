package com.dubsof.graph.resolve;

import com.dubsof.graph.extract.EntityType;

/** Never merges a borderline pair; the Resolver logs it as a possible alias instead. */
public class RuleAdjudicator implements Adjudicator {

    public Verdict sameEntity(EntityType etype, String mention, String candidate, String context) {
        return new Verdict(false, 0.0, "below auto-merge threshold");
    }
}
