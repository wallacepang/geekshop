package co.jueyi.geekshop.service;

import co.jueyi.geekshop.common.Constant;
import co.jueyi.geekshop.common.RequestContext;
import co.jueyi.geekshop.common.utils.BeanMapper;
import co.jueyi.geekshop.common.utils.NormalizeUtil;
import co.jueyi.geekshop.entity.*;
import co.jueyi.geekshop.eventbus.events.AccountRegistrationEvent;
import co.jueyi.geekshop.eventbus.events.IdentifierChangeEvent;
import co.jueyi.geekshop.eventbus.events.IdentifierChangeRequestEvent;
import co.jueyi.geekshop.eventbus.events.PasswordResetEvent;
import co.jueyi.geekshop.exception.EntityNotFoundException;
import co.jueyi.geekshop.exception.IllegalOperationException;
import co.jueyi.geekshop.exception.InternalServerError;
import co.jueyi.geekshop.exception.UserInputException;
import co.jueyi.geekshop.mapper.*;
import co.jueyi.geekshop.service.args.CreateCustomerHistoryEntryArgs;
import co.jueyi.geekshop.service.args.UpdateCustomerHistoryEntryArgs;
import co.jueyi.geekshop.service.helper.ServiceHelper;
import co.jueyi.geekshop.types.common.*;
import co.jueyi.geekshop.types.customer.*;
import co.jueyi.geekshop.types.history.HistoryEntry;
import co.jueyi.geekshop.types.history.HistoryEntryType;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.EventBus;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Created on Nov, 2020 by @author bobo
 */
@Service
@RequiredArgsConstructor
public class CustomerService {
    private final CustomerEntityMapper customerEntityMapper;
    private final AddressEntityMapper addressEntityMapper;
    private final UserEntityMapper userEntityMapper;
    private final CustomerGroupEntityMapper customerGroupEntityMapper;
    private final CustomerGroupJoinEntityMapper customerGroupJoinEntityMapper;
    private final UserService userService;
    private final HistoryService historyService;
    private final EventBus eventBus;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    public CustomerEntity findOneEntity(Long id) {
        QueryWrapper<CustomerEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(CustomerEntity::getId, id).isNull(CustomerEntity::getDeletedAt);
        CustomerEntity customerEntity = this.customerEntityMapper.selectOne(queryWrapper);
        return customerEntity;
    }

    public CustomerEntity findOneEntityByUserId(Long userId) {
        QueryWrapper<CustomerEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(CustomerEntity::getUserId, userId).isNull(CustomerEntity::getDeletedAt);
        CustomerEntity customerEntity = this.customerEntityMapper.selectOne(queryWrapper);
        return customerEntity;
    }

    public List<AddressEntity> findAddressEntitiesByCustomerId(RequestContext ctx, Long customerId) {
        QueryWrapper<AddressEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(AddressEntity::getCustomerId, customerId);
        List<AddressEntity> addressEntityList = this.addressEntityMapper.selectList(queryWrapper);
        return addressEntityList;
    }

    public List<CustomerGroup> getCustomerGroups(Long customerId) {
        QueryWrapper<CustomerGroupJoinEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(CustomerGroupJoinEntity::getCustomerId, customerId);
        List<CustomerGroupJoinEntity> customerGroupJoinEntityList =
                this.customerGroupJoinEntityMapper.selectList(queryWrapper);
        if (CollectionUtils.isEmpty(customerGroupJoinEntityList)) return new ArrayList<>();

        List<Long> groupIds = customerGroupJoinEntityList.stream()
                .map(CustomerGroupJoinEntity::getGroupId).collect(Collectors.toList());

        QueryWrapper<CustomerGroupEntity> customerGroupQueryWrapper = new QueryWrapper<>();
        customerGroupQueryWrapper.lambda().in(CustomerGroupEntity::getId, groupIds);
        List<CustomerGroupEntity> customerGroupEntityList =
                this.customerGroupEntityMapper.selectList(customerGroupQueryWrapper);
        return customerGroupEntityList.stream()
                .map(customerGroupEntity -> BeanMapper.map(customerGroupEntity, CustomerGroup.class))
                .collect(Collectors.toList());
    }

