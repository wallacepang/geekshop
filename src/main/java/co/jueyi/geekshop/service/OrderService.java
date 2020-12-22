/*
 * Copyright (c) 2020 掘艺网络(jueyi.co).
 * All rights reserved.
 */

package co.jueyi.geekshop.service;

import co.jueyi.geekshop.common.RequestContext;
import co.jueyi.geekshop.common.utils.BeanMapper;
import co.jueyi.geekshop.config.order.OrderCodeGenerator;
import co.jueyi.geekshop.config.payment_method.SettlePaymentResult;
import co.jueyi.geekshop.entity.*;
import co.jueyi.geekshop.eventbus.events.OrderStateTransitionEvent;
import co.jueyi.geekshop.eventbus.events.PaymentStateTransitionEvent;
import co.jueyi.geekshop.eventbus.events.RefundStateTransitionEvent;
import co.jueyi.geekshop.exception.*;
import co.jueyi.geekshop.mapper.*;
import co.jueyi.geekshop.service.args.CreateOrderHistoryEntryArgs;
import co.jueyi.geekshop.service.args.UpdateOrderHistoryEntryArgs;
import co.jueyi.geekshop.service.helpers.OrderHelper;
import co.jueyi.geekshop.service.helpers.PageInfo;
import co.jueyi.geekshop.service.helpers.QueryHelper;
import co.jueyi.geekshop.service.helpers.ServiceHelper;
import co.jueyi.geekshop.service.helpers.order_calculator.OrderCalculator;
import co.jueyi.geekshop.service.helpers.order_merger.LineItem;
import co.jueyi.geekshop.service.helpers.order_merger.MergeResult;
import co.jueyi.geekshop.service.helpers.order_merger.OrderMerger;
import co.jueyi.geekshop.service.helpers.order_state_machine.OrderState;
import co.jueyi.geekshop.service.helpers.order_state_machine.OrderStateMachine;
import co.jueyi.geekshop.service.helpers.payment_state_machine.PaymentState;
import co.jueyi.geekshop.service.helpers.payment_state_machine.PaymentStateMachine;
import co.jueyi.geekshop.service.helpers.refund_state_machine.RefundState;
import co.jueyi.geekshop.service.helpers.refund_state_machine.RefundStateMachine;
import co.jueyi.geekshop.service.helpers.shipping_calculator.EligibleShippingMethod;
import co.jueyi.geekshop.service.helpers.shipping_calculator.ShippingCalculator;
import co.jueyi.geekshop.types.common.CreateAddressInput;
import co.jueyi.geekshop.types.common.DeletionResponse;
import co.jueyi.geekshop.types.common.DeletionResult;
import co.jueyi.geekshop.types.history.HistoryEntry;
import co.jueyi.geekshop.types.history.HistoryEntryType;
import co.jueyi.geekshop.types.order.*;
import co.jueyi.geekshop.types.payment.PaymentInput;
import co.jueyi.geekshop.types.settings.OrderProcessState;
import co.jueyi.geekshop.types.shipping.ShippingMethodQuote;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.EventBus;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Created on Dec, 2020 by @author bobo
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("Duplicates")
public class OrderService {

    private final OrderLineEntityMapper orderLineEntityMapper;
    private final OrderEntityMapper orderEntityMapper;
    private final OrderItemEntityMapper orderItemEntityMapper;
    private final PromotionEntityMapper promotionEntityMapper;
    private final ProductEntityMapper productEntityMapper;
    private final ProductVariantEntityMapper productVariantEntityMapper;
    private final RefundEntityMapper refundEntityMapper;
    private final PaymentEntityMapper paymentEntityMapper;
    private final FulfillmentEntityMapper fulfillmentEntityMapper;
    private final OrderPromotionJoinEntityMapper orderPromotionJoinEntityMapper;

    private final OrderCalculator orderCalculator;
    private final OrderMerger orderMerger;
    private final ShippingCalculator shippingCalculator;
    private final RefundStateMachine refundStateMachine;
    private final PaymentStateMachine paymentStateMachine;
    private final OrderStateMachine orderStateMachine;
    private final EventBus eventBus;
    private final ConfigService configService;
    private final ProductVariantService productVariantService;
    private final CustomerService customerService;
    private final HistoryService historyService;
    private final PromotionService promotionService;
    private final PaymentMethodService paymentMethodService;
    private final StockMovementService stockMovementService;
    private final OrderHelper orderHelper;
    private final OrderCodeGenerator orderCodeGenerator;

    public List<OrderProcessState> getOrderProcessStates() {
        return this.orderStateMachine.getOrderStateTransitions().entrySet().stream()
                .map(entry -> {
                    OrderProcessState ops = new OrderProcessState();
                    ops.setName(entry.getKey().name());
                    ops.getTo().addAll(entry.getValue().stream().map(s -> s.name()).collect(Collectors.toList()));
                    return ops;
                }).collect(Collectors.toList());
    }

    public OrderList findAll(OrderListOptions options) {
        return this.findOrderList(null, options);
    }

