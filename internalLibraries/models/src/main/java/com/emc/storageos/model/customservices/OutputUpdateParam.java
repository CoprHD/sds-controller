/*
 * Copyright 2016 Dell Inc. or its subsidiaries.
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
package com.emc.storageos.model.customservices;

import java.util.List;

import javax.xml.bind.annotation.XmlElement;

public class OutputUpdateParam {
    private List<String> add;
    private List<String> remove;

    @XmlElement(name = "add")
    public List<String> getAdd() {
        return add;
    }

    public void setAdd(final List<String> add) {
        this.add = add;
    }

    @XmlElement(name = "remove")
    public List<String> getRemove() {
        return remove;
    }

    public void setRemove(final List<String> remove) {
        this.remove = remove;
    }
}