    public CustomerEntity create(RequestContext ctx, CreateCustomerInput input, String password) {
        input.setEmailAddress(NormalizeUtil.normalizeEmailAddress(input.getEmailAddress()));
        CustomerEntity newCustomerEntity = BeanMapper.map(input, CustomerEntity.class);

        QueryWrapper<CustomerEntity> customerEntityQueryWrapper = new QueryWrapper<>();
        customerEntityQueryWrapper.lambda().eq(CustomerEntity::getEmailAddress, input.getEmailAddress())
                .isNull(CustomerEntity::getDeletedAt);
        CustomerEntity existingCustomerEntity = this.customerEntityMapper.selectOne(customerEntityQueryWrapper);

        QueryWrapper<UserEntity> userEntityQueryWrapper = new QueryWrapper<>();
        userEntityQueryWrapper.lambda().eq(UserEntity::getIdentifier, input.getEmailAddress())
                .isNull(UserEntity::getDeletedAt);
        UserEntity existingUserEntity = this.userEntityMapper.selectOne(userEntityQueryWrapper);

        if (existingCustomerEntity != null || existingUserEntity != null) {
            throw new UserInputException("The email address must be unique");
        }

        UserEntity userEntity = this.userService.createCustomerUser(input.getEmailAddress(), password);

        if (!StringUtils.isEmpty(password)) {
            String verificationToken = this.userService.getNativeAuthMethodEntityByUserId(userEntity.getId())
                    .getVerificationToken();
            if (!StringUtils.isEmpty(verificationToken)) {
                userEntity = this.userService.verifyUserByToken(verificationToken, null);
            }
        } else {
            this.eventBus.post(new AccountRegistrationEvent(ctx, userEntity));
        }
        this.customerEntityMapper.insert(newCustomerEntity);

        CreateCustomerHistoryEntryArgs args = ServiceHelper.buildCreateCustomerHistoryEntryArgs(
                ctx, newCustomerEntity.getId(), HistoryEntryType.CUSTOMER_REGISTERED,
                ImmutableMap.of(HistoryService.KEY_STRATEGY, Constant.NATIVE_AUTH_STRATEGY_NAME));
        this.historyService.createHistoryEntryForCustomer(args);

        if (userEntity.isVerified()) {
            args = ServiceHelper.buildCreateCustomerHistoryEntryArgs(
                    ctx, newCustomerEntity.getId(), HistoryEntryType.CUSTOMER_VERIFIED,
                    ImmutableMap.of(HistoryService.KEY_STRATEGY, Constant.NATIVE_AUTH_STRATEGY_NAME));
            this.historyService.createHistoryEntryForCustomer(args);
        }

        return newCustomerEntity;
    }

    public boolean registerCustomerAccount(RequestContext ctx, RegisterCustomerInput input) {
        if (!this.configService.getAuthOptions().isRequireVerification()) {
            if (StringUtils.isEmpty(input.getPassword())) {
                throw new UserInputException(
                        "A password must be provided when `authOptions.requireVerification` is set to \"false\"");
            }
        }
        UserEntity userEntity = this.userService.findUserEntityByEmailAddress(input.getEmailAddress());
        boolean hasNativeAuthMethod =
                userEntity != null ? this.userService.checkHasNativeAuthMethod(userEntity.getId()) : false;
        if (userEntity != null && userEntity.isVerified()) {
            if (hasNativeAuthMethod) {
                // If the user has already been verified and has already
                // registered with the native authentication strategy, do nothing.
                return false;
            }
        }
        CreateCustomerInput createCustomerInput = new CreateCustomerInput();
        createCustomerInput.setEmailAddress(input.getEmailAddress());
        createCustomerInput.setTitle(input.getTitle() == null ? "" : input.getTitle());
        createCustomerInput.setFirstName(input.getFirstName() == null ? "" : input.getFirstName());
        createCustomerInput.setLastName(input.getLastName() == null ? "" : input.getLastName());
        createCustomerInput.setPhoneNumber(input.getPhoneNumber() == null ? "" : input.getPhoneNumber());
        CustomerEntity customerEntity = this.createOrUpdate(createCustomerInput);

        CreateCustomerHistoryEntryArgs args = ServiceHelper.buildCreateCustomerHistoryEntryArgs(
                ctx, customerEntity.getId(), HistoryEntryType.CUSTOMER_REGISTERED,
                ImmutableMap.of(HistoryService.KEY_STRATEGY, Constant.NATIVE_AUTH_STRATEGY_NAME));
        this.historyService.createHistoryEntryForCustomer(args);

        if (userEntity == null) {
            userEntity = this.userService.createCustomerUser(input.getEmailAddress(), input.getPassword());
        }
        if (!hasNativeAuthMethod) {
            userEntity = this.userService.addNativeAuthenticationMethod(
                    userEntity, input.getEmailAddress(), input.getPassword());
        }
        if (!userEntity.isVerified()) {
            userEntity = this.userService.setVerificationToken(userEntity);
        }
        customerEntity.setUserId(userEntity.getId());
        if (customerEntity.getId() != null) {
            this.customerEntityMapper.updateById(customerEntity);
        } else {
            this.customerEntityMapper.insert(customerEntity);
        }
        if (!userEntity.isVerified()) {
            this.eventBus.post(new AccountRegistrationEvent(ctx, userEntity));
        } else {
            args = ServiceHelper.buildCreateCustomerHistoryEntryArgs(
                            ctx, customerEntity.getId(), HistoryEntryType.CUSTOMER_VERIFIED,
                            ImmutableMap.of(HistoryService.KEY_STRATEGY, Constant.NATIVE_AUTH_STRATEGY_NAME));
            this.historyService.createHistoryEntryForCustomer(args);
        }
        return true;
    }