    /**
     * 该方法获取的OrderEntity会填充OrderLineEntity(s)和OrderItemEntity(s)
     */
    public OrderEntity findOne(Long orderId) {
        OrderEntity order = this.orderEntityMapper.selectById(orderId);
        if (order == null) return null;

        // 预填充OrderLines
        QueryWrapper<OrderLineEntity> orderLineEntityQueryWrapper = new QueryWrapper<>();
        orderLineEntityQueryWrapper.lambda().eq(OrderLineEntity::getOrderId, order.getId())
                .orderByAsc(OrderLineEntity::getCreatedAt);
        List<OrderLineEntity> orderLines = this.orderLineEntityMapper.selectList(orderLineEntityQueryWrapper);
        order.setLines(orderLines);

        // 预填充OrderItems
        for(OrderLineEntity orderLine : orderLines) {
            QueryWrapper<OrderItemEntity> orderItemEntityQueryWrapper = new QueryWrapper<>();
            orderItemEntityQueryWrapper.lambda().eq(OrderItemEntity::getOrderLineId, orderLine.getId())
                    .orderByAsc(OrderItemEntity::getCreatedAt);
            List<OrderItemEntity> orderItems = this.orderItemEntityMapper.selectList(orderItemEntityQueryWrapper);
            orderLine.setItems(orderItems);
        }
        return order;
    }

    public OrderEntity findOneByCode(String orderCode) {
        QueryWrapper<OrderEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(OrderEntity::getCode, orderCode);
        OrderEntity order = this.orderEntityMapper.selectOne(queryWrapper);
        if (order == null) return null;
        return this.findOne(order.getId());
    }

    public OrderList findByCustomerId(Long customerId, OrderListOptions options) {
        return this.findOrderList(customerId, options);
    }

    private OrderList findOrderList(Long customerId, OrderListOptions options) {
        PageInfo pageInfo = ServiceHelper.getListOptions(options);
        IPage<OrderEntity> page = new Page<>(pageInfo.current, pageInfo.size);
        QueryWrapper<OrderEntity> queryWrapper = new QueryWrapper<>();
        if (customerId != null) {
            queryWrapper.lambda().in(OrderEntity::getCustomerId, customerId);
        }
        if (options != null) {
            buildFilter(queryWrapper, options.getFilter());
            buildSortOrder(queryWrapper, options.getSort());
        }
        IPage<OrderEntity> orderEntityPage =
                this.orderEntityMapper.selectPage(page, queryWrapper);

        OrderList orderList = new OrderList();
        orderList.setTotalItems((int) orderEntityPage.getTotal()); // 设置满足条件总记录数

        if (CollectionUtils.isEmpty(orderEntityPage.getRecords()))
            return orderList; // 返回空

        // 将持久化实体类型转换成GraphQL传输类型
        orderEntityPage.getRecords().forEach(orderEntity -> {
            Order order = ServiceHelper.mapOrderEntityToOrder(orderEntity);
            orderList.getItems().add(order);
        });

        return orderList;
    }

    private void buildSortOrder(QueryWrapper queryWrapper, OrderSortParameter sortParameter) {
        if (sortParameter == null) return ;
        QueryHelper.buildOneSortOrder(queryWrapper, sortParameter.getId(), "id");
        QueryHelper.buildOneSortOrder(queryWrapper, sortParameter.getCode(), "code");
        QueryHelper.buildOneSortOrder(queryWrapper, sortParameter.getState(), "state");
        QueryHelper.buildOneSortOrder(queryWrapper, sortParameter.getSubTotal(), "sub_total");
        QueryHelper.buildOneSortOrder(queryWrapper, sortParameter.getShipping(), "shipping");
        QueryHelper.buildOneSortOrder(queryWrapper, sortParameter.getTotal(), "total");
        QueryHelper.buildOneSortOrder(queryWrapper, sortParameter.getCreatedAt(), "created_at");
        QueryHelper.buildOneSortOrder(queryWrapper, sortParameter.getUpdatedAt(), "updated_at");
    }

    private void buildFilter(QueryWrapper queryWrapper, OrderFilterParameter filterParameter) {
        if (filterParameter == null) return;
        QueryHelper.buildOneStringOperatorFilter(queryWrapper, filterParameter.getCode(), "code");
        QueryHelper.buildOneStringOperatorFilter(queryWrapper, filterParameter.getState(), "state");
        QueryHelper.buildOneBooleanOperatorFilter(queryWrapper, filterParameter.getActive(), "active");
        QueryHelper.buildOneNumberOperatorFilter(queryWrapper, filterParameter.getSubTotal(), "sub_total");
        QueryHelper.buildOneNumberOperatorFilter(queryWrapper, filterParameter.getTotal(), "total");
        QueryHelper.buildOneNumberOperatorFilter(queryWrapper, filterParameter.getShipping(), "shipping");
        QueryHelper.buildOneDateOperatorFilter(queryWrapper, filterParameter.getCreatedAt(), "created_at");
        QueryHelper.buildOneDateOperatorFilter(queryWrapper, filterParameter.getUpdatedAt(), "updated_at");
    }

    public List<PaymentEntity> getOrderPayments(Long orderId) {
        QueryWrapper<PaymentEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(PaymentEntity::getOrderId, orderId);
        return this.paymentEntityMapper.selectList(queryWrapper);
    }

    public List<OrderItemEntity> getRefundOrderItems(Long refundId) {
        QueryWrapper<OrderItemEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(OrderItemEntity::getRefundId, refundId);
        return this.orderItemEntityMapper.selectList(queryWrapper);
    }

