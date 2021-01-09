/*
 * Copyright (c) 2020 GeekXYZ.
 * All rights reserved.
 */

package io.geekshop.types.promotion;

import io.geekshop.types.common.ListOptions;
import lombok.Data;

/**
 * Created on Dec, 2020 by @author bobo
 */
@Data
public class PromotionListOptions implements ListOptions {
    private Integer currentPage;
    private Integer pageSize;
    private PromotionSortParameter sort;
    private PromotionFilterParameter filter;
}