    public void refreshVerificationToken(RequestContext ctx, String emailAddress) {
        UserEntity userEntity = this.userService.findUserEntityByEmailAddress(emailAddress);
        if (userEntity != null) {
            this.userService.setVerificationToken(userEntity);
            if (!userEntity.isVerified()) {
                this.eventBus.post(new AccountRegistrationEvent(ctx, userEntity));
            }
        }
    }

    @SuppressWarnings("Duplicates")
    public CustomerEntity verifiyCustomerEmailAddress(RequestContext ctx, String verificationToken, String password) {
        UserEntity userEntity = this.userService.verifyUserByToken(verificationToken, password);
        if (userEntity == null) return null;

        CustomerEntity customerEntity = this.findOneEntityByUserId(userEntity.getId());
        if (customerEntity == null) {
            throw new InternalServerError("Cannot locate a Customer for the user");
        }

        CreateCustomerHistoryEntryArgs args = ServiceHelper.buildCreateCustomerHistoryEntryArgs(
                ctx, customerEntity.getId(), HistoryEntryType.CUSTOMER_VERIFIED,
                ImmutableMap.of(HistoryService.KEY_STRATEGY, Constant.NATIVE_AUTH_STRATEGY_NAME));
        this.historyService.createHistoryEntryForCustomer(args);
        return this.findOneEntityByUserId(userEntity.getId());
    }

    public void requestPasswordReset(RequestContext ctx, String emailAddress) {
        UserEntity userEntity = this.userService.setPasswordResetToken(emailAddress);
        if (userEntity == null) return;

        this.eventBus.post(new PasswordResetEvent(ctx, userEntity));
        CustomerEntity customerEntity = this.findOneEntityByUserId(userEntity.getId());
        if (customerEntity == null) {
            throw new InternalServerError("Cannot locate a Customer for the user");
        }

        CreateCustomerHistoryEntryArgs args = ServiceHelper.buildCreateCustomerHistoryEntryArgs(
                ctx, customerEntity.getId(), HistoryEntryType.CUSTOMER_PASSWORD_RESET_REQUESTED);
        this.historyService.createHistoryEntryForCustomer(args);
    }

    @SuppressWarnings("Duplicates")
    public CustomerEntity resetPassword(RequestContext ctx, String passwordResetToken, String password) {
        UserEntity userEntity = this.userService.resetPasswordByToken(passwordResetToken, password);
        if (userEntity == null) return null;
        CustomerEntity customerEntity = this.findOneEntityByUserId(userEntity.getId());
        if (customerEntity == null) {
            throw new InternalServerError("Cannot locate a Customer for the user");
        }

        CreateCustomerHistoryEntryArgs args = ServiceHelper.buildCreateCustomerHistoryEntryArgs(
                ctx, customerEntity.getId(), HistoryEntryType.CUSTOMER_PASSWORD_RESET_VERIFIED);
        this.historyService.createHistoryEntryForCustomer(args);

        return customerEntity;
    }

