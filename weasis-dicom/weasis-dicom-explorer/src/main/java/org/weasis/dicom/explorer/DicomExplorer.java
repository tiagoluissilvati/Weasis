/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer;

import bibliothek.gui.dock.common.CLocation;
import bibliothek.gui.dock.common.mode.ExtendedMode;
import bibliothek.gui.dock.control.focus.DefaultFocusRequest;
import com.formdev.flatlaf.ui.FlatUIUtils;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.font.FontRenderContext;
import java.beans.PropertyChangeEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Function;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import net.miginfocom.swing.MigLayout;
import org.dcm4che3.data.Tag;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.explorer.DataExplorerView;
import org.weasis.core.api.explorer.ObservableEvent;
import org.weasis.core.api.explorer.model.DataExplorerModel;
import org.weasis.core.api.gui.Insertable;
import org.weasis.core.api.gui.util.ActionW;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.media.data.MediaElement;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.core.api.media.data.MediaSeriesGroupNode;
import org.weasis.core.api.media.data.Series;
import org.weasis.core.api.media.data.SeriesThumbnail;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.api.media.data.Thumbnail;
import org.weasis.core.api.util.FontItem;
import org.weasis.core.api.util.ResourceUtil;
import org.weasis.core.api.util.ResourceUtil.OtherIcon;
import org.weasis.core.api.util.ResourceUtil.ResourceIconPath;
import org.weasis.core.ui.docking.PluginTool;
import org.weasis.core.ui.editor.SeriesViewer;
import org.weasis.core.ui.editor.SeriesViewerEvent;
import org.weasis.core.ui.editor.SeriesViewerEvent.EVENT;
import org.weasis.core.ui.editor.SeriesViewerListener;
import org.weasis.core.ui.editor.image.ImageViewerPlugin;
import org.weasis.core.ui.editor.image.SequenceHandler;
import org.weasis.core.ui.editor.image.ViewCanvas;
import org.weasis.core.ui.editor.image.ViewerPlugin;
import org.weasis.core.ui.util.ArrayListComboBoxModel;
import org.weasis.core.ui.util.DefaultAction;
import org.weasis.core.ui.util.TitleMenuItem;
import org.weasis.core.ui.util.WrapLayout;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomSpecialElement;
import org.weasis.dicom.codec.HiddenSeriesManager;
import org.weasis.dicom.codec.KOSpecialElement;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.codec.TagD.Level;
import org.weasis.dicom.explorer.HangingProtocols.OpeningViewer;
import org.weasis.dicom.explorer.wado.LoadSeries;

public class DicomExplorer extends PluginTool implements DataExplorerView, SeriesViewerListener {

  private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(DicomExplorer.class);

  public enum ListPosition {
    FIRST,
    PREVIOUS,
    NEXT,
    LAST
  }

  public static final String NAME = Messages.getString("DicomExplorer.title");
  public static final String PREFERENCE_NODE = "dicom.explorer";
  public static final String BUTTON_NAME = Messages.getString("DicomExplorer.btn_title");
  public static final String DESCRIPTION = Messages.getString("DicomExplorer.desc");
  public static final String ALL_PATIENTS = Messages.getString("DicomExplorer.sel_all_pat");
  public static final String ALL_STUDIES = Messages.getString("DicomExplorer.sel_all_st");

  private final PatientPane selectedPatient = new PatientPane();

  private final HashMap<MediaSeriesGroup, List<StudyPane>> patient2study = new HashMap<>();
  private final HashMap<MediaSeriesGroup, List<SeriesPane>> study2series = new HashMap<>();
  private final JScrollPane thumbnailView = new JScrollPane();
  private final LoadingPanel loadingPanel = new LoadingPanel();
  private final SeriesSelectionModel selectionList;

  private final DicomModel model;

  private final ArrayListComboBoxModel<Object> modelPatient =
      new ArrayListComboBoxModel<>(DicomSorter.PATIENT_COMPARATOR);
  private final ArrayListComboBoxModel<Object> modelStudy =
      new ArrayListComboBoxModel<>(DicomSorter.STUDY_COMPARATOR);
  private final JComboBox<?> patientCombobox = new JComboBox<>(modelPatient);
  private final JComboBox<?> studyCombobox = new JComboBox<>(modelStudy);
  private final transient ItemListener studyItemListener =
      e -> {
        if (e.getStateChange() == ItemEvent.SELECTED) {
          selectStudy();
        }
      };
  private JPanel panelMain = null;
  private final boolean verticalLayout = true;

  private final JButton koOpen =
      new JButton(
          Messages.getString("DicomExplorer.open_ko"), ResourceUtil.getIcon(OtherIcon.KEY_IMAGE));

  public DicomExplorer() {
    this(null);
  }

  public DicomExplorer(DicomModel model) {
    super(NAME, POSITION.WEST, ExtendedMode.NORMALIZED, Insertable.Type.EXPLORER, 20);
    dockable.setMaximizable(true);
    this.model = model == null ? new DicomModel() : model;
    this.selectionList = new SeriesSelectionModel(selectedPatient);
    int thumbnailSize =
        GuiUtils.getUICore()
            .getSystemPreferences()
            .getIntProperty(Thumbnail.KEY_SIZE, Thumbnail.DEFAULT_SIZE);
    setDockableWidth(Math.max(thumbnailSize, Thumbnail.DEFAULT_SIZE) + 42);

    patientCombobox.setMaximumRowCount(15);
    ItemListener patientChangeListener =
        e -> {
          if (e.getStateChange() == ItemEvent.SELECTED) {
            selectPatient(getSelectedPatient());
            selectedPatient.revalidate();
            selectedPatient.repaint();
          }
        };
    patientCombobox.addItemListener(patientChangeListener);
    studyCombobox.setMaximumRowCount(15);
    // do not use addElement
    modelStudy.insertElementAt(ALL_STUDIES, 0);
    modelStudy.setSelectedItem(ALL_STUDIES);
    studyCombobox.addItemListener(studyItemListener);

    thumbnailView.setBorder(BorderFactory.createEmptyBorder()); // remove default line
    thumbnailView.getVerticalScrollBar().setUnitIncrement(16);
    thumbnailView.setViewportView(selectedPatient);
    thumbnailView.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
    thumbnailView.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    changeToolWindowAnchor(getDockable().getBaseLocation());
    setTransferHandler(new SeriesHandler());
  }