    public List<RefundEntity> getPaymentRefunds(Long paymentId) {
        QueryWrapper<RefundEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(RefundEntity::getPaymentId, paymentId);
        return this.refundEntityMapper.selectList(queryWrapper);
    }

    public OrderEntity getActiveOrderForUser(Long userId) {
        CustomerEntity customer = this.customerService.findOneByUserId(userId);
        if (customer != null) {
            QueryWrapper<OrderEntity> queryWrapper = new QueryWrapper<>();
            queryWrapper.lambda().eq(OrderEntity::getCustomerId, customer.getId())
                    .eq(OrderEntity::isActive, true)
                    .orderByDesc(OrderEntity::getCreatedAt);
            List<OrderEntity> activeOrders = this.orderEntityMapper.selectList(queryWrapper);
            if (!CollectionUtils.isEmpty(activeOrders)) {
                return this.findOne(activeOrders.get(0).getId());
            }
        }
        return null;
    }

    public OrderEntity create(RequestContext ctx, Long userId) {
        OrderEntity order = new OrderEntity();
        order.setCode(orderCodeGenerator.generate(ctx));
        order.setState(this.orderStateMachine.getInitialState());
        if (userId != null) {
            CustomerEntity customer = this.customerService.findOneByUserId(userId);
            if (customer != null) {
                order.setCustomerId(customer.getId());
            }
        }
        this.orderEntityMapper.insert(order);
        return order;
    }

    public OrderEntity addItemToOrder(
            Long orderId,
            Long productVariantId,
            Integer quantity) {
        this.assertQuantityIsPositive(quantity);
        OrderEntity order = this.getOrderOrThrow(orderId);
        this.assertAddingItemsState(order);
        this.assertNotOverOrderItemsLimit(order, quantity);
        ProductVariantEntity productVariant = this.getProductVariantOrThrow(productVariantId);
        OrderLineEntity orderLine = order.getLines().stream()
                .filter(line -> Objects.equals(line.getProductVariantId(), productVariantId))
                .findFirst().orElse(null);
        if (orderLine == null) {
            OrderLineEntity newLine = this.createOrderLineFromVariant(productVariant);
            newLine.setOrderId(orderId);
            orderLineEntityMapper.insert(newLine);
            orderLine = newLine;
            order.getLines().add(orderLine);
        }
        return this.adjustOrderLine(null, order, orderLine.getId(), orderLine.getQuantity() + quantity);
    }

    /**
     * orderId或者orderInput两个参数必须传且只需传入一个
     */
    @Transactional
    public OrderEntity adjustOrderLine(
            Long orderId,
            OrderEntity orderInput,
            Long orderLineId,
            Integer quantity) {
        assert orderId != null || orderInput != null;
        OrderEntity order = orderInput;
        if (order == null) {
            order = this.getOrderOrThrow(orderId);
        }
        OrderLineEntity orderLine = this.getOrderLineOrThrow(order, orderLineId);
        this.assertAddingItemsState(order);
        if (quantity != null) {
            this.assertQuantityIsPositive(quantity);
            Integer currentQuantity = orderLine.getQuantity();
            this.assertNotOverOrderItemsLimit(order, quantity - currentQuantity);
            if (quantity > currentQuantity) {
                ProductVariantEntity productVariant =
                        this.productVariantEntityMapper.selectById(orderLine.getProductVariantId());
                for(int i = currentQuantity; i < quantity; i++) {
                    OrderItemEntity orderItem = new OrderItemEntity();
                    orderItem.setOrderLineId(orderLineId);
                    orderItem.setUnitPrice(productVariant.getPrice());
                    this.orderItemEntityMapper.insert(orderItem);
                    orderLine.getItems().add(orderItem);
                }
            } else if (quantity < currentQuantity) {
                orderLine.setItems(orderLine.getItems().subList(0, quantity));
                List<Long> toRemoveItemIds = new ArrayList<>();
                for(int i = quantity; i < currentQuantity; i++) {
                    OrderItemEntity orderItem = orderLine.getItems().get(i);
                    toRemoveItemIds.add(orderItem.getId());
                }
                this.orderItemEntityMapper.deleteBatchIds(toRemoveItemIds);
            }
        }
        return this.applyPriceAdjustments(order, orderLine);
    }

    @Transactional
    public OrderEntity removeItemFromOrder(Long orderId, Long orderLineId) {
        OrderEntity order = this.getOrderOrThrow(orderId);
        this.assertAddingItemsState(order);
        OrderLineEntity orderLine = this.getOrderLineOrThrow(order, orderLineId);
        order.getLines().remove(orderLine);
        OrderEntity updatedOrder = this.applyPriceAdjustments(order);
        // 删除相关联的items
        this.orderItemEntityMapper.deleteBatchIds(orderHelper.getOrderItemIds(orderLine));
        this.orderLineEntityMapper.deleteById(orderLineId);
        return updatedOrder;
    }

    @Transactional
    public OrderEntity removeAllItemsFromOrder(Long orderId) {
        OrderEntity order = this.getOrderOrThrow(orderId);
        this.assertAddingItemsState(order);
        // 先删除关联的items
        this.orderItemEntityMapper.deleteBatchIds(orderHelper.getOrderItemIds(order));
        // 再删除关联的lines
        this.orderLineEntityMapper.deleteBatchIds(orderHelper.getOrderLineIds(order));
        order.getLines().clear();
        OrderEntity updatedOrder = this.applyPriceAdjustments(order);
        return updatedOrder;
    }

