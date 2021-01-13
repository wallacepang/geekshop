/*
 * Copyright (c) 2020 GeekShop.
 * All rights reserved.
 */

package io.geekshop.service.helpers.payment_state_machine;

/**
 * These are the default states of the payment process.
 *
 * Created on Dec, 2020 by @author bobo
 */
public enum PaymentState {
    Created,
    Authorized,
    Settled,
    Declined,
    Error
}
