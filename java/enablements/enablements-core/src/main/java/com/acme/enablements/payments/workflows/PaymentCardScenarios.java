package com.acme.enablements.payments.workflows;

import java.util.Map;

/**
 * Deterministic authorize/capture outcome lookup keyed by card number, reusing
 * Stripe's published test-card values as local magic values. Any number not
 * in the table authorizes and captures normally.
 */
public final class PaymentCardScenarios {

    public enum Outcome {
        AUTHORIZED,
        DECLINED,
        INSUFFICIENT_FUNDS,
        EXPIRED_CARD,
        CAPTURE_FAILS
    }

    public record CardOutcome(Outcome outcome, String declineReason) {
        public boolean isDeclinedAtAuthorization() {
            return outcome == Outcome.DECLINED || outcome == Outcome.INSUFFICIENT_FUNDS || outcome == Outcome.EXPIRED_CARD;
        }
    }

    private static final CardOutcome AUTHORIZED = new CardOutcome(Outcome.AUTHORIZED, "");

    private static final Map<String, CardOutcome> TABLE = Map.of(
            "4000000000000002", new CardOutcome(Outcome.DECLINED, "card_declined"),
            "4000000000009995", new CardOutcome(Outcome.INSUFFICIENT_FUNDS, "insufficient_funds"),
            "4000000000000069", new CardOutcome(Outcome.EXPIRED_CARD, "expired_card"),
            "4000000000000259", new CardOutcome(Outcome.CAPTURE_FAILS, ""));

    private PaymentCardScenarios() {
    }

    public static CardOutcome lookup(String cardNumber) {
        return TABLE.getOrDefault(cardNumber, AUTHORIZED);
    }
}