    @Transactional
    public OrderEntity applyCouponCode(RequestContext ctx, Long orderId, String couponCode) {
        OrderEntity order = this.getOrderOrThrow(orderId);
        if (order.getCouponCodes().contains(couponCode)) {
            return order;
        }
        PromotionEntity promotion = this.promotionService.validateCouponCode(couponCode, order.getCustomerId());
        order.getCouponCodes().add(couponCode);
        CreateOrderHistoryEntryArgs args = ServiceHelper.buildCreateOrderHistoryEntryArgs(
                ctx,
                order.getId(),
                HistoryEntryType.ORDER_COUPON_APPLIED,
                ImmutableMap.of("couponCode", couponCode, "promotionId", promotion.getId().toString())
        );
        this.historyService.createHistoryEntryForOrder(args);
        return this.applyPriceAdjustments(order);
    }

    public OrderEntity removeCouponCode(RequestContext ctx, Long orderId, String couponCode) {
        OrderEntity order = this.getOrderOrThrow(orderId);
        if (order.getCouponCodes().contains(couponCode)) {
            order.getCouponCodes().remove(couponCode);
            CreateOrderHistoryEntryArgs args = ServiceHelper.buildCreateOrderHistoryEntryArgs(
                    ctx,
                    order.getId(),
                    HistoryEntryType.ORDER_COUPON_REMOVED,
                    ImmutableMap.of("couponCode", couponCode)
            );
            this.historyService.createHistoryEntryForOrder(args);
            return this.applyPriceAdjustments(order);
        } else {
            return order;
        }
    }

    public List<PromotionEntity> getOrderPromotions(Long orderId) {
        QueryWrapper<OrderPromotionJoinEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(OrderPromotionJoinEntity::getOrderId, orderId);
        List<Long> promotionIds = this.orderPromotionJoinEntityMapper.selectList(queryWrapper).stream()
                .map(OrderPromotionJoinEntity::getPromotionId).collect(Collectors.toList());
        if (CollectionUtils.isEmpty(promotionIds)) return new ArrayList<>();
        return this.promotionEntityMapper.selectBatchIds(promotionIds);
    }

    public List<OrderState> getNextOrderStates(Long orderId) {
        OrderEntity order = ServiceHelper.getEntityOrThrow(this.orderEntityMapper, OrderEntity.class, orderId);
        return this.orderStateMachine.getNextStates(order);
    }

    public OrderEntity setShippingAddress(Long orderId, CreateAddressInput input) {
        OrderEntity order = this.getOrderOrThrow(orderId);
        OrderAddress shippingAddress = BeanMapper.map(input, OrderAddress.class);
        order.setShippingAddress(shippingAddress);
        this.orderEntityMapper.updateById(order);
        return order;
    }

    public OrderEntity setBillingAddress(Long orderId, CreateAddressInput input) {
        OrderEntity order = this.getOrderOrThrow(orderId);
        OrderAddress billingAddress = BeanMapper.map(input, OrderAddress.class);
        order.setBillingAddress(billingAddress);
        this.orderEntityMapper.updateById(order);
        return order;
    }

    @SuppressWarnings("Duplicates")
    public List<ShippingMethodQuote> getEligibleShippingMethods(RequestContext ctx, Long orderId) {
        OrderEntity order = this.getOrderOrThrow(orderId);
        List<EligibleShippingMethod> eligibleMethods = this.shippingCalculator.getEligibleShippingMethods(order);
        return eligibleMethods.stream()
                .map(eligible -> {
                    ShippingMethodQuote quote = new ShippingMethodQuote();
                    quote.setId(eligible.getMethod().getId());
                    quote.setPrice(eligible.getResult().getPrice());
                    quote.setDescription(eligible.getMethod().getDescription());
                    quote.setMetadata(eligible.getResult().getMetadata());
                    return quote;
                }).collect(Collectors.toList());
    }

    @Transactional
    public OrderEntity transitionToState(RequestContext ctx, Long orderId, OrderState state) {
        OrderEntity order = this.getOrderOrThrow(orderId);
        OrderState fromState = order.getState();
        this.orderStateMachine.transition(ctx, order, state);
        this.orderEntityMapper.updateById(order);
        OrderStateTransitionEvent event = new OrderStateTransitionEvent(
                fromState,
                state,
                ctx,
                order
        );
        this.eventBus.post(event);
        return order;
    }

    public OrderEntity addPaymentToOrder(RequestContext ctx, Long orderId, PaymentInput input) {
        OrderEntity order = this.getOrderOrThrow(orderId);
        if (order.getState() != OrderState.ArrangingPayment) {
            throw new IllegalOperationException(
                    "A Payment may only be added when Order is in \"ArrangingPayment\" state");
        }
        PaymentEntity payment = this.paymentMethodService.createPayment(
                ctx,
                order,
                input.getMethod(),
                input.getMetadata()
        );

        if (payment.getState() == PaymentState.Error) {
            throw new InternalServerError(payment.getErrorMessage());
        }

        if (orderHelper.orderTotalIsCovered(order, PaymentState.Settled)) {
            return this.transitionToState(ctx, orderId, OrderState.PaymentSettled);
        }
        if (orderHelper.orderTotalIsCovered(order, PaymentState.Authorized)) {
            return this.transitionToState(ctx, orderId, OrderState.PaymentAuthorized);
        }
        return order;
    }

