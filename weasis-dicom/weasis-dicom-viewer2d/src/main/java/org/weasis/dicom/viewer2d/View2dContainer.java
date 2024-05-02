/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.viewer2d;

import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.dcm4che3.data.Tag;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.service.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.explorer.DataExplorerView;
import org.weasis.core.api.explorer.ObservableEvent;
import org.weasis.core.api.explorer.model.DataExplorerModel;
import org.weasis.core.api.gui.Insertable.Type;
import org.weasis.core.api.gui.InsertableUtil;
import org.weasis.core.api.gui.util.ActionW;
import org.weasis.core.api.gui.util.BasicActionState;
import org.weasis.core.api.gui.util.ComboItemListener;
import org.weasis.core.api.gui.util.Filter;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.gui.util.SliderChangeListener;
import org.weasis.core.api.gui.util.ToggleButtonListener;
import org.weasis.core.api.image.GridBagLayoutModel;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.core.api.media.data.Series;
import org.weasis.core.api.media.data.SeriesEvent;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.api.service.BundlePreferences;
import org.weasis.core.api.service.WProperties;
import org.weasis.core.api.util.ResourceUtil;
import org.weasis.core.api.util.ResourceUtil.ActionIcon;
import org.weasis.core.api.util.ResourceUtil.OtherIcon;
import org.weasis.core.ui.docking.DockableTool;
import org.weasis.core.ui.docking.PluginTool;
import org.weasis.core.ui.editor.SeriesViewerEvent;
import org.weasis.core.ui.editor.SeriesViewerEvent.EVENT;
import org.weasis.core.ui.editor.SeriesViewerFactory;
import org.weasis.core.ui.editor.SeriesViewerListener;
import org.weasis.core.ui.editor.SeriesViewerUI;
import org.weasis.core.ui.editor.ViewerPluginBuilder;
import org.weasis.core.ui.editor.image.DefaultView2d;
import org.weasis.core.ui.editor.image.ImageViewerPlugin;
import org.weasis.core.ui.editor.image.MeasureToolBar;
import org.weasis.core.ui.editor.image.RotationToolBar;
import org.weasis.core.ui.editor.image.ScreenshotToolBar;
import org.weasis.core.ui.editor.image.SynchData;
import org.weasis.core.ui.editor.image.SynchView;
import org.weasis.core.ui.editor.image.ViewCanvas;
import org.weasis.core.ui.editor.image.ViewerToolBar;
import org.weasis.core.ui.editor.image.ZoomToolBar;
import org.weasis.core.ui.editor.image.dockable.MeasureTool;
import org.weasis.core.ui.editor.image.dockable.MiniTool;
import org.weasis.core.ui.util.ColorLayerUI;
import org.weasis.core.ui.util.DefaultAction;
import org.weasis.core.ui.util.PrintDialog;
import org.weasis.core.ui.util.Toolbar;
import org.weasis.core.util.LangUtil;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.DicomSpecialElement;
import org.weasis.dicom.codec.KOSpecialElement;
import org.weasis.dicom.codec.PRSpecialElement;
import org.weasis.dicom.codec.PresentationStateReader;
import org.weasis.dicom.codec.SpecialElementRegion;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.codec.TagD.Level;
import org.weasis.dicom.explorer.DicomExplorer;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.DicomViewerPlugin;
import org.weasis.dicom.explorer.ExportToolBar;
import org.weasis.dicom.explorer.ImportToolBar;
import org.weasis.dicom.explorer.print.DicomPrintDialog;
import org.weasis.dicom.viewer2d.dockable.DisplayTool;
import org.weasis.dicom.viewer2d.dockable.ImageTool;

public class View2dContainer extends DicomViewerPlugin implements PropertyChangeListener {
  private static final Logger LOGGER = LoggerFactory.getLogger(View2dContainer.class);

  // Unmodifiable list of the default synchronization elements
  public static final List<SynchView> DEFAULT_SYNCH_LIST =
      List.of(SynchView.NONE, SynchView.DEFAULT_STACK, SynchView.DEFAULT_TILE);

