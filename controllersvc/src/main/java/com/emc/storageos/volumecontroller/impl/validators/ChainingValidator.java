/*
 * Copyright (c) 2016 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.volumecontroller.impl.validators;

import com.emc.storageos.exceptions.DeviceControllerException;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Chains multiple {@link Validator} instances with a shared {@link ValidatorLogger}.
 * This class will execute each validation and then check to see if any validation
 * errors occurred, throwing an exception if so.
 */
public class ChainingValidator implements Validator {

    private static final Logger log = LoggerFactory.getLogger(ChainingValidator.class);

    private List<Validator> validators;
    private ValidatorLogger logger;
    private String type;

    public ChainingValidator(ValidatorLogger logger, String type) {
        validators = Lists.newArrayList();
        this.logger = logger;
        this.type = type;
    }

    public boolean addValidator(Validator validator) {
        return validators.add(validator);
    }

    @Override
    public boolean validate() throws Exception {
        try {
            for (Validator validator : validators) {
                validator.validate();
            }
        } catch (Exception e) {
            log.error("Exception occurred during validation: ", e);
            throw DeviceControllerException.exceptions.unexpectedCondition(e.getMessage());
        }

        if (logger.hasErrors()) {
            throw DeviceControllerException.exceptions.validationError(
                    type, logger.getMsgs().toString(), ValidatorLogger.CONTACT_EMC_SUPPORT);
        }

        return true;
    }
}