    public boolean requestUpdateEmailAddress(RequestContext ctx, Long userId, String newEmailAddress) {
        UserEntity userEntityWithConflictingIdentifier = this.userService.findUserEntityByEmailAddress(newEmailAddress);
        if (userEntityWithConflictingIdentifier != null) {
            throw new UserInputException("This email address is not available");
        }
        UserEntity userEntity = this.userService.findUserEntityById(userId);
        if (userEntity == null) return false;
        CustomerEntity customerEntity = this.findOneEntityByUserId(userId);
        if (customerEntity == null) return false;
        String oldEmailAddress = customerEntity.getEmailAddress();
        CreateCustomerHistoryEntryArgs args = ServiceHelper.buildCreateCustomerHistoryEntryArgs(
                ctx, customerEntity.getId(), HistoryEntryType.CUSTOMER_EMAIL_UPDATE_REQUESTED,
                ImmutableMap.of(
                        HistoryService.KEY_OLD_EMAIL_ADDRESS, oldEmailAddress,
                        HistoryService.KEY_NEW_EMAIL_ADDRESS, newEmailAddress));
        this.historyService.createHistoryEntryForCustomer(args);
        if (this.configService.getAuthOptions().isRequireVerification()) {
            this.userService.changeIdentifierAndSetToken(userId, newEmailAddress);
            this.eventBus.post(new IdentifierChangeRequestEvent(ctx, userEntity));
        } else {
            String oldIdentifier = userEntity.getIdentifier();
            userEntity.setIdentifier(newEmailAddress);
            customerEntity.setEmailAddress(newEmailAddress);
            this.userEntityMapper.updateById(userEntity);
            this.customerEntityMapper.updateById(customerEntity);
            this.eventBus.post(new IdentifierChangeEvent(ctx, userEntity, oldIdentifier));

            args = ServiceHelper.buildCreateCustomerHistoryEntryArgs(
                    ctx, customerEntity.getId(), HistoryEntryType.CUSTOMER_EMAIL_UPDATE_VERIFIED,
                    ImmutableMap.of(
                            HistoryService.KEY_OLD_EMAIL_ADDRESS, oldEmailAddress,
                            HistoryService.KEY_NEW_EMAIL_ADDRESS, newEmailAddress));
            this.historyService.createHistoryEntryForCustomer(args);
        }
        return true;
    }

    public boolean updateEmailAddress(RequestContext ctx, String token) {
        Pair<UserEntity, String> pair = this.userService.changeIdentifierByToken(token);
        UserEntity userEntity = pair.getKey();
        String oldIdentifier = pair.getValue();
        if (userEntity == null) return false;
        CustomerEntity customerEntity = this.findOneEntityByUserId(userEntity.getId());
        if (customerEntity == null) {
            return false;
        }
        this.eventBus.post(new IdentifierChangeEvent(ctx, userEntity, oldIdentifier));
        customerEntity.setEmailAddress(userEntity.getIdentifier());
        this.customerEntityMapper.updateById(customerEntity);
        CreateCustomerHistoryEntryArgs args = ServiceHelper.buildCreateCustomerHistoryEntryArgs(
                ctx, customerEntity.getId(), HistoryEntryType.CUSTOMER_EMAIL_UPDATE_VERIFIED,
                ImmutableMap.of(
                        HistoryService.KEY_OLD_EMAIL_ADDRESS, oldIdentifier,
                        HistoryService.KEY_NEW_EMAIL_ADDRESS, customerEntity.getEmailAddress()));
        this.historyService.createHistoryEntryForCustomer(args);
        return true;
    }

    public CustomerEntity update(RequestContext ctx, UpdateCustomerInput input) {
        CustomerEntity customerEntity = ServiceHelper.getEntityOrThrow(this.customerEntityMapper, input.getId());
        BeanMapper.patch(input, customerEntity);
        this.customerEntityMapper.updateById(customerEntity);

        Map<String, String> data = objectMapper.convertValue(input, Map.class);
        CreateCustomerHistoryEntryArgs args = ServiceHelper.buildCreateCustomerHistoryEntryArgs(
                ctx, customerEntity.getId(), HistoryEntryType.CUSTOMER_DETAIL_UPDATED, data);
        this.historyService.createHistoryEntryForCustomer(args);
        return customerEntity;
    }

    /**
     * For guest checkouts, we assume that a matching email address is the same customer.
     */
    public CustomerEntity createOrUpdate(CreateCustomerInput input) {
        return this.createOrUpdate(input, false);
    }