  public static final GridBagLayoutModel VIEWS_2x1_r1xc2_dump =
      new GridBagLayoutModel(
          View2dContainer.class.getResourceAsStream("/config/layoutModel.xml"), // NON-NLS
          "layout_dump", // NON-NLS
          Messages.getString("View2dContainer.layout_dump"));
  public static final GridBagLayoutModel VIEWS_2x1_r1xc2_histo =
      new GridBagLayoutModel(
          View2dContainer.class.getResourceAsStream("/config/layoutModelHisto.xml"), // NON-NLS
          "layout_histo", // NON-NLS
          Messages.getString("View2dContainer.histogram"));
  // Unmodifiable list of the default layout elements
  public static final List<GridBagLayoutModel> DEFAULT_LAYOUT_LIST =
      List.of(
          VIEWS_1x1,
          VIEWS_1x2,
          VIEWS_1x3,
          VIEWS_1x4,
          VIEWS_2x1,
          VIEWS_2x2_f2,
          VIEWS_2_f1x2,
          VIEWS_2x1_r1xc2_dump,
          VIEWS_2x1_r1xc2_histo,
          VIEWS_2x2,
          VIEWS_2x3,
          VIEWS_2x4);

  public static final SeriesViewerUI UI = new SeriesViewerUI(View2dContainer.class);

  public View2dContainer() {
    this(VIEWS_1x1, null, View2dFactory.NAME, ResourceUtil.getIcon(OtherIcon.XRAY), null);
  }