    @Transactional
    public PaymentEntity settlePayment(RequestContext ctx, Long paymentId) {
        PaymentEntity payment =
                ServiceHelper.getEntityOrThrow(this.paymentEntityMapper, PaymentEntity.class, paymentId);
        OrderEntity order = this.getOrderOrThrow(payment.getOrderId());
        SettlePaymentResult settlePaymentResult = this.paymentMethodService.settlePayment(payment, order);
        if (settlePaymentResult.isSuccess()) {
            PaymentState fromState = payment.getState();
            PaymentState toState = PaymentState.Settled;
            this.paymentStateMachine.transition(ctx, order, payment, toState);
            payment.getMetadata().putAll(settlePaymentResult.getMetadata());
            this.paymentEntityMapper.updateById(payment);
            PaymentStateTransitionEvent event = new PaymentStateTransitionEvent(
                    fromState,
                    toState,
                    ctx,
                    payment,
                    order
            );
            this.eventBus.post(event);
            if (Objects.equals(payment.getAmount(), order.getTotal())) {
                this.transitionToState(ctx, order.getId(), OrderState.PaymentSettled);
            }
        }
        return payment;
    }

    @Transactional
    public FulfillmentEntity createFulfillment(RequestContext ctx, FulfillOrderInput input) {
        if (CollectionUtils.isEmpty(input.getLines()) || input.getLines().stream()
                .reduce(0, (total, line) -> total + line.getQuantity(), Integer::sum) == 0) {
            throw new UserInputException("Nothing to fulfill");
        }

        Pair<List<OrderEntity>, List<OrderItemEntity>> pair = this.getOrdersAndItemsFromLines(
                input.getLines(),
                i -> i.getFulfillmentId() == null,
                "One or more OrderItems have already been fulfilled");
        List<OrderEntity> orders = pair.getLeft();
        List<OrderItemEntity> items = pair.getRight();

        for(OrderEntity order : orders) {
            if (order.getState() != OrderState.PaymentSettled && order.getState() != OrderState.PartiallyFulfilled) {
                throw new IllegalOperationException(
                        "One or more OrderItems belong to an Order which is in an invalid state");
            }
        }

        FulfillmentEntity fulfillment = new FulfillmentEntity();
        fulfillment.setTrackingCode(input.getTrackingCode());
        fulfillment.setMethod(input.getMethod());
        this.fulfillmentEntityMapper.insert(fulfillment);

        for(OrderItemEntity item : items) {
            item.setFulfillmentId(fulfillment.getId());
            orderItemEntityMapper.updateById(item);
        }

        for (OrderEntity order : orders) {
            CreateOrderHistoryEntryArgs args =
                    ServiceHelper.buildCreateOrderHistoryEntryArgs(
                            ctx,
                            order.getId(),
                            HistoryEntryType.ORDER_FULFILLMENT,
                            ImmutableMap.of("fulfillmentId", fulfillment.getId().toString()));
            this.historyService.createHistoryEntryForOrder(args);
            OrderEntity orderWithItems = this.getOrderOrThrow(order.getId());
            if (orderHelper.orderItemsAreFulfilled(orderWithItems)) {
                this.transitionToState(ctx, order.getId(), OrderState.Fulfilled);
            } else {
                this.transitionToState(ctx, order.getId(), OrderState.PartiallyFulfilled);
            }
        }
        return fulfillment;
    }

    public List<FulfillmentEntity> getOrderFulfillments(OrderEntity order) {
        List<OrderLineEntity> lines = null;
        if (!CollectionUtils.isEmpty(order.getLines()) && order.getLines().get(0) != null &&
                !CollectionUtils.isEmpty(order.getLines().get(0).getItems()) &&
                order.getLines().get(0).getItems().get(0).getFulfillmentId() != null) {
            lines = order.getLines();
        } else {
            OrderEntity orderWithItems = this.findOne(order.getId());
            lines = orderWithItems.getLines();
        }
        List<OrderItemEntity> allItems = new ArrayList<>();
        lines.forEach(l -> {
            allItems.addAll(l.getItems());
        });
        List<Long> fulfillmentIds =
                allItems.stream().filter(i -> i.getFulfillmentId() != null).map(OrderItemEntity::getFulfillmentId)
                        .distinct().collect(Collectors.toList());
        return this.fulfillmentEntityMapper.selectBatchIds(fulfillmentIds);
    }

    public List<OrderItemEntity> getFulfillmentOrderItems(Long id) {
        // 确保FulfillmentEntity存在
        ServiceHelper.getEntityOrThrow(this.fulfillmentEntityMapper, FulfillmentEntity.class, id);
        QueryWrapper<OrderItemEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(OrderItemEntity::getFulfillmentId, id);
        List<OrderItemEntity> orderItems = this.orderItemEntityMapper.selectList(queryWrapper);
        return orderItems;
    }

