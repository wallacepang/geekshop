/*
 * Copyright (c) 2020 GeekXYZ.
 * All rights reserved.
 */

package io.geekshop.types.history;

/**
 * Created on Nov, 2020 by @author bobo
 */
public enum HistoryEntryType {
    CUSTOMER_REGISTERED,
    CUSTOMER_VERIFIED,
    CUSTOMER_DETAIL_UPDATED,
    CUSTOMER_ADDED_TO_GROUP,
    CUSTOMER_REMOVED_FROM_GROUP,
    CUSTOMER_ADDRESS_CREATED,
    CUSTOMER_ADDRESS_UPDATED,
    CUSTOMER_ADDRESS_DELETED,
    CUSTOMER_PASSWORD_UPDATED,
    CUSTOMER_PASSWORD_RESET_REQUESTED,
    CUSTOMER_PASSWORD_RESET_VERIFIED,
    CUSTOMER_EMAIL_UPDATE_REQUESTED,
    CUSTOMER_EMAIL_UPDATE_VERIFIED,
    CUSTOMER_NOTE,
    ORDER_STATE_TRANSITION,
    ORDER_PAYMENT_TRANSITION,
    ORDER_FULFILLMENT,
    ORDER_CANCELLATION,
    ORDER_REFUND_TRANSITION,
    ORDER_NOTE,
    ORDER_COUPON_APPLIED,
    ORDER_COUPON_REMOVED
}
