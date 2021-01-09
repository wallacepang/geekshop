/*
 * Copyright (c) 2020 GeekXYZ.
 * All rights reserved.
 */

package io.geekshop.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Created on Nov, 2020 by @author bobo
 */
@TableName(value = "tb_product_variant_facet_value_join")
@Data
@EqualsAndHashCode(callSuper = true)
public class ProductVariantFacetValueJoinEntity extends BaseEntity {
    private Long productVariantId;
    private Long facetValueId;
}
