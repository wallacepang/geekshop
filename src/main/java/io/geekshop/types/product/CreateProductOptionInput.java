/*
 * Copyright (c) 2020 GeekXYZ.
 * All rights reserved.
 */

package io.geekshop.types.product;

import lombok.Data;

/**
 * Created on Nov, 2020 by @author bobo
 */
@Data
public class CreateProductOptionInput {
    private Long productOptionGroupId;
    private String code;
    private String name;
}
