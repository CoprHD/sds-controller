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
package com.emc.storageos.primitives.db.script;

import java.util.Map;
import java.util.Set;

import com.emc.storageos.db.client.model.uimodels.CustomServicesDBScriptResource;
import com.emc.storageos.primitives.db.CustomServicesDBResourceType;

/**
 * Class that represents a shell script file as a java object
 *
 */
public class CustomServicesScriptResource extends CustomServicesDBResourceType<CustomServicesDBScriptResource> {


    public CustomServicesScriptResource(final CustomServicesDBScriptResource resource,
            final Map<String, Set<String>> attributes) {
        super(resource, attributes);
    }

    @Override
    public String suffix() {
        return ".sh";
    }

}
