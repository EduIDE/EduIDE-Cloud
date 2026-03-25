/********************************************************************************
 * Copyright (C) 2026 EclipseSource and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.common.k8s.resource.appdefinition;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize()
public class SidecarSpec {

    @JsonProperty("name")
    private String name;

    @JsonProperty("image")
    private String image;

    @JsonProperty("port")
    private int port;

    @JsonProperty("languages")
    private List<String> languages;

    @JsonProperty("cpuLimit")
    private String cpuLimit;

    @JsonProperty("memoryLimit")
    private String memoryLimit;

    @JsonProperty("cpuRequest")
    private String cpuRequest;

    @JsonProperty("memoryRequest")
    private String memoryRequest;

    @JsonProperty("mountWorkspace")
    private Boolean mountWorkspace;

    /**
     * Default constructor.
     */
    public SidecarSpec() {
    }

    public String getName() {
        return name;
    }

    public String getImage() {
        return image;
    }

    public int getPort() {
        return port;
    }

    public List<String> getLanguages() {
        return languages;
    }

    public String getCpuLimit() {
        return cpuLimit;
    }

    public String getMemoryLimit() {
        return memoryLimit;
    }

    public String getCpuRequest() {
        return cpuRequest;
    }

    public String getMemoryRequest() {
        return memoryRequest;
    }

    public boolean isMountWorkspace() {
        return mountWorkspace == null ? false : mountWorkspace;
    }

    @Override
    public String toString() {
        return "SidecarSpec [name=" + name + ", image=" + image + ", port=" + port + ", languages=" + languages
                + ", cpuLimit=" + cpuLimit + ", memoryLimit=" + memoryLimit + ", cpuRequest=" + cpuRequest
                + ", memoryRequest=" + memoryRequest + ", mountWorkspace=" + mountWorkspace + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SidecarSpec that = (SidecarSpec) o;
        return port == that.port
                && Objects.equals(mountWorkspace, that.mountWorkspace)
                && Objects.equals(name, that.name)
                && Objects.equals(image, that.image)
                && Objects.equals(languages, that.languages)
                && Objects.equals(cpuLimit, that.cpuLimit)
                && Objects.equals(memoryLimit, that.memoryLimit)
                && Objects.equals(cpuRequest, that.cpuRequest)
                && Objects.equals(memoryRequest, that.memoryRequest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, image, port, languages, cpuLimit, memoryLimit, cpuRequest, memoryRequest,
                mountWorkspace);
    }

}