  public View2dContainer(
      GridBagLayoutModel layoutModel, String uid, String pluginName, Icon icon, String tooltips) {
    super(EventManager.getInstance(), layoutModel, uid, pluginName, icon, tooltips);
    setSynchView(SynchView.DEFAULT_STACK);
    addComponentListener(
        new ComponentAdapter() {

          @Override
          public void componentResized(ComponentEvent e) {
            ImageViewerPlugin<DicomImageElement> container =
                EventManager.getInstance().getSelectedView2dContainer();
            if (container == View2dContainer.this) {
              Optional<ComboItemListener<GridBagLayoutModel>> layoutAction =
                  EventManager.getInstance().getAction(ActionW.LAYOUT);
              layoutAction.ifPresent(
                  a ->
                      a.setDataListWithoutTriggerAction(
                          getLayoutList().toArray(new GridBagLayoutModel[0])));
            }
          }
        });

    if (!UI.init.getAndSet(true)) {
      List<Toolbar> toolBars = UI.toolBars;

      // Add standard toolbars
      final BundleContext context = FrameworkUtil.getBundle(this.getClass()).getBundleContext();
      EventManager evtMg = EventManager.getInstance();

      String bundleName = context.getBundle().getSymbolicName();
      String componentName = InsertableUtil.getCName(this.getClass());
      String key = "enable"; // NON-NLS
      WProperties preferences = GuiUtils.getUICore().getSystemPreferences();

      if (InsertableUtil.getBooleanProperty(
          preferences,
          bundleName,
          componentName,
          InsertableUtil.getCName(ImportToolBar.class),
          key,
          true)) {
        Optional<Toolbar> b =
            GuiUtils.getUICore().getExplorerPluginToolbars().stream()
                .filter(ImportToolBar.class::isInstance)
                .findFirst();
        b.ifPresent(toolBars::add);
      }
      if (InsertableUtil.getBooleanProperty(
          preferences,
          bundleName,
          componentName,
          InsertableUtil.getCName(ExportToolBar.class),
          key,
          true)) {
        Optional<Toolbar> b =
            GuiUtils.getUICore().getExplorerPluginToolbars().stream()
                .filter(ExportToolBar.class::isInstance)
                .findFirst();
        b.ifPresent(toolBars::add);
      }
      if (InsertableUtil.getBooleanProperty(
          preferences,
          bundleName,
          componentName,
          InsertableUtil.getCName(ScreenshotToolBar.class),
          key,
          true)) {
        toolBars.add(new ScreenshotToolBar<>(evtMg, 9));
      }
      if (InsertableUtil.getBooleanProperty(
          preferences,
          bundleName,
          componentName,
          InsertableUtil.getCName(ViewerToolBar.class),
          key,
          true)) {
        toolBars.add(
            new ViewerToolBar<>(
                evtMg, evtMg.getMouseActions().getActiveButtons(), preferences, 10));
      }
      if (InsertableUtil.getBooleanProperty(
          preferences,
          bundleName,
          componentName,
          InsertableUtil.getCName(MeasureToolBar.class),
          key,
          true)) {
        toolBars.add(new MeasureToolBar(evtMg, 11));
      }
      if (InsertableUtil.getBooleanProperty(
          preferences,
          bundleName,
          componentName,
          InsertableUtil.getCName(ZoomToolBar.class),
          key,
          true)) {
        toolBars.add(new ZoomToolBar(evtMg, 20, true));
      }
      if (InsertableUtil.getBooleanProperty(
          preferences,
          bundleName,
          componentName,
          InsertableUtil.getCName(RotationToolBar.class),
          key,
          true)) {
        toolBars.add(new RotationToolBar(evtMg, 30));
      }
      if (InsertableUtil.getBooleanProperty(
          preferences,
          bundleName,
          componentName,
          InsertableUtil.getCName(DcmHeaderToolBar.class),
          key,
          true)) {
        toolBars.add(new DcmHeaderToolBar(evtMg, 35));
      }
      if (InsertableUtil.getBooleanProperty(
          preferences,
          bundleName,
          componentName,
          InsertableUtil.getCName(LutToolBar.class),
          key,
          true)) {
        toolBars.add(new LutToolBar(evtMg, 40));
      }
      if (InsertableUtil.getBooleanProperty(
          preferences,
          bundleName,
          componentName,
          InsertableUtil.getCName(Basic3DToolBar.class),
          key,
          true)) {
        toolBars.add(new Basic3DToolBar(50));
      }
      if (InsertableUtil.getBooleanProperty(
          preferences,
          bundleName,
          componentName,
          InsertableUtil.getCName(CineToolBar.class),
          key,
          true)) {
        toolBars.add(new CineToolBar(80));
      }
      if (InsertableUtil.getBooleanProperty(
          preferences,
          bundleName,
          componentName,
          InsertableUtil.getCName(KeyObjectToolBar.class),
          key,
          true)) {
        toolBars.add(new KeyObjectToolBar(90));
      }

      List<DockableTool> tools = UI.tools;
      PluginTool tool;

      if (InsertableUtil.getBooleanProperty(
          preferences,
          bundleName,
          componentName,
          InsertableUtil.getCName(MiniTool.class),
          key,
          true)) {
        tool =
            new MiniTool(MiniTool.BUTTON_NAME) {

              @Override
              public SliderChangeListener[] getActions() {
                ArrayList<SliderChangeListener> listeners = new ArrayList<>(3);
                eventManager.getAction(ActionW.SCROLL_SERIES).ifPresent(listeners::add);
                eventManager.getAction(ActionW.ZOOM).ifPresent(listeners::add);
                eventManager.getAction(ActionW.ROTATION).ifPresent(listeners::add);
                return listeners.toArray(new SliderChangeListener[0]);
              }
            };
        tools.add(tool);
      }

      if (InsertableUtil.getBooleanProperty(
          preferences,
          bundleName,
          componentName,
          InsertableUtil.getCName(ImageTool.class),
          key,
          true)) {
        tool = new ImageTool(ImageTool.BUTTON_NAME);
        tools.add(tool);
      }

      if (InsertableUtil.getBooleanProperty(
          preferences,
          bundleName,
          componentName,
          InsertableUtil.getCName(DisplayTool.class),
          key,
          true)) {
        tool = new DisplayTool(DisplayTool.BUTTON_NAME);
        tools.add(tool);
        eventManager.addSeriesViewerListener((SeriesViewerListener) tool);
      }

      if (InsertableUtil.getBooleanProperty(
          preferences,
          bundleName,
          componentName,
          InsertableUtil.getCName(MeasureTool.class),
          key,
          true)) {
        tool = new MeasureTool(eventManager);
        tools.add(tool);
      }

      InsertableUtil.sortInsertable(tools);

      // Send event to synchronize the series selection.
      DataExplorerView dicomView = GuiUtils.getUICore().getExplorerPlugin(DicomExplorer.NAME);
      if (dicomView != null) {
        eventManager.addSeriesViewerListener((SeriesViewerListener) dicomView);
      }

      Preferences prefs = BundlePreferences.getDefaultPreferences(context);
      if (prefs != null) {
        InsertableUtil.applyPreferences(toolBars, prefs, bundleName, componentName, Type.TOOLBAR);
        InsertableUtil.applyPreferences(tools, prefs, bundleName, componentName, Type.TOOL);
      }
    }
  }

