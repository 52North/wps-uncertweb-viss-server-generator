/**
 * Copyright (C) 2007 - 2015 52°North Initiative for Geospatial Open Source
 * Software GmbH
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 as published
 * by the Free Software Foundation.
 *
 * If the program is linked with libraries which are licensed under one of
 * the following licenses, the combination of the program with the linked
 * library is not considered a "derivative work" of the program:
 *
 *       • Apache License, version 2.0
 *       • Apache Software License, version 1.0
 *       • GNU Lesser General Public License, version 3
 *       • Mozilla Public License, versions 1.0, 1.1 and 2.0
 *       • Common Development and Distribution License (CDDL), version 1.0
 *
 * Therefore the distribution of the program linked with libraries licensed
 * under the aforementioned licenses, is permitted by the copyright holders
 * if the distribution is compliant with both the GNU General Public
 * License version 2 and the aforementioned licenses.
 *
 * As an exception to the terms of the GPL, you may copy, modify,
 * propagate, and distribute a work formed by combining 52°North WPS
 * GeoTools Modules with the Eclipse Libraries, or a work derivative of
 * such a combination, even if such copying, modification, propagation, or
 * distribution would otherwise violate the terms of the GPL. Nothing in
 * this exception exempts you from complying with the GPL in all respects
 * for all of the code used other than the Eclipse Libraries. You may
 * include this exception and its grant of permissions when you distribute
 * 52°North WPS GeoTools Modules. Inclusion of this notice with such a
 * distribution constitutes a grant of such permissions. If you do not wish
 * to grant these permissions, remove this paragraph from your
 * distribution. "52°North WPS GeoTools Modules" means the 52°North WPS
 * modules using GeoTools functionality - software licensed under version 2
 * or any later version of the GPL, or a work based on such software and
 * licensed under the GPL. "Eclipse Libraries" means Eclipse Modeling
 * Framework Project and XML Schema Definition software distributed by the
 * Eclipse Foundation and licensed under the Eclipse Public License Version
 * 1.0 ("EPL"), or a work based on such software and licensed under the EPL.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 */
package org.n52.geoprocessing.wps.generator;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.httpclient.HttpException;
import org.n52.wps.commons.WPSConfig;
import org.n52.wps.generator.module.UncertWebVissServerWMSGeneratorCM;
import org.n52.wps.io.data.GenericFileDataWithGT;
import org.n52.wps.io.data.IData;
import org.n52.wps.io.data.binding.complex.GTRasterDataBinding;
import org.n52.wps.io.data.binding.complex.GenericFileDataWithGTBinding;
import org.n52.wps.io.data.binding.complex.GeotiffBinding;
import org.n52.wps.io.datahandler.generator.AbstractGenerator;
import org.n52.wps.server.database.DatabaseFactory;
import org.n52.wps.webapp.api.ConfigurationCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generator for storing raster datasets in the UncertWeb Viss Client. A JSON
 * String containing timestamps and WMS URLs will be returned.
 * 
 * @author bpr
 *
 */
public class UncertWebVissServerWMSGenerator extends AbstractGenerator {

    private static Logger LOGGER = LoggerFactory.getLogger(UncertWebVissServerWMSGenerator.class);

    // private String geoserverHost;
    // private String geoserverPort;
    private String vissServerHost;

    public UncertWebVissServerWMSGenerator() {
        super();
        this.supportedIDataTypes.add(GTRasterDataBinding.class);
        this.supportedIDataTypes.add(GeotiffBinding.class);
        this.supportedIDataTypes.add(GenericFileDataWithGTBinding.class);

        UncertWebVissServerWMSGeneratorCM configurationModule =
                (UncertWebVissServerWMSGeneratorCM) WPSConfig.getInstance()
                        .getConfigurationModuleForClass(this.getClass().getName(), ConfigurationCategory.GENERATOR);

        // geoserverHost = configurationModule.getGeoserverHost();
        // geoserverPort = configurationModule.getGeoserverPort();
        vissServerHost = configurationModule.getVissServerHost();
    }

    @Override
    public InputStream generateStream(IData data,
            String mimeType,
            String schema) throws IOException {

        InputStream stream = null;
        try {
            String urlString = storeLayer(data);
            stream = new ByteArrayInputStream(urlString.getBytes("UTF-8"));
        } catch (IOException e) {
            LOGGER.error("Error generating WMS output. Reason: ", e);
            throw new RuntimeException("Error generating WMS output. Reason: " + e);
        } catch (ParserConfigurationException e) {
            LOGGER.error("Error generating WMS output. Reason: ", e);
            throw new RuntimeException("Error generating WMS output. Reason: " + e);
        }
        return stream;
    }

    public String storeLayer(IData coll) throws HttpException, IOException, ParserConfigurationException {
        File file = null;
        if (coll instanceof GTRasterDataBinding) {
            GTRasterDataBinding gtData = (GTRasterDataBinding) coll;
            GenericFileDataWithGT fileData = new GenericFileDataWithGT(gtData.getPayload(), null);
            file = fileData.getBaseFile(true);
        }
        if (coll instanceof GeotiffBinding) {
            GeotiffBinding data = (GeotiffBinding) coll;
            file = (File) data.getPayload();
        }
        if (coll instanceof GenericFileDataWithGTBinding) {
            file = ((GenericFileDataWithGTBinding) coll).getPayload().getBaseFile(true);
        }

        InputStream stream = new FileInputStream(file);

        String resourceURL = DatabaseFactory.getDatabase().storeComplexValue(UUID.randomUUID() + "viss-result", stream,
                "ComplexDataResponse", "application/x-uncertweb-viss-wms");

        LOGGER.info("ResourceURL: " + resourceURL);

        return new UncertWebVissServiceUploader(vissServerHost).createVissResource(resourceURL);
    }

}