    @Transactional
    public OrderEntity cancelOrder(RequestContext ctx, CancelOrderInput input) {
        boolean allOrderItemsCancelled = false;
        if (!CollectionUtils.isEmpty(input.getLines())) {
            allOrderItemsCancelled = this.cancelOrderByOrderLines(ctx, input, input.getLines());
        } else {
            allOrderItemsCancelled = this.cancelOrderById(ctx, input);
        }
        if (allOrderItemsCancelled) {
            this.transitionToState(ctx, input.getOrderId(), OrderState.Cancelled);
        }
        return this.findOne(input.getOrderId());
    }

    private boolean cancelOrderById(RequestContext ctx, CancelOrderInput input) {
        OrderEntity order = this.getOrderOrThrow(input.getOrderId());
        if (order.getState() == OrderState.AddingItems || order.getState() == OrderState.ArrangingPayment) {
            return true;
        } else {
            List<OrderLineInput> lines = order.getLines().stream()
                    .map(l -> {
                        OrderLineInput orderLineInput = new OrderLineInput();
                        orderLineInput.setOrderLineId(l.getId());
                        orderLineInput.setQuantity(l.getQuantity());
                        return orderLineInput;
                    }).collect(Collectors.toList());
            return this.cancelOrderByOrderLines(ctx, input, lines);
        }
    }

    private boolean cancelOrderByOrderLines(
            RequestContext ctx,
            CancelOrderInput input,
            List<OrderLineInput> lines) {
        if (CollectionUtils.isEmpty(lines) ||
                lines.stream().reduce(0, (total, line) -> total + line.getQuantity(), Integer::sum) == 0) {
            throw new UserInputException("Nothing to cancel");
        }
        Pair<List<OrderEntity>, List<OrderItemEntity>> pair = this.getOrdersAndItemsFromLines(
                lines,
                i -> !i.isCancelled(),
                "Quantity to cancel is greater than existing OrderLine quantity");
        List<OrderEntity> orders = pair.getLeft();
        List<OrderItemEntity> items = pair.getRight();
        if (orders.size() > 1) {
            throw new IllegalOperationException("OrderLines must all belong to a single Order");
        }
        OrderEntity order = orders.get(0);
        if (!Objects.equals(order.getId(), input.getOrderId())) {
            throw new IllegalOperationException("OrderLines must all belong to a single Order");
        }
        if (order.getState() == OrderState.AddingItems || order.getState() == OrderState.ArrangingPayment) {
            throw new IllegalOperationException(
                    String.format("Cannot cancel OrderLines from an Order in the \"{ %s }\" state", order.getState()));
        }

        // Perform the cancellation
        this.stockMovementService.createCancellationsForOrderItems(items);
        items.forEach(item -> {
            item.setCancelled(true);
            this.orderItemEntityMapper.updateById(item);
        });

        OrderEntity orderWithItems = this.getOrderOrThrow(order.getId());
        CreateOrderHistoryEntryArgs args = ServiceHelper.buildCreateOrderHistoryEntryArgs(
                ctx,
                order.getId(),
                HistoryEntryType.ORDER_CANCELLATION,
                ImmutableMap.of(
                        "orderItemIds",
                        items.stream().map(i -> i.getId().toString()).collect(Collectors.joining(",")),
                                "reason",
                                input.getReason() == null ? "" : input.getReason()));
        this.historyService.createHistoryEntryForOrder(args);
        return orderHelper.orderItemsAreAllCancelled(orderWithItems);
    }

    public RefundEntity refundOrder(RequestContext ctx, RefundOrderInput input) {
        if ((CollectionUtils.isEmpty(input.getLines()) || input.getLines().stream()
            .reduce(0, (total, line) -> total + line.getQuantity(), Integer::sum) == 0) &&
            input.getShipping() == 0) {
            throw new UserInputException("Nothing to refund");
        }
        Pair<List<OrderEntity>, List<OrderItemEntity>> pair = this.getOrdersAndItemsFromLines(
                input.getLines(),
                i -> !i.isCancelled(),
                "Quantity to refund is greater than existing OrderLine quantity");
        List<OrderEntity> orders = pair.getLeft();
        List<OrderItemEntity> items = pair.getRight();
        if (orders.size() > 1) {
            throw new IllegalOperationException("OrderLines must all belong to a single Order");
        }
        PaymentEntity payment =
                ServiceHelper.getEntityOrThrow(this.paymentEntityMapper, PaymentEntity.class, input.getPaymentId());
        if (!CollectionUtils.isEmpty(orders) && !Objects.equals(payment.getOrderId(), orders.get(0).getId())) {
            throw new IllegalOperationException("Refund order payment lines mismatch");
        }
        OrderEntity order = this.findOne(payment.getOrderId());
        if (order.getState() == OrderState.AddingItems || order.getState() == OrderState.ArrangingPayment ||
            order.getState() == OrderState.PaymentAuthorized) {
            throw new IllegalOperationException(
                    String.format("Cannot refund an OrderItem in the \"{ %s }\" state", order.getState()));
        }
        if (items.stream().anyMatch(i -> i.getRefundId() != null)) {
            throw new IllegalOperationException("Cannot refund an OrderItem which has already been refunded");
        }
        return this.paymentMethodService.createRefund(ctx, input, order, items, payment);
    }