    public CustomerEntity createOrUpdate(CreateCustomerInput input, boolean throwOnExistingUser) {
        input.setEmailAddress(NormalizeUtil.normalizeEmailAddress(input.getEmailAddress()));
        QueryWrapper<CustomerEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(CustomerEntity::getEmailAddress, input.getEmailAddress())
                .isNull(CustomerEntity::getDeletedAt);
        CustomerEntity existingCustomerEntity = this.customerEntityMapper.selectOne(queryWrapper);
        if (existingCustomerEntity != null) {
            if (existingCustomerEntity.getUserId() != null && throwOnExistingUser) {
                throw new IllegalOperationException(
                        "Cannot use a registered email address for a guest order. Please log in first");
            }
            BeanMapper.patch(input, existingCustomerEntity);
            this.customerEntityMapper.updateById(existingCustomerEntity);
            return existingCustomerEntity;
        } else {
            CustomerEntity newCustomerEntity = BeanMapper.map(input, CustomerEntity.class);
            this.customerEntityMapper.insert(newCustomerEntity);
            return newCustomerEntity;
        }
    }

    public AddressEntity createAddress(RequestContext ctx, Long customerId, CreateAddressInput input) {
        QueryWrapper<CustomerEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(CustomerEntity::getId, customerId).isNull(CustomerEntity::getDeletedAt);
        CustomerEntity customerEntity = this.customerEntityMapper.selectOne(queryWrapper);

        if (customerEntity == null) {
            throw new EntityNotFoundException("Customer", customerId);
        }
        AddressEntity createdAddressEntity = BeanMapper.map(input, AddressEntity.class);
        createdAddressEntity.setCustomerId(customerId);
        this.addressEntityMapper.insert(createdAddressEntity);
        this.enforceSingleDefaultAddress(
                createdAddressEntity.getId(), input.getDefaultBillingAddress(), input.getDefaultShippingAddress());

        CreateCustomerHistoryEntryArgs args = ServiceHelper.buildCreateCustomerHistoryEntryArgs(
                ctx, customerEntity.getId(), HistoryEntryType.CUSTOMER_ADDRESS_CREATED,
                ImmutableMap.of(HistoryService.KEY_ADDRESS, ServiceHelper.addressToLine(createdAddressEntity)));
        this.historyService.createHistoryEntryForCustomer(args);

        return createdAddressEntity;
    }

    public AddressEntity updateAddress(RequestContext ctx, UpdateAddressInput input) {
        AddressEntity addressEntity = ServiceHelper.getEntityOrThrow(this.addressEntityMapper, input.getId());
        BeanMapper.patch(input, addressEntity);
        this.addressEntityMapper.updateById(addressEntity);
        this.enforceSingleDefaultAddress(
                input.getId(), input.getDefaultBillingAddress(), input.getDefaultShippingAddress());

        Map<String, String> data = objectMapper.convertValue(input, Map.class);
        data.put(HistoryService.KEY_ADDRESS, ServiceHelper.addressToLine(addressEntity));
        CreateCustomerHistoryEntryArgs args = ServiceHelper.buildCreateCustomerHistoryEntryArgs(
                ctx, addressEntity.getCustomerId(), HistoryEntryType.CUSTOMER_ADDRESS_UPDATED, data);
        this.historyService.createHistoryEntryForCustomer(args);
        return addressEntity;
    }

    public boolean deleteAddress(RequestContext ctx, Long id) {
        AddressEntity addressEntity = ServiceHelper.getEntityOrThrow(this.addressEntityMapper, id);
        this.reassignDefaultsForDeletedAddress(addressEntity);
        CreateCustomerHistoryEntryArgs args = ServiceHelper.buildCreateCustomerHistoryEntryArgs(
                ctx, addressEntity.getCustomerId(), HistoryEntryType.CUSTOMER_ADDRESS_DELETED,
                ImmutableMap.of(HistoryService.KEY_ADDRESS, ServiceHelper.addressToLine(addressEntity)));
        this.historyService.createHistoryEntryForCustomer(args);
        this.addressEntityMapper.deleteById(id);
        return true;
    }

