/*
 * Copyright (c) 2020 掘艺网络(jueyi.co).
 * All rights reserved.
 */

package co.jueyi.geekshop.entity;

import co.jueyi.geekshop.custom.mybatis_plus.ConfigArgListTypeHandler;
import co.jueyi.geekshop.types.common.ConfigArg;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * A PaymentMethod is created automatically according to the configured {@link PaymentMethodHandler}s defined in the
 * {@link PaymentOptions} config.
 *
 * Created on Dec, 2020 by @author bobo
 */
@TableName(value = "tb_payment_method", autoResultMap = true)
@Data
@EqualsAndHashCode(callSuper = true)
public class PaymentMethodEntity extends BaseEntity {
    private String code;
    private boolean enabled;
    @TableField(typeHandler = ConfigArgListTypeHandler.class)
    private List<ConfigArg> configArgs = new ArrayList<>();
}