  public SeriesSelectionModel getSelectionList() {
    return selectionList;
  }

  private void removePatientPane(MediaSeriesGroup patient) {
    if (patient != null) {
      List<StudyPane> studies = patient2study.remove(patient);
      if (studies != null) {
        for (StudyPane studyPane : studies) {
          study2series.remove(studyPane.dicomStudy);
        }
      }
      modelPatient.removeElement(patient);
      if (modelPatient.getSize() == 0) {
        modelStudy.removeAllElements();
        modelStudy.insertElementAt(ALL_STUDIES, 0);
        modelStudy.setSelectedItem(ALL_STUDIES);
        koOpen.setVisible(false);
      }
      if (selectedPatient.isPatient(patient)) {
        selectedPatient.patient = null;
      }
    }
  }

  private void removeStudy(MediaSeriesGroup study) {
    MediaSeriesGroup patient = model.getParent(study, DicomModel.patient);
    List<StudyPane> studies = patient2study.get(patient);
    if (studies != null) {
      for (int i = studies.size() - 1; i >= 0; i--) {
        StudyPane st = studies.get(i);
        if (st.isStudy(study)) {
          studies.remove(i);
          if (studies.isEmpty()) {
            patient2study.remove(patient);
            // throw a new event for removing the patient
            model.removePatient(patient);
            break;
          }
          study2series.remove(study);
          if (selectedPatient.isStudyVisible(study)) {
            selectedPatient.remove(st);
            modelStudy.removeElement(study);
            selectedPatient.revalidate();
            selectedPatient.repaint();
          }
          return;
        }
      }
    }
    study2series.remove(study);
  }

  private void removeSeries(MediaSeriesGroup series) {
    MediaSeriesGroup study = model.getParent(series, DicomModel.study);
    List<SeriesPane> seriesList = study2series.get(study);
    if (seriesList != null) {
      for (int j = seriesList.size() - 1; j >= 0; j--) {
        SeriesPane se = seriesList.get(j);
        if (se.isSeries(series)) {
          seriesList.remove(j);
          if (seriesList.isEmpty()) {
            study2series.remove(study);
            // throw a new event for removing the patient
            model.removeStudy(study);
            break;
          }
          se.removeAll();

          StudyPane studyPane = getStudyPane(study);
          if (studyPane != null && studyPane.isSeriesVisible(series)) {
            studyPane.remove(se);
            studyPane.revalidate();
            studyPane.repaint();
          }
          break;
        }
      }
    }
  }

  private StudyPane getStudyPane(MediaSeriesGroup study) {
    List<StudyPane> studies = patient2study.get(model.getParent(study, DicomModel.patient));
    if (studies != null) {
      for (StudyPane st : studies) {
        if (st.isStudy(study)) {
          return st;
        }
      }
    }
    return null;
  }

  private StudyPane createStudyPaneInstance(MediaSeriesGroup study, int[] position) {
    StudyPane studyPane = getStudyPane(study);
    if (studyPane == null) {
      studyPane = new StudyPane(study);
      List<StudyPane> studies = patient2study.get(model.getParent(study, DicomModel.patient));
      if (studies != null) {
        int index = Collections.binarySearch(studies, studyPane, DicomSorter.STUDY_COMPARATOR);
        if (index < 0) {
          index = -(index + 1);
        }
        if (position != null) {
          position[0] = index;
        }
        studies.add(index, studyPane);
      }
    } else if (position != null) {
      position[0] = -1;
    }
    return studyPane;
  }

  public void updateThumbnailSize(int thumbnailSize) {
    updateDockableWidth(Math.max(thumbnailSize, Thumbnail.DEFAULT_SIZE) + 42);
    for (StudyPane studyPane : selectedPatient.getStudyPaneList()) {
      for (SeriesPane series : studyPane.getSeriesPaneList()) {
        series.updateSize(thumbnailSize);
      }
      studyPane.doLayout();
    }
    selectedPatient.revalidate();
    selectedPatient.repaint();
  }

  private SeriesPane getSeriesPane(MediaSeriesGroup series) {
    List<SeriesPane> seriesList = study2series.get(model.getParent(series, DicomModel.study));
    if (seriesList != null) {
      for (SeriesPane se : seriesList) {
        if (se.isSeries(series)) {
          return se;
        }
      }
    }
    return null;
  }

  private MediaSeriesGroupNode getPatient(Object item) {
    if (item instanceof MediaSeriesGroupNode patient) {
      return patient;
    }
    return null;
  }

  public MediaSeriesGroupNode getSelectedPatient() {
    return getPatient(modelPatient.getSelectedItem());
  }

  public MediaSeries<? extends MediaElement> movePatient(
      ViewCanvas<DicomImageElement> view, ListPosition position) {
    if (view != null) {
      MediaSeriesGroup patientGroup;
      MediaSeriesGroup seriesGroup;
      MediaSeriesGroup series = view.getSeries();
      if (series == null) {
        seriesGroup = getSeries(null, null, ListPosition.FIRST);
        MediaSeriesGroup studyGroup = model.getParent(seriesGroup, DicomModel.study);
        patientGroup = model.getParent(studyGroup, DicomModel.patient);
      } else {
        MediaSeriesGroup studyGroup = model.getParent(series, DicomModel.study);
        patientGroup = model.getParent(studyGroup, DicomModel.patient);
        seriesGroup = getSeriesGroupFromPatient(patientGroup, position);
      }

      if (seriesGroup instanceof MediaSeries<?> dicomSeries) {
        if (isPatientHasOpenSeries(patientGroup)) {
          displaySeries(view, seriesGroup);
        } else {
          ThumbnailMouseAndKeyAdapter.openSeriesInDefaultPlugin(
              model, (MediaSeries<? extends MediaElement>) dicomSeries);
        }
        return (MediaSeries<? extends MediaElement>) dicomSeries;
      }
    }
    return null;
  }

  public MediaSeriesGroup getSeriesGroupFromPatient(
      MediaSeriesGroup patientGroup, ListPosition position) {
    MediaSeriesGroup seriesGroup;
    if (patientGroup == null) {
      seriesGroup = getSeries(null, null, ListPosition.FIRST);
    } else {
      MediaSeriesGroup patient = getPatientFromList(patientGroup, position);
      seriesGroup = getSeries(getFirstStudy(patient), null, ListPosition.FIRST);
    }
    return seriesGroup;
  }

