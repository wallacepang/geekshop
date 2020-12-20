/*
 * Copyright (c) 2020 掘艺网络(jueyi.co).
 * All rights reserved.
 */

package co.jueyi.geekshop.types.order;

import co.jueyi.geekshop.types.common.BooleanOperators;
import co.jueyi.geekshop.types.common.DateOperators;
import co.jueyi.geekshop.types.common.NumberOperators;
import co.jueyi.geekshop.types.common.StringOperators;
import lombok.Data;

/**
 * Created on Dec, 2020 by @author bobo
 */
@Data
public class OrderFilterParameter {
    private DateOperators createdAt;
    private DateOperators updatedAt;
    private StringOperators code;
    private StringOperators state;
    private BooleanOperators active;
    private NumberOperators subTotal;
    private NumberOperators total;
    private NumberOperators shipping;
}