  @Override
  public void setSelectedImagePaneFromFocus(ViewCanvas<DicomImageElement> viewCanvas) {
    setSelectedImagePane(viewCanvas);
    if (viewCanvas != null && viewCanvas.getSeries() instanceof DicomSeries series) {
      DicomSeries.startPreloading(
          series,
          series.copyOfMedias(
              (Filter<DicomImageElement>) viewCanvas.getActionValue(ActionW.FILTERED_SERIES.cmd()),
              viewCanvas.getCurrentSortComparator()),
          viewCanvas.getFrameIndex());
    }
  }

  @Override
  public JMenu fillSelectedPluginMenu(JMenu menuRoot) {
    if (menuRoot != null) {
      menuRoot.removeAll();
      if (eventManager instanceof EventManager manager) {
        MediaSeries<DicomImageElement> series = EventManager.getInstance().getSelectedSeries();
        if (series != null) {
          JMenu menu = new JMenu(Messages.getString("open.new.tab"));
          List<SeriesViewerFactory> plugins =
              GuiUtils.getUICore().getViewerFactoryList(new String[] {series.getMimeType()});
          for (final SeriesViewerFactory viewerFactory : plugins) {
            if (viewerFactory.canReadSeries(series)) {
              JMenuItem menuFactory = new JMenuItem(viewerFactory.getUIName());
              menuFactory.setIcon(viewerFactory.getIcon());
              GuiUtils.applySelectedIconEffect(menuFactory);
              menuFactory.addActionListener(
                  e ->
                      ViewerPluginBuilder.openSequenceInPlugin(
                          viewerFactory,
                          series,
                          (DataExplorerModel) series.getTagValue(TagW.ExplorerModel),
                          false,
                          false));
              menu.add(menuFactory);
            }
          }

          BasicActionState volState =
              EventManager.getInstance().getAction(ActionW.VOLUME).orElse(null);
          if (volState != null && volState.isActionEnabled()) {
            JMenuItem mip =
                new JMenuItem(
                    Messages.getString("View2dContainer.mip"),
                    ResourceUtil.getIcon(OtherIcon.VIEW_MIP));
            GuiUtils.applySelectedIconEffect(mip);
            mip.addActionListener(Basic3DToolBar.getMipAction());
            menu.add(mip);
          }
          menuRoot.add(menu);
        }
        menuRoot.add(new JSeparator());
        GuiUtils.addItemToMenu(menuRoot, manager.getPresetMenu(null));
        GuiUtils.addItemToMenu(menuRoot, manager.getLutShapeMenu(null));
        GuiUtils.addItemToMenu(menuRoot, manager.getLutMenu(null));
        GuiUtils.addItemToMenu(menuRoot, manager.getLutInverseMenu(null));
        GuiUtils.addItemToMenu(menuRoot, manager.getFilterMenu(null));
        menuRoot.add(new JSeparator());
        GuiUtils.addItemToMenu(menuRoot, manager.getZoomMenu(null));
        GuiUtils.addItemToMenu(menuRoot, manager.getOrientationMenu(null));
        GuiUtils.addItemToMenu(menuRoot, manager.getCineMenu(null));
        GuiUtils.addItemToMenu(menuRoot, manager.getSortStackMenu(null));
        menuRoot.add(new JSeparator());
        menuRoot.add(manager.getResetMenu(null));
      }
    }
    return menuRoot;
  }

  @Override
  public void addSeries(MediaSeries<DicomImageElement> sequence) {
    if (DicomModel.isHiddenModality(sequence)) {
      return;
    }
    super.addSeries(sequence);
  }

  public SeriesViewerUI getSeriesViewer() {
    return UI;
  }

  @Override
  public void close() {
    View2dFactory.closeSeriesViewer(this);
    super.close();
  }