  private MediaSeriesGroup getPatientFromList(
      MediaSeriesGroup patientGroup, ListPosition position) {
    if (patientGroup == null) {
      return getSelectedPatient();
    } else {
      if (modelPatient.getSize() > 0) {
        int index = 0;
        if (position == ListPosition.LAST) {
          index = modelPatient.getSize() - 1;
        } else if (position == ListPosition.PREVIOUS) {
          index = getPatientIndex(patientGroup) - 1;
        } else if (position == ListPosition.NEXT) {
          index = getPatientIndex(patientGroup) + 1;
        }
        if (index >= 0 && index < modelPatient.getSize()) {
          Object object = modelPatient.getElementAt(index);
          if (object instanceof MediaSeriesGroupNode patient) {
            return patient;
          }
        }
      }
    }
    return null;
  }

  private int getPatientIndex(MediaSeriesGroup patientGroup) {
    if (patientGroup != null) {
      synchronized (modelPatient) {
        for (int i = 0; i < modelPatient.getSize(); i++) {
          if (patientGroup.equals(modelPatient.getElementAt(i))) {
            return i;
          }
        }
      }
    }
    return 0;
  }

  private MediaSeriesGroup getFirstStudy(MediaSeriesGroup patientGroup) {
    List<StudyPane> studyList = getStudyList(patientGroup);
    if (studyList != null && !studyList.isEmpty()) {
      return studyList.getFirst().dicomStudy;
    }
    return null;
  }

  public MediaSeries<? extends MediaElement> moveStudy(
      ViewCanvas<DicomImageElement> view, ListPosition position) {
    if (view != null) {
      MediaSeriesGroup seriesGroup;
      MediaSeriesGroup series = view.getSeries();
      if (series == null) {
        seriesGroup = getSeries(null, null, ListPosition.FIRST);
      } else {
        seriesGroup = getSeriesGroupFromStudy(model.getParent(series, DicomModel.study), position);
      }
      return displaySeries(view, seriesGroup);
    }
    return null;
  }

  public MediaSeriesGroup getSeriesGroupFromStudy(
      MediaSeriesGroup studyGroup, ListPosition position) {
    MediaSeriesGroup seriesGroup;
    if (studyGroup == null) {
      seriesGroup = getSeries(null, null, ListPosition.FIRST);
    } else {
      MediaSeriesGroup study = getStudyFromList(studyGroup, position);
      seriesGroup = getSeries(study, null, ListPosition.FIRST);
    }
    return seriesGroup;
  }

  private List<StudyPane> getStudyList(MediaSeriesGroup patient) {
    MediaSeriesGroupNode patientNode = getPatient(patient);
    if (patient == null) {
      patientNode = getSelectedPatient();
    }
    return patient2study.get(patientNode);
  }

  private MediaSeriesGroup getStudyFromList(MediaSeriesGroup studyGroup, ListPosition position) {
    MediaSeriesGroup patientGroup = model.getParent(studyGroup, DicomModel.patient);
    if (patientGroup == null || studyGroup == null) {
      return getFirstStudy(patientGroup);
    } else {
      List<StudyPane> studyPanes = patient2study.get(patientGroup);
      if (studyPanes != null && !studyPanes.isEmpty()) {
        int index = 0;
        if (position == ListPosition.LAST) {
          index = studyPanes.size() - 1;
        } else if (position == ListPosition.PREVIOUS) {
          index = getStudyIndex(studyPanes, studyGroup) - 1;
        } else if (position == ListPosition.NEXT) {
          index = getStudyIndex(studyPanes, studyGroup) + 1;
        }
        if (index >= 0 && index < studyPanes.size()) {
          return studyPanes.get(index).dicomStudy;
        }
      }
    }
    return null;
  }

  private int getStudyIndex(List<StudyPane> seriesList, MediaSeriesGroup studyGroup) {
    if (seriesList != null && !seriesList.isEmpty()) {
      for (int i = 0; i < seriesList.size(); i++) {
        StudyPane se = seriesList.get(i);
        if (se.isStudy(studyGroup)) {
          return i;
        }
      }
    }
    return 0;
  }

  public MediaSeries<? extends MediaElement> moveSeries(
      ViewCanvas<DicomImageElement> view, ListPosition position) {
    if (view != null) {
      MediaSeriesGroup seriesGroup = getSeriesGroup(view.getSeries(), position);
      return displaySeries(view, seriesGroup);
    }
    return null;
  }

  private MediaSeries<? extends MediaElement> displaySeries(
      ViewCanvas<DicomImageElement> view, MediaSeriesGroup seriesGroup) {
    if (view != null && seriesGroup instanceof MediaSeries<?> dicomSeries) {
      view.setSeries(null);
      view.setSeries((MediaSeries<DicomImageElement>) dicomSeries, null);
      return (MediaSeries<? extends MediaElement>) dicomSeries;
    }
    return null;
  }

  public MediaSeriesGroup getSeriesGroup(MediaSeriesGroup series, ListPosition position) {
    MediaSeriesGroup seriesGroup;
    if (series == null) {
      seriesGroup = getSeries(null, null, position);
    } else {
      MediaSeriesGroup studyGroup = model.getParent(series, DicomModel.study);
      seriesGroup = getSeries(studyGroup, series, position);
    }
    return seriesGroup;
  }

  private MediaSeriesGroup getSeries(
      MediaSeriesGroup studyGroup, MediaSeriesGroup series, ListPosition position) {
    if (studyGroup == null || series == null) {
      List<StudyPane> studyList;
      if (studyGroup == null) {
        studyList = getStudyList(null);
      } else {
        return getSeriesFromList(studyGroup, null, ListPosition.FIRST);
      }
      if (studyList != null) {
        for (StudyPane studyPane : studyList) {
          List<SeriesPane> seriesList = study2series.get(studyPane.dicomStudy);
          if (seriesList != null && !seriesList.isEmpty()) {
            return seriesList.getFirst().sequence;
          }
        }
      }
    } else {
      return getSeriesFromList(studyGroup, series, position);
    }
    return null;
  }

