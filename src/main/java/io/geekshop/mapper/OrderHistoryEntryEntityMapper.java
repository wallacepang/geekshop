/*
 * Copyright (c) 2020 GeekShop.
 * All rights reserved.
 */

package io.geekshop.mapper;

import io.geekshop.entity.OrderHistoryEntryEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Created on Nov, 2020 by @author bobo
 */
@Mapper
public interface OrderHistoryEntryEntityMapper extends BaseMapper<OrderHistoryEntryEntity> {
}