  @Override
  public void propertyChange(PropertyChangeEvent evt) {

    if (evt instanceof ObservableEvent event) {
      ObservableEvent.BasicAction action = event.getActionCommand();
      Object newVal = event.getNewValue();

      if (newVal instanceof SeriesEvent event2) {

        SeriesEvent.Action action2 = event2.getActionCommand();
        Object source = event2.getSource();
        Object param = event2.getParam();

        if (ObservableEvent.BasicAction.ADD.equals(action)) {

          if (SeriesEvent.Action.ADD_IMAGE.equals(action2)) {
            if (source instanceof DicomSeries series) {
              ViewCanvas<DicomImageElement> view2DPane = eventManager.getSelectedViewPane();
              if (view2DPane != null) {
                DicomImageElement img = view2DPane.getImage();
                if (img != null && view2DPane.getSeries() == series) {
                  eventManager
                      .getAction(ActionW.SCROLL_SERIES)
                      .ifPresent(
                          s -> {
                            Filter<DicomImageElement> filter =
                                (Filter<DicomImageElement>)
                                    view2DPane.getActionValue(ActionW.FILTERED_SERIES.cmd());
                            int imgIndex =
                                series.getImageIndex(
                                    img, filter, view2DPane.getCurrentSortComparator());
                            if (imgIndex < 0) {
                              imgIndex = 0;
                              // add again the series for registering listeners
                              // (require at least one image)
                              view2DPane.setSeries(series, null);
                            }
                            s.setSliderMinMaxValue(1, series.size(filter), imgIndex + 1);
                          });
                }
              }
            }
          } else if (SeriesEvent.Action.UPDATE_IMAGE.equals(action2)) {
            if (source instanceof DicomImageElement dcm) {
              for (ViewCanvas<DicomImageElement> v : view2ds) {
                if (dcm == v.getImage()) {
                  // Force to repaint the same image
                  if (v.getImageLayer().getDisplayImage() == null) {
                    v.setActionsInView(ActionW.PROGRESSION.cmd(), param);
                    // Set image to null for getting correct W/L values
                    v.getImageLayer().setImage(null, null);
                    v.setSeries(v.getSeries());
                  } else {
                    v.propertyChange(
                        new PropertyChangeEvent(dcm, ActionW.PROGRESSION.cmd(), null, param));
                  }
                }
              }
            }
          } else if (SeriesEvent.Action.PRELOADING.equals(action2)
              && source instanceof DicomSeries dcm) {
            for (ViewCanvas<DicomImageElement> v : view2ds) {
              if (dcm == v.getSeries()) {
                v.getJComponent().repaint(v.getInfoLayer().getPreloadingProgressBound());
              }
            }
          }
        } else if (ObservableEvent.BasicAction.UPDATE.equals(action)
            && SeriesEvent.Action.UPDATE.equals(action2)) {
          if (source instanceof KOSpecialElement) {
            setKOSpecialElement((KOSpecialElement) source, null, false, param.equals("updateAll"));
          } else if (source instanceof DicomSeries dcm) {
            ViewCanvas<DicomImageElement> view = getSelectedImagePane();
            if (view != null && view.getSeries() == dcm) {
              eventManager.updateComponentsListener(view);
            }
          }
        }
      } else if (ObservableEvent.BasicAction.REMOVE.equals(action)) {
        if (newVal instanceof MediaSeriesGroup group) {
          removeViews(this, group, event);
        }
      } else if (ObservableEvent.BasicAction.REPLACE.equals(action)) {
        if (newVal instanceof Series series) {
          for (ViewCanvas<DicomImageElement> v : view2ds) {
            MediaSeries<DicomImageElement> s = v.getSeries();
            if (series.equals(s)) {
              /*
               * Set to null to be sure that all parameters from the view are applied again to the Series
               * (for instance it is the same series with more images)
               */
              v.setSeries(null);
              v.setSeries(series, null);
            }
          }
        }
      } else if (ObservableEvent.BasicAction.UPDATE.equals(action)) {

        DicomSpecialElement specialElement = null;

        // When a dicom KO element is loaded an ObservableEvent.BasicAction.Update is sent
        // Either it's a new DicomObject or it's content is updated

        // TODO - a choice should be done about sending either a DicomSpecialElement or a Series
        // object as the
        // new value for this event. A DicomSpecialElement seems to be a better choice since a
        // Series of
        // DicomSpecialElement do not necessarily concerned the series in the Viewer2dContainer

        if (newVal instanceof Series series) {
          specialElement = DicomModel.getFirstSpecialElement(series, DicomSpecialElement.class);
        } else if (newVal instanceof DicomSpecialElement dicomSpecialElement) {
          specialElement = dicomSpecialElement;
        }

        if (specialElement instanceof PRSpecialElement prSpecialElement) {
          for (ViewCanvas<DicomImageElement> view : view2ds) {
            if (view instanceof View2d view2d
                && PresentationStateReader.isImageApplicable(prSpecialElement, view.getImage())) {
              view2d.updatePR();
            }
          }
        } else if (specialElement instanceof SpecialElementRegion region) {
          ViewCanvas<DicomImageElement> pane = getSelectedImagePane();
          for (ViewCanvas<DicomImageElement> view : view2ds) {
            if (view instanceof View2d view2d) {
              if (region.containsSopInstanceUIDReference(view.getImage())) {
                view2d.updateSegmentation();
              }
              if (view2d == pane) {
                UI.updateDynamicTools(view2d.getSeries());
                if (!UI.dynamicTools.isEmpty()) {
                  eventManager.fireSeriesViewerListeners(
                      new SeriesViewerEvent(this, view2d.getSeries(), null, EVENT.SELECT_VIEW));
                }
              }
            }
          }
        } else if (specialElement instanceof KOSpecialElement koSpecialElement) {
          // Update if necessary all the views with the KOSpecialElement
          setKOSpecialElement(koSpecialElement, null, false, true);
        }
      } else if (ObservableEvent.BasicAction.SELECT.equals(action)
          && newVal instanceof KOSpecialElement koSpecialElement) {
        // Match using UID of the plugin window and the source event
        if (this.getDockableUID().equals(evt.getSource())) {
          setKOSpecialElement(koSpecialElement, true, true, false);
        }
      }
    }
  }

