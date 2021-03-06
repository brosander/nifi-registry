/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.registry.service.alias;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.nifi.registry.flow.VersionedFlowCoordinates;
import org.apache.nifi.registry.flow.VersionedProcessGroup;
import org.apache.nifi.registry.properties.NiFiRegistryProperties;
import org.apache.nifi.registry.provider.ProviderFactoryException;
import org.apache.nifi.registry.provider.StandardProviderFactory;
import org.apache.nifi.registry.url.aliaser.generated.Aliases;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class RegistryUrlAliasService {
    private static final String ALIASES_XSD = "/aliases.xsd";
    private static final String JAXB_GENERATED_PATH = "org.apache.nifi.registry.url.aliaser.generated";
    private static final JAXBContext JAXB_CONTEXT = initializeJaxbContext();

    /**
     * Load the JAXBContext.
     */
    private static JAXBContext initializeJaxbContext() {
        try {
            return JAXBContext.newInstance(JAXB_GENERATED_PATH, RegistryUrlAliasService.class.getClassLoader());
        } catch (JAXBException e) {
            throw new RuntimeException("Unable to create JAXBContext.", e);
        }
    }

    private final List<Pair<String, String>> aliases;

    @Autowired
    public RegistryUrlAliasService(NiFiRegistryProperties niFiRegistryProperties) {
        this(createAliases(niFiRegistryProperties));
    }

    private static List<Pair<String, String>> createAliases(NiFiRegistryProperties niFiRegistryProperties) {
        File configurationFile = niFiRegistryProperties.getRegistryAliasConfigurationFile();
        if (configurationFile.exists()) {
            try {
                // find the schema
                final SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                final Schema schema = schemaFactory.newSchema(StandardProviderFactory.class.getResource(ALIASES_XSD));

                // attempt to unmarshal
                final Unmarshaller unmarshaller = JAXB_CONTEXT.createUnmarshaller();
                unmarshaller.setSchema(schema);

                // set the holder for later use
                final JAXBElement<Aliases> element = unmarshaller.unmarshal(new StreamSource(configurationFile), Aliases.class);
                return element.getValue().getAlias().stream().map(a -> Pair.of(a.getInternal(), a.getExternal())).collect(Collectors.toList());
            } catch (SAXException | JAXBException e) {
                throw new ProviderFactoryException("Unable to load the registry alias configuration file at: " + configurationFile.getAbsolutePath(), e);
            }
        } else {
            return Collections.emptyList();
        }
    }

    public RegistryUrlAliasService(List<Pair<String, String>> aliases) {
        Pattern urlStart = Pattern.compile("^https?://");

        this.aliases = new ArrayList<>(aliases.size());

        for (Pair<String, String> alias : aliases) {
            String internal = alias.getKey();
            String external = alias.getValue();

            if (!urlStart.matcher(external).find()) {
                throw new IllegalArgumentException("Expected " + external + " to start with http:// or https://");
            }

            this.aliases.add(Pair.of(internal, external));
        }
    }

    public void setExternal(VersionedProcessGroup processGroup) {
        processGroup.getProcessGroups().forEach(this::setExternal);

        VersionedFlowCoordinates coordinates = processGroup.getVersionedFlowCoordinates();
        if (coordinates != null) {
            coordinates.setRegistryUrl(getExternal(coordinates.getRegistryUrl()));
        }
    }

    public String getExternal(String url) {
        for (Pair<String, String> alias : aliases) {
            String internal = alias.getKey();
            String external = alias.getValue();

            if (url.startsWith(internal)) {
                int internalLength = internal.length();
                if (url.length() == internalLength) {
                    return external;
                }
                return external + url.substring(internalLength);
            }
        }
        return url;
    }

    public void setInternal(VersionedProcessGroup processGroup) {
        processGroup.getProcessGroups().forEach(this::setInternal);

        VersionedFlowCoordinates coordinates = processGroup.getVersionedFlowCoordinates();
        if (coordinates != null) {
            coordinates.setRegistryUrl(getInternal(coordinates.getRegistryUrl()));
        }
    }

    public String getInternal(String url) {
        for (Pair<String, String> alias : aliases) {
            String internal = alias.getKey();
            String external = alias.getValue();

            if (url.startsWith(external)) {
                int externalLength = external.length();
                if (url.length() == externalLength) {
                    return internal;
                }
                return internal + url.substring(externalLength);
            }
        }
        return url;
    }
}
