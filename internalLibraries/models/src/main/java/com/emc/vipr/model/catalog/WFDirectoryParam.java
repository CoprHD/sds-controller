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
package com.emc.vipr.model.catalog;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Rest representation for WF directory create
 */
@XmlRootElement(name = "wf_directory_create")
public class WFDirectoryParam {

    private String name;
    private URI parent;
    private List<URI> workflows;

    @XmlElement(required =true)
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public URI getParent() {
        return parent;
    }

    public void setParent(URI parent) {
        this.parent = parent;
    }

    @XmlElementWrapper(name = "workflows")
    @XmlElement(name = "workflow")
    public List<URI> getWorkflows() {
        if (workflows == null) {
            workflows = new ArrayList<>();
        }
        return workflows;
    }

    public void setWorkflows(List<URI> workflows) {
        this.workflows = workflows;
    }
}