  public static void removeViews(
      ImageViewerPlugin<?> viewerPlugin, MediaSeriesGroup group, ObservableEvent event) {
    // Patient Group
    if (TagD.getUID(Level.PATIENT).equals(group.getTagID())) {
      if (group.equals(viewerPlugin.getGroupID())) {
        // Close the content of the plug-in
        viewerPlugin.close();
        viewerPlugin.handleFocusAfterClosing();
      }
    }
    // Study Group
    else if (TagD.getUID(Level.STUDY).equals(group.getTagID())) {
      if (event.getSource() instanceof DicomModel model) {
        for (ViewCanvas<?> v : viewerPlugin.getImagePanels()) {
          if (group.equals(model.getParent(v.getSeries(), DicomModel.study))) {
            v.setSeries(null);
            if (viewerPlugin.closeIfNoContent()) {
              return;
            }
          }
        }
      }
    }
    // Series Group
    else if (TagD.getUID(Level.SERIES).equals(group.getTagID())) {
      for (ViewCanvas<?> v : viewerPlugin.getImagePanels()) {
        if (group.equals(v.getSeries())) {
          v.setSeries(null);
          if (viewerPlugin.closeIfNoContent()) {
            return;
          }
        }
      }
    }
  }

  private void setKOSpecialElement(
      KOSpecialElement updatedKOSelection,
      Boolean enableFilter,
      boolean forceUpdate,
      boolean updateAll) {
    ViewCanvas<DicomImageElement> selectedView = getSelectedImagePane();

    if (updatedKOSelection != null && selectedView instanceof View2d view2d) {
      if (SynchData.Mode.TILE.equals(this.getSynchView().getSynchData().getMode())) {

        selectedView
            .getEventManager()
            .getAction(ActionW.KO_SELECTION)
            .ifPresent(l -> l.setSelectedItem(updatedKOSelection));

        if (forceUpdate || enableFilter != null) {
          ToggleButtonListener koFilterAction =
              selectedView.getEventManager().getAction(ActionW.KO_FILTER).orElse(null);
          if (koFilterAction != null) {
            if (enableFilter == null) {
              enableFilter =
                  LangUtil.getNULLtoFalse(
                      (Boolean) selectedView.getActionValue(ActionW.KO_FILTER.cmd()));
            }
            koFilterAction.setSelected(enableFilter);
          }
        }

        if (updateAll) {
          List<ViewCanvas<DicomImageElement>> viewList = getImagePanels(true);
          for (ViewCanvas<DicomImageElement> view : viewList) {
            ((View2d) view).updateKOButtonVisibleState();
          }
        } else {
          view2d.updateKOButtonVisibleState();
        }

      } else {
        /*
         * Set the selected view at the end of the list to trigger the synchronization of the SCROLL_SERIES
         * action at the end of the process
         */
        List<ViewCanvas<DicomImageElement>> viewList = getImagePanels(true);

        for (ViewCanvas<DicomImageElement> view : viewList) {

          if (!(view.getSeries() instanceof DicomSeries dicomSeries) || !(view instanceof View2d)) {
            continue;
          }

          if (forceUpdate
              || updatedKOSelection == view.getActionValue(ActionW.KO_SELECTION.cmd())) {
            KOManager.updateKOFilter(
                view, forceUpdate ? updatedKOSelection : null, enableFilter, -1);
          }

          String seriesInstanceUID =
              TagD.getTagValue(dicomSeries, Tag.SeriesInstanceUID, String.class);

          if (!updatedKOSelection.containsSeriesInstanceUIDReference(seriesInstanceUID)) {
            continue;
          }

          ((View2d) view).updateKOButtonVisibleState();
        }
      }

      EventManager.getInstance().updateKeyObjectComponentsListener(selectedView);
    }
  }

