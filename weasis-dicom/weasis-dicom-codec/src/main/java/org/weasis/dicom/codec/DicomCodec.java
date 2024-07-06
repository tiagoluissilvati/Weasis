/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.codec;

import java.net.URI;
import java.util.Hashtable;
import javax.imageio.spi.IIOServiceProvider;
import org.dcm4che3.data.SpecificCharacterSet;
import org.dcm4che3.img.DicomImageReaderSpi;
import org.dcm4che3.util.UIDUtils;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.media.data.Codec;
import org.weasis.core.api.media.data.MediaReader;
import org.weasis.imageio.codec.ImageioUtil;

@org.osgi.service.component.annotations.Component(service = Codec.class)
public class DicomCodec implements Codec<DicomImageElement> {
  private static final Logger LOGGER = LoggerFactory.getLogger(DicomCodec.class);

  public static final String NAME = "dcm4che"; // NON-NLS
  public static final String[] FILE_EXTENSIONS = {"dcm", "dic", "dicm", "dicom"}; // NON-NLS

  private static final String LOGGER_KEY = "always.info.ItemParser";
  private static final String LOGGER_VAL = "org.dcm4che3.imageio.ItemParser";

  private static final IIOServiceProvider[] dcm4cheCodecs = {new DicomImageReaderSpi()};

  @Override
  public String[] getReaderMIMETypes() {
    return new String[] {
      DicomMediaIO.DICOM_MIMETYPE,
      DicomMediaIO.SERIES_XDSI,
      DicomMediaIO.IMAGE_MIMETYPE,
      DicomMediaIO.SERIES_VIDEO_MIMETYPE,
      DicomMediaIO.SERIES_ENCAP_DOC_MIMETYPE
    };
  }

  @Override
  public String[] getReaderExtensions() {
    return FILE_EXTENSIONS;
  }

  @Override
  public boolean isMimeTypeSupported(String mimeType) {
    if (mimeType != null) {
      for (String mime : getReaderMIMETypes()) {
        if (mimeType.equals(mime)) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public MediaReader<DicomImageElement> getMediaIO(
      URI media, String mimeType, Hashtable<String, Object> properties) {
    if (isMimeTypeSupported(mimeType)) {
      return new DicomMediaIO(media);
    }
    return null;
  }

  @Override
  public String getCodecName() {
    return NAME;
  }

  @Override
  public String[] getWriterExtensions() {
    return FILE_EXTENSIONS;
  }

  @Override
  public String[] getWriterMIMETypes() {
    return new String[] {DicomMediaIO.DICOM_MIMETYPE};
  }

  // ================================================================================
  // OSGI service implementation
  // ================================================================================

  @Activate
  protected void activate(ComponentContext context) {
    LOGGER.info("Activate DicomCodec");

    /**
     * Set value for dicom root UID which should be registered at the
     * https://www.iana.org/assignments/enterprise-numbers <br>
     * Default value is 2.25, this enables users to generate OIDs without any registration procedure
     *
     * @see http://www.dclunie.com/medical-image-faq/html/part2.html#UUID <br>
     *     http://www.oid-info.com/get/2.25 <br>
     *     http://www.itu.int/ITU-T/asn1/uuid.html<br>
     *     http://healthcaresecprivacy.blogspot.ch/2011/02/creating-and-using-unique-id-uuid-oid.html
     */
    String weasisRootUID =
        GuiUtils.getUICore()
            .getSystemPreferences()
            .getProperty("weasis.dicom.root.uid", UIDUtils.getRoot());
    UIDUtils.setRoot(weasisRootUID);

    // Set the default encoding (must contain ASCII)
    SpecificCharacterSet.setDefaultCharacterSet("ISO_IR 100"); // NON-NLS

    for (IIOServiceProvider p : dcm4cheCodecs) {
      ImageioUtil.registerServiceProvider(p);
    }
  }

  @Deactivate
  protected void deactivate(ComponentContext context) {
    LOGGER.info("Deactivate DicomCodec");
    for (IIOServiceProvider p : dcm4cheCodecs) {
      ImageioUtil.deregisterServiceProvider(p);
    }
  }

  @Reference(
      service = DicomSpecialElementFactory.class,
      cardinality = ReferenceCardinality.MULTIPLE,
      policy = ReferencePolicy.DYNAMIC,
      unbind = "removeDicomSpecialElementFactory")
  void addDicomSpecialElementFactory(DicomSpecialElementFactory factory) {
    String name = factory.getClass().getName();
    for (String modality : factory.getModalities()) {
      DicomSpecialElementFactory prev = DicomMediaIO.DCM_ELEMENT_FACTORIES.put(modality, factory);
      if (prev != null) {
        LOGGER.warn("{} factory has been replaced by {}", prev.getClass().getName(), name);
      }
      LOGGER.info("Register DicomSpecialElementFactory: {} => {}", modality, name);
    }
  }

  void removeDicomSpecialElementFactory(DicomSpecialElementFactory factory) {
    String name = factory.getClass().getName();
    for (String modality : factory.getModalities()) {
      DicomSpecialElementFactory f = DicomMediaIO.DCM_ELEMENT_FACTORIES.get(modality);
      if (factory.equals(f)) {
        DicomMediaIO.DCM_ELEMENT_FACTORIES.remove(modality);
        LOGGER.info("Unregister DicomSpecialElementFactory: {} => {}", modality, name);
      } else {
        LOGGER.warn(
            "{}: Unregistering {} has no effect, {} is registered instead",
            modality,
            name,
            f.getClass().getName());
      }
    }
  }
}
