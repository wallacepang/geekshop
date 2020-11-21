/*
 * Copyright (c) 2020 掘艺网络(jueyi.co).
 * All rights reserved.
 */

package co.jueyi.geekshop.types.product;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Created on Nov, 2020 by @author bobo
 */
@Data
public class CreateProductInput {
    private Long featuredAssetId;
    private List<Long> assetIds = new ArrayList<>();
    private List<Long> facetValueIds = new ArrayList<>();
    private String name;
    private String slug;
    private String description;
}