package io.github.sahajm99.innkeeper.model;

/** How a payment was taken; matches ck_payment_method. No card data is stored. */
public enum PaymentMethod {
    CARD,
    CASH
}