    @Transactional
    public RefundEntity settleRefund(RequestContext ctx, SettleRefundInput input) {
        RefundEntity refund = ServiceHelper.getEntityOrThrow(this.refundEntityMapper, RefundEntity.class, input.getId());
        refund.setTransactionId(input.getTransactionId());
        RefundState fromState = refund.getState();
        RefundState toState = RefundState.Settled;
        PaymentEntity payment = this.paymentEntityMapper.selectById(refund.getPaymentId());
        OrderEntity order = this.orderEntityMapper.selectById(payment.getOrderId());
        this.refundStateMachine.transition(ctx, order, refund, toState);
        this.refundEntityMapper.updateById(refund);

        RefundStateTransitionEvent event = new RefundStateTransitionEvent(
                fromState,
                toState,
                ctx,
                refund,
                order
        );
        this.eventBus.post(event);
        return refund;
    }

    @Transactional
    public OrderEntity addCustomerToOrder(Long orderId, Long customerId) {
        OrderEntity order = this.getOrderOrThrow(orderId);
        order.setCustomerId(customerId);
        this.orderEntityMapper.updateById(order);
        /**
         * Check that any applied couponCodes are still valid now that
         * we know the Customer.
         */
        if (!CollectionUtils.isEmpty(order.getCouponCodes())) {
            Set<String> toRemoveCodes = new HashSet<>();
            for(String couponCode : order.getCouponCodes()) {
                try {
                    this.promotionService.validateCouponCode(couponCode, customerId);
                } catch (Exception ex) {
                    toRemoveCodes.add(couponCode);
                }
            }
            if (toRemoveCodes.size() > 0) {
                order.getCouponCodes().removeAll(toRemoveCodes);
                this.applyPriceAdjustments(order);
            }
        }
        return order;
    }

    public OrderEntity addNoteToOrder(RequestContext ctx, AddNoteToOrderInput input) {
        OrderEntity order = this.getOrderOrThrow(input.getId());
        CreateOrderHistoryEntryArgs args = ServiceHelper.buildCreateOrderHistoryEntryArgs(
                ctx,
                order.getId(),
                HistoryEntryType.ORDER_NOTE,
                ImmutableMap.of("note", input.getNote())
        );
        this.historyService.createHistoryEntryForOrder(args, BooleanUtils.toBoolean(input.getPrivateOnly()));
        return order;
    }

    public HistoryEntry updateOrderNote(RequestContext ctx, UpdateOrderNoteInput input) {
        UpdateOrderHistoryEntryArgs args = ServiceHelper.buildUpdateOrderHistoryEntryArgs(
                ctx,
                HistoryEntryType.ORDER_NOTE,
                input.getNote(),
                BooleanUtils.toBoolean(input.getPrivateOnly()),
                input.getNoteId()
        );
        OrderHistoryEntryEntity orderHistoryEntryEntity = this.historyService.updateOrderHistoryEntry(ctx, args);
        return BeanMapper.map(orderHistoryEntryEntity, HistoryEntry.class);
    }

    public DeletionResponse deleteOrderNote(Long id) {
        DeletionResponse response = new DeletionResponse();
        try {
            this.historyService.deleteOrderHistoryEntry(id);
            response.setResult(DeletionResult.DELETED);
        } catch (Exception ex) {
            response.setResult(DeletionResult.NOT_DELETED);
            response.setMessage(ex.getMessage());
        }
        return response;
    }

    /**
     * When a guest user with an anonymous Order signs in and has an existing Order associated with that Customer,
     * we need to reconcile the contents of the two orders.
     */
    @Transactional
    public OrderEntity mergeOrders(UserEntity user, OrderEntity guestOrder, OrderEntity existingOrder) {
        if (guestOrder != null && guestOrder.getCustomerId() != null) {
            /**
             * In thie case the "guest order" is actually an order of an existing Customer,
             * so we do not want to merge at all.
             */
            return existingOrder;
        }
        MergeResult mergeResult = this.orderMerger.merge(guestOrder, existingOrder);
        OrderEntity orderToDelete = mergeResult.getOrderToDelete();
        List<LineItem> linesToInsert = mergeResult.getLinesToInsert();
        OrderEntity order = mergeResult.getOrder();
        if (orderToDelete != null) {
            this.orderEntityMapper.deleteById(orderToDelete.getId());
        }
        if (order != null && !CollectionUtils.isEmpty(linesToInsert)) {
            for(LineItem line : linesToInsert) {
                this.addItemToOrder(order.getId(), line.getProductVariantId(), line.getQuantity());
            }
        }
        CustomerEntity customer = this.customerService.findOneByUserId(user.getId());
        if (order != null && customer != null) {
            order.setCustomerId(customer.getId());
            this.orderEntityMapper.updateById(order);
        }

        return order;
    }

    /**
     * 该方法获取的OrderEntity会填充OrderLineEntity(s)和OrderItemEntity(s)
     */
    private OrderEntity getOrderOrThrow(Long orderId) {
        OrderEntity order = this.findOne(orderId);
        if (order == null) {
            throw new EntityNotFoundException("Order", orderId);
        }
        return order;
    }

    private ProductVariantEntity getProductVariantOrThrow(Long productVariantId) {
        ProductVariantEntity productVariant = this.productVariantService.findOne(productVariantId);
        if (productVariant == null) {
            throw new EntityNotFoundException("ProductVariant", productVariantId);
        }
        return productVariant;
    }