  private MediaSeriesGroup getSeriesFromList(
      MediaSeriesGroup studyGroup, MediaSeriesGroup series, ListPosition position) {
    List<SeriesPane> seriesList = study2series.get(studyGroup);
    if (seriesList != null && !seriesList.isEmpty()) {
      int index = 0;
      if (position == ListPosition.LAST) {
        index = seriesList.size() - 1;
      } else if (position == ListPosition.PREVIOUS) {
        index = getSeriesIndex(seriesList, series) - 1;
      } else if (position == ListPosition.NEXT) {
        index = getSeriesIndex(seriesList, series) + 1;
      }
      if (index >= 0 && index < seriesList.size()) {
        return seriesList.get(index).sequence;
      }
    }
    return null;
  }

  private int getSeriesIndex(List<SeriesPane> seriesList, MediaSeriesGroup series) {
    if (seriesList != null && !seriesList.isEmpty()) {
      for (int i = 0; i < seriesList.size(); i++) {
        SeriesPane se = seriesList.get(i);
        if (se.isSeries(series)) {
          return i;
        }
      }
    }
    return 0;
  }

  private synchronized SeriesPane createSeriesPaneInstance(
      MediaSeriesGroup series, int[] position) {
    SeriesPane seriesPane = getSeriesPane(series);
    if (seriesPane == null) {
      seriesPane = new SeriesPane(series);
      List<SeriesPane> seriesList = study2series.get(model.getParent(series, DicomModel.study));
      if (seriesList != null) {
        int index = Collections.binarySearch(seriesList, seriesPane, DicomSorter.SERIES_COMPARATOR);
        if (index < 0) {
          index = -(index + 1);
        }
        if (position != null) {
          position[0] = index;
        }
        seriesList.add(index, seriesPane);
      }
    } else if (position != null) {
      position[0] = -1;
    }
    return seriesPane;
  }

  private boolean isSelectedPatient(MediaSeriesGroup patient) {
    return Objects.equals(selectedPatient.patient, patient);
  }

  class PatientPane extends JPanel {

    private MediaSeriesGroup patient;

    public PatientPane() {
      this.setAlignmentX(LEFT_ALIGNMENT);
      this.setAlignmentY(TOP_ALIGNMENT);
      this.setFocusable(false);
      refreshLayout();
    }

    public void setPatient(MediaSeriesGroup patient) {
      this.patient = patient;
    }

    public void showTitle(boolean show) {
      if (show && patient != null) {
        TitledBorder title = GuiUtils.getTitledBorder(patient.toString());
        this.setBorder(
            BorderFactory.createCompoundBorder(GuiUtils.getEmptyBorder(0, 3, 5, 3), title));
      } else {
        this.setBorder(BorderFactory.createEmptyBorder());
      }
    }

    public boolean isStudyVisible(MediaSeriesGroup study) {
      for (Component c : this.getComponents()) {
        if (c instanceof StudyPane studyPane && studyPane.isStudy(study)) {
          return true;
        }
      }
      return false;
    }

    public boolean isSeriesVisible(MediaSeriesGroup series) {
      MediaSeriesGroup study = model.getParent(series, DicomModel.study);
      for (Component c : this.getComponents()) {
        if (c instanceof StudyPane studyPane
            && studyPane.isStudy(study)
            && studyPane.isSeriesVisible(series)) {
          return true;
        }
      }
      return false;
    }

    List<StudyPane> getStudyPaneList() {
      ArrayList<StudyPane> studyPaneList = new ArrayList<>();
      for (Component c : this.getComponents()) {
        if (c instanceof StudyPane studyPane) {
          studyPaneList.add(studyPane);
        }
      }
      return studyPaneList;
    }

    private void refreshLayout() {
      this.setLayout(
          verticalLayout
              ? new MigLayout("fillx, flowy, insets 0", "[fill]")
              : new MigLayout("fillx, flowx, insets 0", "[fill]"));
      List<StudyPane> studies = getStudyPaneList();
      super.removeAll();
      for (StudyPane studyPane : studies) {
        // Force to resize study pane
        studyPane.setSize(50, 50);
        if (studyPane.getComponentCount() > 0) {
          addPane(studyPane);
        }
        studyPane.doLayout();
      }
      this.revalidate();
    }

    private void showAllStudies() {
      super.removeAll();
      List<StudyPane> studies = patient2study.get(patient);
      if (studies != null) {
        for (StudyPane studyPane : studies) {
          studyPane.showAllSeries();
          if (studyPane.getComponentCount() > 0) {
            addPane(studyPane);
          }
          studyPane.doLayout();
        }
        this.revalidate();
      }
    }

    public void addPane(StudyPane studyPane) {
      add(studyPane);
    }

    public boolean isPatient(MediaSeriesGroup patient) {
      return Objects.equals(this.patient, patient);
    }
  }

  class StudyPane extends JPanel {

    private final JPanel sub = new JPanel(new WrapLayout());
    final MediaSeriesGroup dicomStudy;
    private final TitledBorder title;

