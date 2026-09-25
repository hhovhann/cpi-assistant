package com.hhovhann.cpiassistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim splitter behind CitationEvaluation. Pure string logic, so it runs
 * in the normal suite. The inputs are shapes Llama really produced.
 */
class CitationClaimsTest {

    private static List<CitationEvaluation.Claim> claims(String answer) {
        return CitationEvaluation.claims(answer);
    }

    @Test
    void leadingBlanketCitationStaysWithItsSentence() {
        assertThat(claims("[1, 2] You can use the JDBC Adapter by adding a receiver channel."))
                .containsExactly(new CitationEvaluation.Claim(
                        "You can use the JDBC Adapter by adding a receiver channel.", List.of(1, 2)));
    }

    @Test
    void markersAfterTheFullStopBelongToThatSentence() {
        assertThat(claims("Add an Exception Subprocess to the flow. [1] Then send the alert."))
                .containsExactly(
                        new CitationEvaluation.Claim("Add an Exception Subprocess to the flow.", List.of(1)),
                        new CitationEvaluation.Claim("Then send the alert.", List.of()));
    }

    @Test
    void aCitedFileNameAloneIsABareCitation() {
        List<CitationEvaluation.Claim> claims = claims("[2] 08-as2-adapter-b2b.txt\nMDN is an acknowledgment message.");
        assertThat(claims.getFirst().cited()).containsExactly(2);
        assertThat(claims.getFirst().bare()).isTrue();
    }

    @Test
    void listMarkersAreStrippedButLeadingNumbersInTextAreKept() {
        assertThat(claims("- The Exception Subprocess catches the error [2]\n1. Add a receiver channel [1]\n30 lines or fewer suit a Script step [1]"))
                .extracting(CitationEvaluation.Claim::text)
                .containsExactly("The Exception Subprocess catches the error",
                        "Add a receiver channel",
                        "30 lines or fewer suit a Script step");
    }

    @Test
    void aSentenceIntroducingAListIsALeadIn() {
        CitationEvaluation.Claim claim = claims("Based on the provided context [1, 2], here are the steps:").getFirst();
        assertThat(claim.leadIn()).isTrue();
        assertThat(claim.bare()).isFalse();
    }

    @Test
    void repeatedAndGroupedNumbersAreCollectedOnce() {
        assertThat(claims("Both apply [1][2] and again [1, 2].").getFirst().cited()).containsExactly(1, 2);
    }
}
