package com.acme.enablements.payments.workflows;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCardScenariosTest {

    @Test
    void unrecognizedCardAuthorizes() {
        var outcome = PaymentCardScenarios.lookup("4111111111111111");
        assertThat(outcome.outcome()).isEqualTo(PaymentCardScenarios.Outcome.AUTHORIZED);
        assertThat(outcome.isDeclinedAtAuthorization()).isFalse();
    }

    @Test
    void declinedCard() {
        var outcome = PaymentCardScenarios.lookup("4000000000000002");
        assertThat(outcome.outcome()).isEqualTo(PaymentCardScenarios.Outcome.DECLINED);
        assertThat(outcome.isDeclinedAtAuthorization()).isTrue();
    }

    @Test
    void insufficientFundsCard() {
        var outcome = PaymentCardScenarios.lookup("4000000000009995");
        assertThat(outcome.outcome()).isEqualTo(PaymentCardScenarios.Outcome.INSUFFICIENT_FUNDS);
        assertThat(outcome.isDeclinedAtAuthorization()).isTrue();
    }

    @Test
    void expiredCard() {
        var outcome = PaymentCardScenarios.lookup("4000000000000069");
        assertThat(outcome.outcome()).isEqualTo(PaymentCardScenarios.Outcome.EXPIRED_CARD);
        assertThat(outcome.isDeclinedAtAuthorization()).isTrue();
    }

    @Test
    void captureFailsCardAuthorizesButIsNotDeclinedAtAuthorization() {
        var outcome = PaymentCardScenarios.lookup("4000000000000259");
        assertThat(outcome.outcome()).isEqualTo(PaymentCardScenarios.Outcome.CAPTURE_FAILS);
        assertThat(outcome.isDeclinedAtAuthorization()).isFalse();
    }
}