    public StudyPane(MediaSeriesGroup dicomStudy) {
      super(new MigLayout("fillx, flowy, insets 0", "[fill]")); // NON-NLS
      if (dicomStudy == null) {
        throw new IllegalArgumentException("Study cannot be null");
      }
      this.dicomStudy = dicomStudy;
      title = GuiUtils.getTitledBorder(dicomStudy.toString());
      this.setBorder(
          BorderFactory.createCompoundBorder(GuiUtils.getEmptyBorder(0, 3, 0, 3), title));
      this.setFocusable(false);
      this.add(sub, "shrinky 100");
      this.addComponentListener(
          new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
              refreshLayout();
              StudyPane.this.revalidate();
              StudyPane.this.getParent().repaint();
            }
          });
    }

    @Override
    public void remove(int index) {
      sub.remove(index);
      refreshLayout();
    }

    @Override
    public void remove(Component comp) {
      sub.remove(comp);
      refreshLayout();
    }

    public void refreshLayout() {
      WrapLayout wl = (WrapLayout) sub.getLayout();
      sub.setPreferredSize(wl.preferredLayoutSize(sub));
    }

    public boolean isSeriesVisible(MediaSeriesGroup series) {
      for (Component c : sub.getComponents()) {
        if (c instanceof SeriesPane seriesPane && seriesPane.isSeries(series)) {
          return true;
        }
      }
      return false;
    }

    List<SeriesPane> getSeriesPaneList() {
      ArrayList<SeriesPane> seriesPaneList = new ArrayList<>();
      for (Component c : sub.getComponents()) {
        if (c instanceof SeriesPane seriesPane) {
          seriesPaneList.add(seriesPane);
        }
      }
      return seriesPaneList;
    }

    private void clearAllSeries() {
      sub.removeAll();
    }

    private void showAllSeries() {
      clearAllSeries();
      List<SeriesPane> seriesList = study2series.get(dicomStudy);
      if (seriesList != null) {
        int thumbnailSize =
            GuiUtils.getUICore()
                .getSystemPreferences()
                .getIntProperty(Thumbnail.KEY_SIZE, Thumbnail.DEFAULT_SIZE);
        for (int i = 0; i < seriesList.size(); i++) {
          SeriesPane series = seriesList.get(i);
          series.updateSize(thumbnailSize);
          addPane(series, i, thumbnailSize);
        }
        this.revalidate();
      }
    }

    public void addPane(SeriesPane seriesPane, int index, int thumbnailSize) {
      seriesPane.updateSize(thumbnailSize);
      sub.add(seriesPane, index);
      updateText();
    }

    public void updateText() {
      title.setTitle(dicomStudy.toString());
    }

    public boolean isStudy(MediaSeriesGroup dicomStudy) {
      return this.dicomStudy.equals(dicomStudy);
    }
  }

  class SeriesPane extends JPanel {

    final MediaSeriesGroup sequence;
    private final JLabel label;

    public SeriesPane(MediaSeriesGroup sequence) {
      this.sequence = Objects.requireNonNull(sequence);
      this.setLayout(new MigLayout("wrap 1", "[center]"));
      this.setBackground(FlatUIUtils.getUIColor(SeriesSelectionModel.BACKGROUND, Color.LIGHT_GRAY));
      int thumbnailSize =
          GuiUtils.getUICore()
              .getSystemPreferences()
              .getIntProperty(Thumbnail.KEY_SIZE, Thumbnail.DEFAULT_SIZE);
      if (sequence instanceof Series series) {
        Thumbnail thumb = (Thumbnail) series.getTagValue(TagW.Thumbnail);
        if (thumb == null) {
          thumb = createThumbnail(series, model, thumbnailSize);
          series.setTag(TagW.Thumbnail, thumb);
        }
        Optional.ofNullable(thumb).ifPresent(this::add);
      }
      String desc = TagD.getTagValue(sequence, Tag.SeriesDescription, String.class);
      label = new JLabel(desc == null ? "" : desc, SwingConstants.CENTER);
      label.setFont(FontItem.MINI.getFont());
      label.setFocusable(false);
      this.setFocusable(false);
      updateSize(thumbnailSize);
      this.add(label);
    }

    public void updateSize(int thumbnailSize) {
      Dimension max = label.getMaximumSize();
      if (max == null || max.width != thumbnailSize) {
        if (sequence instanceof Series series) {
          SeriesThumbnail thumb = (SeriesThumbnail) series.getTagValue(TagW.Thumbnail);
          if (thumb != null) {
            thumb.setThumbnailSize(thumbnailSize);
          }
        }
        FontRenderContext frc = new FontRenderContext(null, false, false);
        Dimension dim =
            new Dimension(
                GuiUtils.getScaleLength(thumbnailSize),
                (int) (label.getFont().getStringBounds("0", frc).getHeight() + 1.0f));
        label.setPreferredSize(dim);
        label.setMaximumSize(dim);
      }
    }

    public void updateText() {
      String desc = TagD.getTagValue(sequence, Tag.SeriesDescription, String.class);
      label.setText(desc == null ? "" : desc);
    }

    public boolean isSeries(MediaSeriesGroup sequence) {
      return this.sequence.equals(sequence);
    }

    public MediaSeriesGroup getSequence() {
      return sequence;
    }

    public JLabel getLabel() {
      return label;
    }
  }

  protected JPanel getMainPanel() {
    if (panelMain == null) {
      MigLayout layout =
          new MigLayout("fillx, ins 3", "[right]rel[grow,fill]", "[]10lp[]"); // NON-NLS
      panelMain = new JPanel(layout);

      final JLabel label = new JLabel(ResourceUtil.getIcon(OtherIcon.PATIENT, 24, 24));
      panelMain.add(label, GuiUtils.NEWLINE);
      label.setLabelFor(patientCombobox);
      panelMain.add(patientCombobox, "width 30lp:min:250lp"); // NON-NLS

      final JLabel labelStudy = new JLabel(ResourceUtil.getIcon(OtherIcon.CALENDAR, 24, 24));
      labelStudy.setLabelFor(studyCombobox);
      panelMain.add(labelStudy, GuiUtils.NEWLINE);
      panelMain.add(studyCombobox, "width 30lp:min:250lp"); // NON-NLS

      koOpen.setToolTipText(koOpen.getText());
      panelMain.add(
          koOpen, "newline, spanx, alignx left, width 30lp:pref:pref, hidemode 2"); // NON-NLS
      koOpen.addActionListener(
          e -> {
            final MediaSeriesGroup patient =
                selectedPatient == null ? null : selectedPatient.patient;
            if (patient != null && e.getSource() instanceof JButton button) {
              List<KOSpecialElement> list =
                  HiddenSeriesManager.getHiddenElementsFromPatient(KOSpecialElement.class, patient);
              if (!list.isEmpty()) {
                if (list.size() == 1) {
                  model.openRelatedSeries(list.getFirst(), patient);
                } else {
                  list.sort(DicomSpecialElement.ORDER_BY_DATE);
                  JPopupMenu popupMenu = new JPopupMenu();
                  popupMenu.add(new TitleMenuItem(ActionW.KO_SELECTION.getTitle()));
                  popupMenu.addSeparator();

                  ButtonGroup group = new ButtonGroup();
                  for (final KOSpecialElement koSpecialElement : list) {
                    final JMenuItem item = new JMenuItem(koSpecialElement.getShortLabel());
                    item.addActionListener(
                        e1 -> model.openRelatedSeries(koSpecialElement, patient));
                    popupMenu.add(item);
                    group.add(item);
                  }
                  popupMenu.show(button, 0, button.getHeight());
                }
              }
            }
          });
      koOpen.setVisible(false);
    }
    return panelMain;
  }

  public Set<Series<?>> getSelectedPatientOpenSeries() {
    return getPatientOpenSeries(selectedPatient.patient);
  }

  public Set<Series<?>> getPatientOpenSeries(MediaSeriesGroup patient) {
    Set<Series<?>> openSeriesSet = new LinkedHashSet<>();

    if (patient != null) {
      synchronized (model) {
        for (MediaSeriesGroup study : model.getChildren(patient)) {
          for (MediaSeriesGroup seq : model.getChildren(study)) {
            if (seq instanceof Series<?> series
                && Boolean.TRUE.equals(seq.getTagValue(TagW.SeriesOpen))) {
              openSeriesSet.add(series);
            }
          }
        }
      }
    }
    return openSeriesSet;
  }

  public boolean isPatientHasOpenSeries(MediaSeriesGroup patient) {

    synchronized (model) {
      for (MediaSeriesGroup study : model.getChildren(patient)) {
        for (MediaSeriesGroup seq : model.getChildren(study)) {
          if (seq instanceof Series<?> series) {
            Boolean open = (Boolean) series.getTagValue(TagW.SeriesOpen);
            return open != null && open;
          }
        }
      }
    }
    return false;
  }

  public void selectPatient(MediaSeriesGroup patient) {
    if (patient != null && !selectedPatient.isPatient(patient)) {
      selectionList.clear();
      studyCombobox.removeItemListener(studyItemListener);
      modelStudy.removeAllElements();
      // do not use addElement
      modelStudy.insertElementAt(ALL_STUDIES, 0);
      modelStudy.setSelectedItem(ALL_STUDIES);
      selectedPatient.setPatient(patient);
      selectedPatient.showTitle(false);
      List<StudyPane> studies = patient2study.get(patient);
      if (studies != null) {
        for (StudyPane studyPane : studies) {
          modelStudy.addElement(studyPane.dicomStudy);
        }
      }
      studyCombobox.addItemListener(studyItemListener);
      selectStudy();
      koOpen.setVisible(
          HiddenSeriesManager.hasHiddenSpecialElements(KOSpecialElement.class, patient));
      // Send message for selecting related plug-ins window
      model.firePropertyChange(
          new ObservableEvent(ObservableEvent.BasicAction.SELECT, model, null, patient));
    }
  }

  private List<Series<?>> getSplitSeries(Series<?> dcm) {
    MediaSeriesGroup study = model.getParent(dcm, DicomModel.study);
    Object splitNb = dcm.getTagValue(TagW.SplitSeriesNumber);
    List<Series<?>> list = new ArrayList<>();
    if (splitNb == null || study == null) {
      list.add(dcm);
      return list;
    }
    String uid = TagD.getTagValue(dcm, Tag.SeriesInstanceUID, String.class);
    if (uid != null) {
      for (MediaSeriesGroup group : model.getChildren(study)) {
        if (group instanceof Series<?> s
            && uid.equals(TagD.getTagValue(s, Tag.SeriesInstanceUID))) {
          list.add(s);
        }
      }
    }
    return list;
  }

  private void updateSplitSeries(Series<?> dcmSeries) {
    MediaSeriesGroup study = model.getParent(dcmSeries, DicomModel.study);
    // In case the Series has been replaced (split number = -1) and removed
    if (study == null) {
      return;
    }
    StudyPane studyPane = createStudyPaneInstance(study, null);
    List<Series<?>> list = getSplitSeries(dcmSeries);

    List<SeriesPane> seriesList = study2series.computeIfAbsent(study, k -> new ArrayList<>());
    boolean addSeries = selectedPatient.isStudyVisible(study);
    boolean repaintStudy = false;
    for (Series<?> dicomSeries : list) {
      int[] positionSeries = new int[1];
      createSeriesPaneInstance(dicomSeries, positionSeries);
      if (addSeries && positionSeries[0] != -1) {
        repaintStudy = true;
      }

      SeriesThumbnail thumb = (SeriesThumbnail) dicomSeries.getTagValue(TagW.Thumbnail);
      if (thumb != null) {
        thumb.reBuildThumbnail();
      }
    }

    Integer nb = (Integer) dcmSeries.getTagValue(TagW.SplitSeriesNumber);
    // Convention -> split number inferior to 0 is a Series that has been replaced (ex. when a
    // DicomSeries is converted DicomVideoSeries after downloading files).
    if (nb != null && nb < 0) {
      model.removeSeries(dcmSeries);
      repaintStudy = true;
    }
    if (repaintStudy) {
      int thumbnailSize =
          GuiUtils.getUICore()
              .getSystemPreferences()
              .getIntProperty(Thumbnail.KEY_SIZE, Thumbnail.DEFAULT_SIZE);
      studyPane.clearAllSeries();
      for (int i = 0, k = 1; i < seriesList.size(); i++) {
        SeriesPane s = seriesList.get(i);
        studyPane.addPane(s, i, thumbnailSize);
        if (list.contains(s.getSequence())) {
          s.getSequence().setTag(TagW.SplitSeriesNumber, k);
          k++;
        }
      }
      studyPane.revalidate();
      studyPane.repaint();
      changeToolWindowAnchor(getDockable().getBaseLocation());
    } else {
      int k = 1;
      for (SeriesPane s : seriesList) {
        if (list.contains(s.getSequence())) {
          s.getSequence().setTag(TagW.SplitSeriesNumber, k);
          k++;
        }
      }
    }
  }

  private void selectStudy() {
    Object item = modelStudy.getSelectedItem();
    if (item instanceof MediaSeriesGroupNode selectedStudy) {
      selectStudy(selectedStudy);
    } else {
      selectStudy(null);
    }
  }

  public void selectStudy(MediaSeriesGroup selectedStudy) {
    selectionList.clear();
    selectedPatient.removeAll();

    if (selectedStudy == null) {
      selectedPatient.showAllStudies();
    } else {
      StudyPane studyPane = getStudyPane(selectedStudy);
      if (studyPane != null) {
        studyPane.showAllSeries();
        selectedPatient.addPane(studyPane);
        studyPane.doLayout();
      }
    }
    selectedPatient.revalidate();
    selectedPatient.repaint();
  }

  @Override
  public void dispose() {
    super.closeDockable();
  }

  private void addDicomSeries(Series<?> series) {
    if (DicomModel.isHiddenModality(series)) {
      // Up to now nothing has to be done in the explorer view about specialModality
      return;
    }
    LOGGER.info("Add series: {}", series);
    MediaSeriesGroup study = model.getParent(series, DicomModel.study);
    MediaSeriesGroup patient = model.getParent(series, DicomModel.patient);

    if (modelPatient.getIndexOf(patient) < 0) {
      modelPatient.addElement(patient);
      if (modelPatient.getSize() == 1) {
        modelPatient.setSelectedItem(patient);
      }
    }

    List<StudyPane> studies = patient2study.computeIfAbsent(patient, k -> new ArrayList<>());
    Object selectedStudy = modelStudy.getSelectedItem();
    int[] positionStudy = new int[1];
    StudyPane studyPane = createStudyPaneInstance(study, positionStudy);

    List<SeriesPane> seriesList = study2series.computeIfAbsent(study, k -> new ArrayList<>());
    int[] positionSeries = new int[1];
    createSeriesPaneInstance(series, positionSeries);
    if (isSelectedPatient(patient) && positionSeries[0] != -1) {
      // If new study
      if (positionStudy[0] != -1) {
        if (modelStudy.getIndexOf(study) < 0) {
          modelStudy.addElement(study);
        }
        // if modelStudy has the value "All studies"
        if (ALL_STUDIES.equals(selectedStudy)) {
          selectedPatient.removeAll();
          for (StudyPane s : studies) {
            selectedPatient.addPane(s);
          }
          selectedPatient.revalidate();
        }
      }
      if (selectedPatient.isStudyVisible(study)) {
        int thumbnailSize =
            GuiUtils.getUICore()
                .getSystemPreferences()
                .getIntProperty(Thumbnail.KEY_SIZE, Thumbnail.DEFAULT_SIZE);
        studyPane.clearAllSeries();
        for (int i = 0; i < seriesList.size(); i++) {
          studyPane.addPane(seriesList.get(i), i, thumbnailSize);
        }
        studyPane.revalidate();
        studyPane.repaint();
      }
    }
  }

  @Override
  public void changingViewContentEvent(SeriesViewerEvent event) {
    EVENT type = event.getEventType();
    if (EVENT.SELECT_VIEW.equals(type) && event.getSeriesViewer() instanceof ImageViewerPlugin) {
      ViewCanvas<?> pane = ((ImageViewerPlugin<?>) event.getSeriesViewer()).getSelectedImagePane();
      if (pane != null) {
        MediaSeries<?> s = pane.getSeries();
        if (s != null
            && !getSelectionList().isOpeningSeries()
            && selectedPatient.isSeriesVisible(s)) {
          SeriesPane p = getSeriesPane(s);
          if (p != null) {
            JViewport vp = thumbnailView.getViewport();
            Rectangle bound = vp.getViewRect();
            Point ptmin = SwingUtilities.convertPoint(p, new Point(0, 0), selectedPatient);
            Point ptmax =
                SwingUtilities.convertPoint(p, new Point(0, p.getHeight()), selectedPatient);
            if (!bound.contains(ptmin.x, ptmin.y) || !bound.contains(ptmax.x, ptmax.y)) {
              Point pt = vp.getViewPosition();
              pt.y = ptmin.y + (ptmax.y - ptmin.y) / 2;
              pt.y -= vp.getHeight() / 2;
              int maxHeight = (int) (vp.getViewSize().getHeight() - vp.getExtentSize().getHeight());
              if (pt.y < 0) {
                pt.y = 0;
              } else if (pt.y > maxHeight) {
                pt.y = maxHeight;
              }
              vp.setViewPosition(pt);
              // Clear the selection when another view is selected
              getSelectionList().clear();
            }
          }
        }
      }
    }
  }

  @Override
  public void propertyChange(PropertyChangeEvent evt) {
    // Get only event from the model of DicomExplorer
    if (evt instanceof ObservableEvent event) {
      ObservableEvent.BasicAction action = event.getActionCommand();
      Object newVal = event.getNewValue();
      if (model.equals(evt.getSource())) {
        if (ObservableEvent.BasicAction.SELECT.equals(action)) {
          if (newVal instanceof Series dcm) {
            MediaSeriesGroup patient = model.getParent(dcm, DicomModel.patient);
            if (!isSelectedPatient(patient)) {
              modelPatient.setSelectedItem(patient);
              GuiUtils.getUICore()
                  .getDockingControl()
                  .getController()
                  .setFocusedDockable(
                      new DefaultFocusRequest(dockable.intern(), this, false, true, false));
            }
          }
        } else if (ObservableEvent.BasicAction.ADD.equals(action)) {
          if (newVal instanceof Series series) {
            addDicomSeries(series);
          }
        } else if (ObservableEvent.BasicAction.REMOVE.equals(action)) {
          if (newVal instanceof MediaSeriesGroup group) {
            // Patient Group
            if (TagD.getUID(Level.PATIENT).equals(group.getTagID())) {
              removePatientPane(group);
            }
            // Study Group
            else if (TagD.getUID(Level.STUDY).equals(group.getTagID())) {
              removeStudy(group);
            }
            // Series Group
            else if (TagD.getUID(Level.SERIES).equals(group.getTagID())) {
              removeSeries(group);
            }
          }
        }
        // Update patient and study infos from the series (when receiving the first downloaded
        // image)
        else if (ObservableEvent.BasicAction.UPDATE_PARENT.equals(action)) {
          if (newVal instanceof Series dcm) {
            MediaSeriesGroup patient = model.getParent(dcm, DicomModel.patient);
            if (isSelectedPatient(patient)) {
              MediaSeriesGroup study = model.getParent(dcm, DicomModel.study);
              StudyPane studyPane = getStudyPane(study);
              if (studyPane != null) {
                if (!DicomModel.isHiddenModality(dcm)) {
                  SeriesPane pane = getSeriesPane(dcm);
                  if (pane != null) {
                    pane.updateText();
                  }
                }
                studyPane.updateText();
              }
            }
          }
        }
        // filter
        else if (ObservableEvent.BasicAction.UPDATE.equals(action)) {
          if (newVal instanceof Series series) {
            Integer splitNb = (Integer) series.getTagValue(TagW.SplitSeriesNumber);
            if (splitNb != null) {
              updateSplitSeries(series);
            }
          } else if (newVal instanceof KOSpecialElement) {
            MediaSeriesGroupNode patient = getSelectedPatient();
            if (patient != null) {
              koOpen.setVisible(
                  HiddenSeriesManager.hasHiddenSpecialElements(KOSpecialElement.class, patient));
            }
          }
        } else if (ObservableEvent.BasicAction.LOADING_START.equals(action)) {
          if (newVal instanceof ExplorerTask) {
            addTaskToGlobalProgression((ExplorerTask<?, ?>) newVal);
          }
        } else if (ObservableEvent.BasicAction.LOADING_STOP.equals(action)
            || ObservableEvent.BasicAction.LOADING_CANCEL.equals(action)) {
          if (newVal instanceof ExplorerTask) {
            removeTaskToGlobalProgression((ExplorerTask<?, ?>) newVal);
          }
          MediaSeriesGroupNode patient = getSelectedPatient();
          if (patient != null) {
            koOpen.setVisible(
                HiddenSeriesManager.hasHiddenSpecialElements(KOSpecialElement.class, patient));
          }
        }
      } else if (evt.getSource() instanceof SeriesViewer
          && ObservableEvent.BasicAction.SELECT.equals(action)
          && newVal instanceof MediaSeriesGroup patient
          && !isSelectedPatient(patient)) {
        modelPatient.setSelectedItem(patient);
        // focus get back to viewer
        if (evt.getSource() instanceof ViewerPlugin viewerPlugin) {
          viewerPlugin.requestFocusInWindow();
        }
      }
    }
  }

  @Override
  public String getDescription() {
    return DESCRIPTION;
  }

  @Override
  public Icon getIcon() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public String getUIName() {
    return NAME;
  }

  @Override
  public String toString() {
    return NAME;
  }

  @Override
  public DataExplorerModel getDataExplorerModel() {
    return model;
  }

  @Override
  protected void changeToolWindowAnchor(CLocation clocation) {
    removeAll();
    if (verticalLayout) {
      setLayout(new MigLayout("fillx, ins 0", "[grow,fill]", "[]rel[grow,fill]unrel[]")); // NON-NLS
      add(getMainPanel(), "");
      add(thumbnailView, "newline, top"); // NON-NLS
      add(loadingPanel, "newline,"); // NON-NLS
    } else {
      setLayout(new MigLayout("fillx, ins 0", "[right]rel[grow,fill]")); // NON-NLS
      add(GuiUtils.getVerticalBoxLayoutPanel(getMainPanel(), loadingPanel));
      add(thumbnailView);
    }
    selectedPatient.refreshLayout();
  }

  public synchronized void addTaskToGlobalProgression(final ExplorerTask task) {
    GuiExecutor.invokeAndWait(
        () -> {
          loadingPanel.addTask(task);
          revalidate();
          repaint();
        });
  }

  public synchronized void removeTaskToGlobalProgression(final ExplorerTask task) {
    GuiExecutor.invokeAndWait(
        () -> {
          if (loadingPanel.removeTask(task)) {
            revalidate();
            repaint();
          }
        });
  }

  public static SeriesThumbnail createThumbnail(
      final Series<?> series, final DicomModel dicomModel, final int thumbnailSize) {

    Callable<SeriesThumbnail> callable =
        () -> {
          Function<String, Set<ResourceIconPath>> drawIcons = HiddenSeriesManager::getRelatedIcons;
          final SeriesThumbnail thumb = new SeriesThumbnail(series, thumbnailSize, drawIcons);
          if (series.getSeriesLoader() instanceof LoadSeries loader) {
            // In case series is downloaded or canceled
            thumb.setProgressBar(loader.isDone() ? null : loader.getProgressBar());
          }
          thumb.registerListeners();
          ThumbnailMouseAndKeyAdapter thumbAdapter =
              new ThumbnailMouseAndKeyAdapter(series, dicomModel, null);
          thumb.addMouseListener(thumbAdapter);
          thumb.addKeyListener(thumbAdapter);
          return thumb;
        };
    FutureTask<SeriesThumbnail> future = new FutureTask<>(callable);
    GuiExecutor.invokeAndWait(future);
    SeriesThumbnail result = null;
    try {
      result = future.get();
    } catch (InterruptedException e) {
      LOGGER.warn("Building Series thumbnail task Interruption");
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      LOGGER.error("Building Series thumbnail task", e);
    }
    return result;
  }

  @Override
  public void importFiles(File[] files, boolean recursive) {
    if (files != null) {
      OpeningViewer openingViewer =
          OpeningViewer.getOpeningViewerByLocalKey(LocalImport.LAST_OPEN_VIEWER_MODE);
      DicomModel.LOADING_EXECUTOR.execute(
          new LoadLocalDicom(files, recursive, model, openingViewer));
    }
  }

  @Override
  public List<Action> getOpenExportDialogAction() {
    return Collections.singletonList(ExportToolBar.buildExportAction(this, model, BUTTON_NAME));
  }

  @Override
  public List<Action> getOpenImportDialogAction() {
    ArrayList<Action> actions = new ArrayList<>(2);
    actions.add(ImportToolBar.buildImportAction(this, model, BUTTON_NAME));
    DefaultAction importCDAction =
        new DefaultAction(
            Messages.getString("DicomExplorer.dcmCD"),
            ResourceUtil.getIcon(OtherIcon.CDROM),
            event ->
                ImportToolBar.openImportDicomCdAction(
                    this, model, Messages.getString("DicomExplorer.dcmCD")));
    actions.add(importCDAction);
    return actions;
  }

  @Override
  public boolean canImportFiles() {
    return true;
  }

  private class SeriesHandler extends SequenceHandler {
    public SeriesHandler() {
      super(false, true);
    }

    @Override
    protected boolean dropFiles(List<File> files, TransferSupport support) {
      return DicomSeriesHandler.dropDicomFiles(files);
    }
  }
}
