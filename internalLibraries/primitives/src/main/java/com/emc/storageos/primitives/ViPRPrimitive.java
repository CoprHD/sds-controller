/*
 * Copyright 2017 Dell Inc. or its subsidiaries.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.emc.storageos.primitives;

import java.net.URI;

import com.emc.storageos.db.client.model.ModelObject;
import com.emc.storageos.db.client.model.NamedURI;


/**
 * Base class for a primitive that represents a ViPR API call
 */
public abstract class ViPRPrimitive extends CustomServicesPrimitiveType {

    private final CustomServicesStaticPrimitiveModel model;
    public ViPRPrimitive(URI id, String name) {
        model = new CustomServicesStaticPrimitiveModel(id, name);
    }

    @Override
    public URI id() {
        return model.getId();
    }
    
    @Override 
    public String name() {
        return model.getLabel();
    }
    
    @Override 
    public String type() {
        return "vipr";
    }
    
    @Override 
    public StepType stepType() {
        return StepType.VIPR_REST;
    }
    
    @Override
    public NamedURI resource() {
        return null;
    }
    
    @Override
    public ModelObject asModelObject() {
        return model;
    }
    
    public abstract String path();

    public abstract String method();

    public abstract String body();

}
