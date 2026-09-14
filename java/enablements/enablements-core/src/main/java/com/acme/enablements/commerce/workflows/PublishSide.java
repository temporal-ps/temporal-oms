package com.acme.enablements.commerce.workflows;

/** Which side of a PendingPublishEntry a delivery/mark-delivered call applies to. */
public enum PublishSide {
    AUTHORIZED,
    COMMERCE,
    PAYMENT
}