    private OrderLineEntity getOrderLineOrThrow(OrderEntity order, Long orderLineId) {
        OrderLineEntity orderLine = order.getLines().stream()
                .filter(line -> Objects.equals(line.getId(), orderLineId))
                .findFirst().orElse(null);
        if (orderLine == null) {
            throw new UserInputException(
                    String.format("This order does not contain an OrderLine with the id { %d }", orderLineId)
            );
        }
        return orderLine;
    }

    private OrderLineEntity createOrderLineFromVariant(
            ProductVariantEntity productVariant

    ) {
        OrderLineEntity orderLine = new OrderLineEntity();
        orderLine.setProductVariantId(productVariant.getId());

        ProductEntity product = this.productEntityMapper.selectById(productVariant.getProductId());
        orderLine.setFeaturedAssetId(product.getFeaturedAssetId());

        return orderLine;
    }

    /**
     * Throws if quantity is negative.
     */
    private void assertQuantityIsPositive(Integer quantity) {
        if (quantity < 0) {
            throw new IllegalOperationException(
                    String.format("{ %d } is not a valid quantity for an OrderItem", quantity)
            );
        }
    }

    /**
     * Throws if the Order is not in the "AddingItems" state.
     */
    private void assertAddingItemsState(OrderEntity order) {
        if (!Objects.equals(order.getState(), OrderState.AddingItems)) {
            throw new IllegalOperationException(
                    "Order contents may only be modified when in the \"AddingItems\" state"
            );
        }
    }

    /**
     * Throws if adding the given quantity would take the total order items over the
     * maximum limit specified in the config.
     */
    private void assertNotOverOrderItemsLimit(OrderEntity order, Integer quantityToAdd) {
        Integer currentItemsCount = order.getLines().stream()
                .reduce(0, (subTotal, line) -> subTotal + line.getQuantity(), Integer::sum);
        Integer orderItemsLimit = this.configService.getOrderOptions().getOrderItemsLimit();
        if (currentItemsCount + quantityToAdd > orderItemsLimit) {
            throw new OrderItemsLimitException(orderItemsLimit);
        }
    }

    /**
     * Applies promotions and shipping to the Order
     */
    private OrderEntity applyPriceAdjustments(OrderEntity order) {
        return  this.applyPriceAdjustments(order, null);
    }

    @Transactional
    OrderEntity applyPriceAdjustments(OrderEntity order, OrderLineEntity updatedOrderLine) {
        QueryWrapper<PromotionEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(PromotionEntity::isEnabled, true).isNull(PromotionEntity::getDeletedAt)
                .orderByAsc(PromotionEntity::getPriorityScore);
        List<PromotionEntity> promotions = promotionEntityMapper.selectList(queryWrapper);

        List<OrderItemEntity> updatedItems =
                orderCalculator.applyPriceAdjustments(order, promotions, updatedOrderLine);
        this.orderEntityMapper.updateById(order);
        for(OrderItemEntity item : updatedItems) {
            this.orderItemEntityMapper.updateById(item);
        }
        return order;
    }

    private Pair<List<OrderEntity>, List<OrderItemEntity>> getOrdersAndItemsFromLines(
            List<OrderLineInput> orderLinesInput,
            Matcher itemMatcher,
            String noMatchesError
    ) {
        Map<Long, OrderEntity> ordersMap = new HashMap<>();
        Map<Long, OrderItemEntity> itemsMap = new HashMap<>();

        List<Long> orderLineIds = orderLinesInput.stream().map(l -> l.getOrderLineId()).collect(Collectors.toList());
        List<OrderLineEntity> lines = this.orderLineEntityMapper.selectBatchIds(orderLineIds);
        for(OrderLineEntity line : lines) {
            OrderLineInput inputLine = orderLinesInput.stream()
                    .filter(l -> Objects.equals(l.getOrderLineId(), line.getId())).findFirst().orElse(null);
            if (inputLine == null) continue;
            OrderEntity order = orderEntityMapper.selectById(line.getOrderId());
            if (!ordersMap.containsKey(order.getId())) {
                ordersMap.put(order.getId(), order);
            } else {
                order = ordersMap.get(order.getId());
            }
            order.getLines().add(line);
            QueryWrapper<OrderItemEntity> queryWrapper = new QueryWrapper<>();
            queryWrapper.lambda().eq(OrderItemEntity::getOrderLineId, line.getId());
            List<OrderItemEntity> lineItems = orderItemEntityMapper.selectList(queryWrapper);
            line.setItems(lineItems);
            List<OrderItemEntity> matchingItems = line.getItems().stream()
                    .sorted((a, b) -> a.getId() < b.getId() ? -1 : 1)
                    .filter(i -> itemMatcher.match(i))
                    .collect(Collectors.toList());
            if (matchingItems.size() < inputLine.getQuantity()) {
                throw new IllegalOperationException(noMatchesError);
            }
            matchingItems.subList(0, inputLine.getQuantity()).forEach(item -> itemsMap.put(item.getId(), item));
        }
        return Pair.of(new ArrayList(ordersMap.values()), new ArrayList(itemsMap.values()));
    }


    interface Matcher {
        boolean match(OrderItemEntity i);
    }
}