    public DeletionResponse softDelete(Long customerId) {
        CustomerEntity customerEntity = ServiceHelper.getEntityOrThrow(this.customerEntityMapper, customerId);
        customerEntity.setDeletedAt(new Date());
        this.customerEntityMapper.updateById(customerEntity);
        this.userService.softDelete(customerEntity.getUserId());
        DeletionResponse response = new DeletionResponse();
        response.setResult(DeletionResult.DELETED);
        return response;
    }

    public CustomerEntity addNoteToCustomer(RequestContext ctx, AddNoteToCustomerInput input) {
        CustomerEntity customerEntity = ServiceHelper.getEntityOrThrow(this.customerEntityMapper, input.getId());
        CreateCustomerHistoryEntryArgs args = ServiceHelper.buildCreateCustomerHistoryEntryArgs(
                ctx, customerEntity.getId(), HistoryEntryType.CUSTOMER_NOTE,
                ImmutableMap.of(HistoryService.KEY_NOTE, input.getNode()));
        this.historyService.createHistoryEntryForCustomer(args);
        return customerEntity;
    }

    public HistoryEntry updateCustomerNode(RequestContext ctx, UpdateCustomerNoteInput input) {
        UpdateCustomerHistoryEntryArgs args = ServiceHelper.buildUpdateCustomerHistoryEntryArgs(
                ctx, input.getNoteId(), HistoryEntryType.CUSTOMER_NOTE,
                ImmutableMap.of(HistoryService.KEY_NOTE, input.getNote())
        );
        return this.historyService.updateCustomerHistoryEntry(args);
    }

    public DeletionResponse deleteCustomerNote(RequestContext ctx, Long id) {
        DeletionResponse deletionResponse =  new DeletionResponse();
        try {
            this.historyService.deleteCustomerHistoryEntry(id);
            deletionResponse.setResult(DeletionResult.DELETED);
        } catch (Exception ex) {
            deletionResponse.setResult(DeletionResult.NOT_DELETED);
            deletionResponse.setMessage(ex.getMessage());
        }
        return deletionResponse;
    }

    private void enforceSingleDefaultAddress(
            Long addressId, Boolean defaultBillingAddress, Boolean defaultShippingAddress) {
        AddressEntity addressEntity = this.addressEntityMapper.selectById(addressId);
        if (addressEntity == null) return;

        QueryWrapper<AddressEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(AddressEntity::getCustomerId, addressEntity.getCustomerId())
                             .ne(AddressEntity::getId, addressId);
        List<AddressEntity> addressEntityList = this.addressEntityMapper.selectList(queryWrapper);
        if (CollectionUtils.isEmpty(addressEntityList)) return;

        if (BooleanUtils.isTrue(defaultBillingAddress)) {
            addressEntityList.forEach(addressToUpdate -> {
                addressToUpdate.setDefaultBillingAddress(false);
                this.addressEntityMapper.updateById(addressToUpdate);
            });
        }
        if (BooleanUtils.isTrue(defaultShippingAddress)) {
            addressEntityList.forEach(addressToUpdate -> {
                addressToUpdate.setDefaultShippingAddress(false);
                this.addressEntityMapper.updateById(addressToUpdate);
            });
        }
    }

    /**
     * If a Customer Address is to be deleted, check if it is assigned as a default for shipping or
     * billing. If so, attempt to transfer default status to one of the other addresses if there are any.
     */
    private void reassignDefaultsForDeletedAddress(AddressEntity addressToDelete) {
        if (!addressToDelete.isDefaultBillingAddress() && !addressToDelete.isDefaultShippingAddress()) {
            return;
        }
        QueryWrapper<AddressEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(AddressEntity::getCustomerId, addressToDelete.getCustomerId())
                .ne(AddressEntity::getId, addressToDelete.getId());
        List<AddressEntity> otherAddressEntityList = this.addressEntityMapper.selectList(queryWrapper);
        if (!CollectionUtils.isEmpty(otherAddressEntityList)) {
            otherAddressEntityList.sort((a, b) -> a.getId() < b.getId() ? -1 : 1);
            if (addressToDelete.isDefaultShippingAddress()) {
                otherAddressEntityList.get(0).setDefaultShippingAddress(true);
            }
            if (addressToDelete.isDefaultBillingAddress()) {
                otherAddressEntityList.get(0).setDefaultBillingAddress(true);
            }
            this.addressEntityMapper.updateById(otherAddressEntityList.get(0));
        }
    }

}