  @Override
  public int getViewTypeNumber(GridBagLayoutModel layout, Class<?> defaultClass) {
    return View2dFactory.getViewTypeNumber(layout, defaultClass);
  }

  @Override
  public boolean isViewType(Class<?> defaultClass, String type) {
    if (defaultClass != null) {
      try {
        Class<?> clazz = Class.forName(type);
        return defaultClass.isAssignableFrom(clazz);
      } catch (Exception e) {
        LOGGER.error("Checking view", e);
      }
    }
    return false;
  }

  @Override
  public DefaultView2d<DicomImageElement> createDefaultView(String classType) {
    return new View2d(eventManager);
  }

  @Override
  public JComponent createComponent(String clazz) {
    if (isViewType(DefaultView2d.class, clazz)) {
      return createDefaultView(clazz);
    }

    try {
      // FIXME use classloader.loadClass or injection
      return buildInstance(Class.forName(clazz));
    } catch (Exception e) {
      LOGGER.error("Cannot create {}", clazz, e);
    }
    return null;
  }

  @Override
  public SeriesViewerUI getSeriesViewerUI() {
    return UI;
  }

  @Override
  public Class<?> getSeriesViewerClass() {
    return view2dClass;
  }

  @Override
  public GridBagLayoutModel getDefaultLayoutModel() {
    return VIEWS_1x1;
  }

  @Override
  public List<Action> getExportActions() {
    return selectedImagePane == null ? null : selectedImagePane.getExportActions();
  }

  @Override
  public List<Action> getPrintActions() {
    ArrayList<Action> actions = new ArrayList<>(2);
    final String title = Messages.getString("View2dContainer.print_layout");
    DefaultAction printStd =
        new DefaultAction(
            title,
            ResourceUtil.getIcon(ActionIcon.PRINT),
            event -> {
              ColorLayerUI layer = ColorLayerUI.createTransparentLayerUI(View2dContainer.this);
              PrintDialog<DicomImageElement> dialog =
                  new PrintDialog<>(
                      SwingUtilities.getWindowAncestor(View2dContainer.this), title, eventManager);
              ColorLayerUI.showCenterScreen(dialog, layer);
            });
    printStd.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_P, 0));
    actions.add(printStd);

    final String title2 = Messages.getString("View2dContainer.dcm_print");
    DefaultAction printStd2 =
        new DefaultAction(
            title2,
            null,
            event -> {
              ColorLayerUI layer = ColorLayerUI.createTransparentLayerUI(View2dContainer.this);
              DicomPrintDialog<DicomImageElement> dialog =
                  new DicomPrintDialog<>(
                      SwingUtilities.getWindowAncestor(View2dContainer.this), title2, eventManager);
              ColorLayerUI.showCenterScreen(dialog, layer);
            });
    actions.add(printStd2);
    return actions;
  }

  @Override
  public List<SynchView> getSynchList() {
    return DEFAULT_SYNCH_LIST;
  }

  @Override
  public List<GridBagLayoutModel> getLayoutList() {
    return getLayoutList(this, DEFAULT_LAYOUT_LIST);
  }
}
