package com.dubsof.graph.resolve;

import com.dubsof.graph.extract.EntityType;

/**
 * Second opinion on borderline matches (score between Config.COMPANY_MATCH_GRAY and COMPANY_MATCH_ACCEPT).
 * The default RuleAdjudicator keeps borderline pairs apart; ClaudeAdjudicator
 * (ERKG_ADJUDICATOR=claude) asks Claude.
 */
public interface Adjudicator {

    Verdict sameEntity(EntityType etype, String mention, String candidate, String context);

    /** The answer: same or not, how sure, and why. */
    class Verdict {
        public final boolean same;
        public final double confidence;
        public final String reason;

        public Verdict(boolean same, double confidence, String reason) {
            this.same = same;
            this.confidence = confidence;
            this.reason = reason;
        }
    }
}
