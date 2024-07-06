/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.launcher;

import static java.time.temporal.ChronoUnit.DAYS;
import static org.weasis.pref.ConfigData.F_RESOURCES;
import static org.weasis.pref.ConfigData.P_GOSH_ARGS;
import static org.weasis.pref.ConfigData.P_WEASIS_CODEBASE_LOCAL;
import static org.weasis.pref.ConfigData.P_WEASIS_CODEBASE_URL;
import static org.weasis.pref.ConfigData.P_WEASIS_I18N;
import static org.weasis.pref.ConfigData.P_WEASIS_LOOK;
import static org.weasis.pref.ConfigData.P_WEASIS_PATH;
import static org.weasis.pref.ConfigData.P_WEASIS_RESOURCES_URL;
import static org.weasis.pref.ConfigData.P_WEASIS_RES_DATE;
import static org.weasis.pref.ConfigData.P_WEASIS_SOURCE_ID;
import static org.weasis.pref.ConfigData.P_WEASIS_VERSION;

import com.formdev.flatlaf.FlatSystemProperties;
import com.formdev.flatlaf.util.SystemInfo;
import java.awt.Desktop;
import java.awt.Desktop.Action;
import java.awt.EventQueue;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.ResourceBundle.Control;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.management.ObjectName;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UIManager.LookAndFeelInfo;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import org.apache.felix.framework.Felix;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.Version;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.launcher.LookAndFeels.ReadableLookAndFeelInfo;
import org.weasis.pref.ConfigData;

/**
 * @author Richard S. Hall
 * @author Nicolas Roduit
 */
public class WeasisLauncher {

  private static final Logger LOGGER = LoggerFactory.getLogger(WeasisLauncher.class);

  public enum Type {
    DEFAULT,
    NATIVE
  }

  public enum State {
    UNINSTALLED(0x00000001),
    INSTALLED(0x00000002),
    RESOLVED(0x00000004),
    STARTING(0x00000008),
    STOPPING(0x00000010),
    ACTIVE(0x00000020);

    private final int index;

    State(int state) {
      this.index = state;
    }

    public static String valueOf(int state) {
      for (State s : State.values()) {
        if (s.index == state) {
          return s.name();
        }
      }
      return "UNKNOWN";
    }
  }

  protected Felix mFelix = null;
  protected ServiceTracker mTracker = null;
  protected volatile boolean frameworkLoaded = false;

  protected String look = null;
  protected RemotePrefService remotePrefs;
  protected String localPrefsDir;

  protected final Properties modulesi18n;
  protected final ConfigData configData;

  public WeasisLauncher(ConfigData configData) {
    this.configData = Objects.requireNonNull(configData);
    this.modulesi18n = new Properties();
  }

  public final void launch(Type type) throws Exception {
    Map<String, String> serverProp = configData.getFelixProps();
    String cacheDir = serverProp.get(Constants.FRAMEWORK_STORAGE) + "-" + configData.getSourceID();
    // If there is a passed in bundle cache directory, then
    // that overwrites anything in the config file.
    serverProp.put(Constants.FRAMEWORK_STORAGE, cacheDir);

    // Load local properties and clean if necessary the previous version
    WeasisLoader loader = loadProperties(serverProp, configData.getConfigOutput());
    WeasisMainFrame mainFrame = loader.getMainFrame();

    String minVersion = System.getProperty(ConfigData.P_WEASIS_MIN_NATIVE_VERSION);
    if (Utils.hasText(minVersion)) {
      EventQueue.invokeAndWait(
          () -> {
            String appName = System.getProperty(ConfigData.P_WEASIS_NAME);
            int response =
                JOptionPane.showOptionDialog(
                    mainFrame.getWindow(),
                    String.format(
                        STR."\{
                            Messages.getString("WeasisLauncher.update_min")}\n\n\{
                            Messages.getString("WeasisLauncher.continue_local")}",
                        appName,
                        minVersion),
                    null,
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.ERROR_MESSAGE,
                    null,
                    null,
                    null);

            if (response != 0) {
              LOGGER.error("Do not continue the launch with the local version");
              System.exit(-1);
            }
          });
    }

    Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownHook));
    registerAdditionalShutdownHook();

    displayStartingAsciiIcon();

    int exitStatus = 0;
    try {

      String goshArgs = getGoshArgs(serverProp);
      // Now create an instance of the framework with our configuration properties.
      mFelix = new Felix(serverProp);
      // Initialize the framework, but don't start it yet.
      mFelix.init();

      // Use the system bundle context to process the auto-deploy
      // and auto-install/auto-start properties.
      loader.setFelix(serverProp, mFelix.getBundleContext(), modulesi18n);
      loader.writeLabel(
          String.format(
              Messages.getString("WeasisLauncher.starting"),
              System.getProperty(ConfigData.P_WEASIS_NAME)));
      mTracker =
          new ServiceTracker(
              mFelix.getBundleContext(), "org.apache.felix.service.command.CommandProcessor", null);
      mTracker.open();

      // Start the framework.
      mFelix.start();

      // End of splash screen
      loader.close();
      loader = null;

      String logActivation = serverProp.get("org.apache.sling.commons.log.file");
      if (Utils.hasText(logActivation)) {
        LOGGER.info(
            "Logs has been delegated to the OSGI service and can be read in {}", logActivation);
      }

      // Init after default properties for UI
      Desktop app = Desktop.getDesktop();
      if (app.isSupported(Action.APP_OPEN_URI)) {
        app.setOpenURIHandler(
            e -> {
              String uri = "dicom:get -r \"" + e.getURI().toString() + "\""; // NON-NLS
              LOGGER.info("Get URI event from OS. URI: {}", uri);
              executeCommands(List.of(uri), null);
            });
      }
      if (app.isSupported(Desktop.Action.APP_OPEN_FILE)) {

        app.setOpenFileHandler(
            e -> {
              List<String> files =
                  e.getFiles().stream()
                      .map(f -> "dicom:get -l \"" + f.getPath() + "\"") // NON-NLS
                      .toList();
              LOGGER.info("Get oOpen file event from OS. Files: {}", files);
              executeCommands(files, null);
            });
      }

      executeCommands(configData.getArguments(), goshArgs);

      checkBundleUI(serverProp);
      frameworkLoaded = true;

      showMessage(mainFrame, serverProp);

      // Wait for framework to stop to exit the VM.
      mFelix.waitForStop(0);
      System.exit(0);
    } catch (Throwable ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      exitStatus = -1;
      LOGGER.error("Cannot not start framework.", ex);
      LOGGER.error("Weasis cache will be cleaned at next launch.");
      LOGGER.error("State of the framework:");
      for (Bundle b : mFelix.getBundleContext().getBundles()) {
        LOGGER.error(
            STR." * \{
                b.getSymbolicName()}-\{
                b.getVersion().toString()} \{
                State.valueOf(b.getState())}");
      }
      resetBundleCache();
    } finally {
      Runtime.getRuntime().halt(exitStatus);
    }
  }

  private void checkBundleUI(Map<String, String> serverProp) {
    String mainUI = serverProp.getOrDefault("weasis.main.ui", "").trim(); // NON-NLS
    if (Utils.hasText(mainUI)) {
      boolean uiStarted = false;
      for (Bundle b : mFelix.getBundleContext().getBundles()) {
        if (b.getSymbolicName().equals(mainUI) && b.getState() == Bundle.ACTIVE) {
          uiStarted = true;
          break;
        }
      }
      if (!uiStarted) {
        throw new IllegalStateException("Main User Interface bundle cannot be started");
      }
    }
  }

  private static String getGoshArgs(Map<String, String> serverProp) {
    String goshArgs = System.getProperty(P_GOSH_ARGS, serverProp.getOrDefault(P_GOSH_ARGS, ""));
    if (goshArgs.isEmpty()) {
      String val = System.getProperty("gosh.port", ""); // NON-NLS
      if (!val.isEmpty()) {
        try {
          goshArgs = String.format("-sc telnetd -p %d start", Integer.parseInt(val)); // NON-NLS
        } catch (NumberFormatException e) {
          // Do nothing
        }
      }
    }
    serverProp.put(P_GOSH_ARGS, "--nointeractive --noshutdown"); // NON-NLS
    return goshArgs;
  }

  private static void displayStartingAsciiIcon() {
    String asciiArt =
        """

Starting OSGI Bundles...

        __        __             _    \s
        \\ \\      / /__  __ _ ___(_)___\s
         \\ \\ /\\ / / _ \\/ _` / __| / __|
          \\ V  V /  __/ (_| \\__ \\ \\__ \\
           \\_/\\_/ \\___|\\__,_|___/_|___/

                     """;
    LOGGER.info("\u001B[32m{}\u001B[0m", asciiArt);
  }

  protected void executeCommands(List<String> commandList, String goshArgs) {
    SwingUtilities.invokeLater(
        () -> {
          mTracker.open();

          // Do not close streams. Workaround for stackoverflow issue when using System.in
          Object commandSession =
              getCommandSession(
                  mTracker.getService(),
                  new Object[] {
                    new FileInputStream(FileDescriptor.in),
                    new FileOutputStream(FileDescriptor.out),
                    new FileOutputStream(FileDescriptor.err)
                  });
          if (commandSession != null) {
            if (goshArgs == null) {
              // Set the main window visible and to the front
              commandSessionExecute(commandSession, "weasis:ui -v"); // NON-NLS
            } else {
              // Start telnet after all other bundles. This will ensure that all the plugins
              // commands are
              // activated once telnet is available
              initCommandSession(commandSession, goshArgs);
            }

            // execute the commands from main argv
            for (String command : commandList) {
              commandSessionExecute(commandSession, command);
            }
            commandSessionClose(commandSession);
          }

          mTracker.close();
        });
  }

  private static void resetBundleCache() {
    // Set flag to clean cache at next launch
    File sourceIdProps =
        new File(
            System.getProperty(P_WEASIS_PATH, ""),
            System.getProperty(P_WEASIS_SOURCE_ID) + ".properties"); // NON-NLS
    Properties localSourceProp = new Properties();
    FileUtil.readProperties(sourceIdProps, localSourceProp);
    localSourceProp.setProperty(ConfigData.P_WEASIS_CLEAN_CACHE, Boolean.TRUE.toString());
    FileUtil.storeProperties(sourceIdProps, localSourceProp, null);
  }

  private void showMessage(final WeasisMainFrame mainFrame, Map<String, String> serverProp) {
    String versionOld = serverProp.get("prev." + P_WEASIS_VERSION); // NON-NLS
    String versionNew = serverProp.getOrDefault(P_WEASIS_VERSION, "0.0.0");
    // First time launch
    if (versionOld == null) {
      String val = serverProp.get("prev." + ConfigData.P_WEASIS_SHOW_DISCLAIMER); // NON-NLS
      String accept = serverProp.get(ConfigData.P_WEASIS_ACCEPT_DISCLAIMER);
      if (Utils.geEmptyToTrue(val) && !Utils.getEmptyToFalse(accept)) {

        EventQueue.invokeLater(
            () -> {
              Object[] options = {
                Messages.getString("WeasisLauncher.ok"), Messages.getString("WeasisLauncher.no")
              };

              String appName = System.getProperty(ConfigData.P_WEASIS_NAME);
              int response =
                  JOptionPane.showOptionDialog(
                      mainFrame.getWindow(),
                      String.format(Messages.getString("WeasisLauncher.msg"), appName),
                      String.format(Messages.getString("WeasisLauncher.first"), appName),
                      JOptionPane.YES_NO_OPTION,
                      JOptionPane.WARNING_MESSAGE,
                      null,
                      options,
                      null);

              if (response == 0) {
                // Write "false" in weasis.properties. It can be useful when preferences are store
                // remotely.
                // The user will accept the disclaimer only once.
                System.setProperty(ConfigData.P_WEASIS_ACCEPT_DISCLAIMER, Boolean.TRUE.toString());
              } else {
                File file =
                    new File(
                        System.getProperty(P_WEASIS_PATH, ""),
                        System.getProperty(P_WEASIS_SOURCE_ID) + ".properties");
                // delete the properties file to ask again
                FileUtil.delete(file);
                LOGGER.error("Refusing the disclaimer");
                System.exit(-1);
              }
            });
      }
    } else if (versionNew != null && !versionNew.equals(versionOld)) {
      String val = serverProp.get("prev." + ConfigData.P_WEASIS_SHOW_RELEASE); // NON-NLS
      if (Utils.geEmptyToTrue(val)) {
        try {
          Version vOld = getVersion(versionOld);
          Version vNew = getVersion(versionNew);
          if (vNew.compareTo(vOld) > 0) {
            String lastTag = serverProp.get(ConfigData.P_WEASIS_VERSION_RELEASE);
            if (lastTag != null) {
              vOld = getVersion(lastTag);
              if (vNew.compareTo(vOld) <= 0) {
                // Message has been already displayed once.
                return;
              }
            }
            System.setProperty(ConfigData.P_WEASIS_VERSION_RELEASE, vNew.toString());
          }
        } catch (Exception e2) {
          LOGGER.error("Cannot read version", e2);
          return;
        }
        final String releaseNotesUrl = serverProp.get("weasis.releasenotes"); // NON-NLS
        final StringBuilder message = new StringBuilder("<P>"); // NON-NLS
        message.append(
            String.format(
                Messages.getString("WeasisLauncher.change.version"),
                System.getProperty(ConfigData.P_WEASIS_NAME),
                versionOld,
                versionNew));

        EventQueue.invokeLater(
            () -> {
              JTextPane jTextPane1 = new JTextPane();
              jTextPane1.setBorder(new EmptyBorder(5, 5, 15, 5));
              jTextPane1.setContentType("text/html");
              jTextPane1.setEditable(false);
              jTextPane1.addHyperlinkListener(
                  e -> {
                    JTextPane pane = (JTextPane) e.getSource();
                    if (e.getEventType() == HyperlinkEvent.EventType.ENTERED) {
                      pane.setToolTipText(e.getDescription());
                    } else if (e.getEventType() == HyperlinkEvent.EventType.EXITED) {
                      pane.setToolTipText(null);
                    } else if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                      Utils.openInDefaultBrowser(e.getURL());
                    }
                  });

              message.append("<BR>");
              String rn = Messages.getString("WeasisLauncher.release"); // NON-NLS
              message.append(
                  String.format(
                      "<a href=\"%s", // NON-NLS
                      releaseNotesUrl));
              message.append("\">"); // NON-NLS
              message.append(rn);
              message.append("</a>"); // NON-NLS
              message.append("</P>"); // NON-NLS
              jTextPane1.setText(message.toString());
              JOptionPane.showMessageDialog(
                  mainFrame.getWindow(),
                  jTextPane1,
                  Messages.getString("WeasisLauncher.News"),
                  JOptionPane.INFORMATION_MESSAGE);
            });
      }
    }
  }

  private static Version getVersion(String version) {
    String v = "";
    if (version != null) {
      int index = version.indexOf('-');
      v = index > 0 ? version.substring(0, index) : version;
    }
    return new Version(v);
  }

  public static Object getCommandSession(Object commandProcessor, Object[] arguments) {
    if (commandProcessor == null) {
      return null;
    }
    Class<?>[] parameterTypes =
        new Class[] {InputStream.class, OutputStream.class, OutputStream.class};
    try {
      Method nameMethod = commandProcessor.getClass().getMethod("createSession", parameterTypes);
      Object commandSession = nameMethod.invoke(commandProcessor, arguments);
      addCommandSessionListener(commandProcessor);
      return commandSession;
    } catch (Exception ex) {
      // Since the services returned by the tracker could become
      // invalid at any moment, we will catch all exceptions, log
      // a message, and then ignore faulty services.
      LOGGER.error("Create a command session", ex);
    }

    return null;
  }

  private static void addCommandSessionListener(Object commandProcessor) {
    try {
      ClassLoader loader = commandProcessor.getClass().getClassLoader();
      Class<?> c = loader.loadClass("org.apache.felix.service.command.CommandSessionListener");
      Method nameMethod = commandProcessor.getClass().getMethod("addListener", c);

      Object listener =
          Proxy.newProxyInstance(
              loader,
              new Class[] {c},
              (proxy, method, args) -> {
                String listenerMethod = method.getName();

                if (listenerMethod.equals("beforeExecute")) {
                  String arg = args[1].toString();
                  if (arg.startsWith("gosh") || arg.startsWith("gogo:gosh")) { // NON-NLS
                    // Force gogo to not use Expander to concatenate parameter with the current
                    // directory (Otherwise "*(|<[?" are interpreted, issue with URI parameters)
                    commandSessionExecute(args[0], "gogo.option.noglob=on"); // NON-NLS
                  }
                } else if (listenerMethod.equals("equals")) { // NON-NLS
                  // Only add once in the set of listeners
                  return proxy.getClass().isAssignableFrom((args[0].getClass()));
                }
                return null;
              });
      nameMethod.invoke(commandProcessor, listener);
    } catch (Exception e) {
      LOGGER.error("Add command session listener", e);
    }
  }

  public static boolean initCommandSession(Object commandSession, String args) {
    try {
      // wait for gosh command to be registered
      for (int i = 0;
          (i < 100) && commandSessionGet(commandSession, "gogo:gosh") == null; // NON-NLS
          ++i) {
        TimeUnit.MILLISECONDS.sleep(10);
      }

      Class<?>[] parameterTypes = new Class[] {CharSequence.class};
      Object[] arguments =
          new Object[] {"gogo:gosh --login " + (args == null ? "" : args)}; // NON-NLS
      Method nameMethod = commandSession.getClass().getMethod("execute", parameterTypes);
      nameMethod.invoke(commandSession, arguments);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      LOGGER.error("Init command session", e);
    }
    return false;
  }

  public static Object commandSessionGet(Object commandSession, String key) {
    if (commandSession == null || key == null) {
      return null;
    }

    Class<?>[] parameterTypes = new Class[] {String.class};
    Object[] arguments = new Object[] {key};

    try {
      Method nameMethod = commandSession.getClass().getMethod("get", parameterTypes);
      return nameMethod.invoke(commandSession, arguments);
    } catch (Exception ex) {
      LOGGER.error("Invoke a command", ex);
    }

    return null;
  }

  public static boolean commandSessionClose(Object commandSession) {
    if (commandSession == null) {
      return false;
    }
    try {
      Method nameMethod = commandSession.getClass().getMethod("close");
      nameMethod.invoke(commandSession);
      return true;
    } catch (Exception ex) {
      LOGGER.error("Close command session", ex);
    }

    return false;
  }

  public static Object commandSessionExecute(Object commandSession, CharSequence charSequence) {
    if (commandSession == null) {
      return false;
    }
    Class<?>[] parameterTypes = new Class[] {CharSequence.class};

    Object[] arguments = new Object[] {charSequence};

    try {
      Method nameMethod = commandSession.getClass().getMethod("execute", parameterTypes);
      return nameMethod.invoke(commandSession, arguments);
    } catch (Exception ex) {
      LOGGER.error("Execute command", ex);
    }

    return null;
  }

  /** This following part has been copied from the Main class of the Felix project */
  public static void readProperties(URI propURI, Properties props) {
    try (InputStream is = FileUtil.getAdaptedConnection(propURI.toURL(), false).getInputStream()) {
      props.load(is);
    } catch (Exception ex) {
      LOGGER.error("Cannot read properties file: {}", propURI, ex);
    }
  }

  private static String getGeneralProperty(
      String key,
      String defaultValue,
      Map<String, String> serverProp,
      Properties localProp,
      boolean storeInLocalPref,
      boolean serviceProperty) {
    String value = localProp.getProperty(key, null);
    String defaultVal = System.getProperty(key, null);
    if (defaultVal == null) {
      defaultVal = serverProp.getOrDefault(key, defaultValue);
    }

    if (value == null) {
      value = defaultVal;
      if (storeInLocalPref && value != null) {
        // When first launch, set property that can be written later
        localProp.setProperty(key, value);
      }
    }
    if (serviceProperty) {
      serverProp.put(key, value);
      serverProp.put("def." + key, defaultVal); // NON-NLS
    }
    LOGGER.info("Config of {} = {}", key, value);
    return value;
  }

  public WeasisLoader loadProperties(Map<String, String> serverProp, StringBuilder conf) {
    String dir = configData.getProperty(P_WEASIS_PATH);
    String profileName = configData.getProperty(ConfigData.P_WEASIS_PROFILE, "default"); // NON-NLS
    String user = configData.getProperty(ConfigData.P_WEASIS_USER);

    // If proxy configuration, activate it
    configData.applyProxy(
        dir + File.separator + "data" + File.separator + "weasis-core-ui"); // NON-NLS

    StringBuilder bufDir = new StringBuilder(dir);
    bufDir.append(File.separator);
    bufDir.append("preferences"); // NON-NLS
    bufDir.append(File.separator);
    bufDir.append(user);
    bufDir.append(File.separator);
    bufDir.append(profileName);
    File prefDir = new File(bufDir.toString());
    try {
      prefDir.mkdirs();
    } catch (Exception e) {
      prefDir = new File(dir);
      LOGGER.error("Cannot create preferences folders", e);
    }
    localPrefsDir = prefDir.getPath();
    serverProp.put("weasis.pref.dir", prefDir.getPath());

    Properties currentProps = new Properties();
    FileUtil.readProperties(new File(prefDir, ConfigData.APP_PROPERTY_FILE), currentProps);
    currentProps
        .stringPropertyNames()
        .forEach(key -> serverProp.put("wp.init." + key, currentProps.getProperty(key))); // NON-NLS

    String remotePrefURL = configData.getProperty(ConfigData.P_WEASIS_PREFS_URL);
    if (Utils.hasText(remotePrefURL)) {
      String storeLocalSession = "weasis.pref.store.local.session";
      String defaultVal = configData.getProperty(storeLocalSession, null);
      if (defaultVal == null) {
        defaultVal = serverProp.getOrDefault(storeLocalSession, Boolean.FALSE.toString());
      }
      serverProp.put(storeLocalSession, defaultVal);
      try {
        remotePrefs = new RemotePrefService(remotePrefURL, serverProp, user, profileName);
        Properties remote = remotePrefs.readLauncherPref(null);
        currentProps.putAll(remote); // merge remote to local
        if (remote.size() < currentProps.size()) {
          // Force to have difference for saving preferences
          serverProp.put("wp.init.diff.remote.pref", Boolean.TRUE.toString()); // NON-NLS
        }
      } catch (Exception e) {
        LOGGER.error("Cannot read Launcher preference for user: {}", user, e);
      }
    }

    // General Preferences priority order:
    // 1) Last value (does not exist for first launch of Weasis in an operating system session).
    // 2) Java System property
    // 3) Property defined in base.json or in other profile json files
    // 4) default value
    final String lang =
        getGeneralProperty(
            "locale.lang.code", "en", serverProp, currentProps, true, false); // NON-NLS
    getGeneralProperty(
        "locale.format.code", "system", serverProp, currentProps, true, false); // NON-NLS

    // Set value back to the bundle context properties, sling logger uses
    // bundleContext.getProperty(prop)
    getGeneralProperty(
        "org.apache.sling.commons.log.level", "INFO", serverProp, currentProps, true, true);
    // Empty string make the file log writer disable
    String logActivation =
        getGeneralProperty(
            "org.apache.sling.commons.log.file.activate",
            Boolean.FALSE.toString(),
            serverProp,
            currentProps,
            true,
            true);
    if (Utils.getEmptyToFalse(logActivation)) {
      String logFile = dir + File.separator + "log" + File.separator + "default.log"; // NON-NLS
      serverProp.put("org.apache.sling.commons.log.file", logFile);
      currentProps.remove("org.apache.sling.commons.log.file");
    }

    getGeneralProperty(
        "org.apache.sling.commons.log.file.number", "20", serverProp, currentProps, true, true);
    getGeneralProperty(
        "org.apache.sling.commons.log.file.size",
        "10MB", // NON-NLS
        serverProp,
        currentProps,
        true,
        true);
    getGeneralProperty(
        "org.apache.sling.commons.log.stack.limit", "3", serverProp, currentProps, true, true);
    getGeneralProperty(
        "org.apache.sling.commons.log.pattern",
        "%d{dd.MM.yyyy HH:mm:ss.SSS} *%-5level* [%thread] %logger{36}: %msg%ex{3}%n", // NON-NLS
        serverProp,
        currentProps,
        false,
        true);

    loadI18nModules();

    Locale locale = textToLocale(lang);
    if (Locale.ENGLISH.equals(locale)) {
      // if English no need to load i18n bundle fragments
      modulesi18n.clear();
    } else {
      // Get the default i18n suffix for properties files
      String suffix = Control.getControl(Control.FORMAT_PROPERTIES).toBundleName("", locale);
      SwingResources.loadResources("/swing/basic" + suffix + ".properties"); // NON-NLS
      SwingResources.loadResources("/swing/synth" + suffix + ".properties"); // NON-NLS
    }

    String nativeLook;
    String sysSpec = System.getProperty(ConfigData.P_NATIVE_LIB_SPEC, "unknown"); // NON-NLS
    int index = sysSpec.indexOf('-');
    if (index > 0) {
      nativeLook = "weasis.theme." + sysSpec.substring(0, index); // NON-NLS
      look = System.getProperty(nativeLook, null);
      if (look == null) {
        look = serverProp.get(nativeLook);
      }
    }
    if (look == null) {
      look = System.getProperty(P_WEASIS_LOOK, null);
      if (look == null) {
        look = serverProp.get(P_WEASIS_LOOK);
      }
    }
    String localLook = currentProps.getProperty(P_WEASIS_LOOK, null);
    // If look is in local preferences, use it
    if (localLook != null) {
      look = localLook;
    }
    final LookAndFeels lookAndFeels = new LookAndFeels();
    final ReadableLookAndFeelInfo lookAndFeelInfo =
        lookAndFeels.getAvailableLookAndFeel(look, profileName);

    // See https://github.com/JFormDesigner/FlatLaf/issues/482
    if (SystemInfo.isLinux) {
      String decoration =
          getGeneralProperty(
              "weasis.linux.windows.decoration",
              Boolean.FALSE.toString(),
              serverProp,
              currentProps,
              true,
              true);
      if (Utils.getEmptyToFalse(decoration)) {
        // enable custom window decorations
        JFrame.setDefaultLookAndFeelDecorated(true);
        JDialog.setDefaultLookAndFeelDecorated(true);
      }
    } else if (SystemInfo.isMacOS) {
      // Enable screen menu bar - MUST BE initialized before UI components
      System.setProperty("apple.laf.useScreenMenuBar", "true");
      System.setProperty(
          "apple.awt.application.name", System.getProperty(ConfigData.P_WEASIS_NAME));
      System.setProperty(
          "apple.awt.application.appearance",
          lookAndFeelInfo.isDark() ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua");
    }

    // JVM Locale
    Locale.setDefault(locale);
    // LookAndFeel Locale
    UIManager.getDefaults().setDefaultLocale(locale);
    // For new components
    JComponent.setDefaultLocale(locale);

    UIManager.setInstalledLookAndFeels(
        lookAndFeels.getLookAndFeels().toArray(new LookAndFeelInfo[0]));

    final String scaleFactor =
        getGeneralProperty(
            FlatSystemProperties.UI_SCALE, null, serverProp, currentProps, true, false);
    if (scaleFactor != null) {
      System.setProperty(FlatSystemProperties.UI_SCALE, scaleFactor);
    }

    /*
     * Build a Frame
     *
     * This will ensure the popup message or other dialogs to have frame parent. When the parent is
     *  null the dialog can be hidden under the main frame
     */
    final WeasisMainFrame mainFrame = new WeasisMainFrame();

    try {
      SwingUtilities.invokeAndWait(
          () -> {
            // Set look and feels
            look = lookAndFeels.setLookAndFeel(lookAndFeelInfo);

            try {
              mainFrame.setConfigData(configData);
              // Build a JFrame which will be used later in base.ui module
              ObjectName objectName2 = new ObjectName("weasis:name=MainWindow"); // NON-NLS
              mainFrame.setRootPaneContainer(new JFrame());
              ManagementFactory.getPlatformMBeanServer().registerMBean(mainFrame, objectName2);
            } catch (Exception e1) {
              LOGGER.error("Cannot register the main frame", e1);
            }
          });
    } catch (Exception e) {
      LOGGER.error("Unable to set the Look&Feel {}", look);
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
    currentProps.put(P_WEASIS_LOOK, look);

    File sourceIDProps =
        new File(dir, configData.getProperty(P_WEASIS_SOURCE_ID) + ".properties"); // NON-NLS
    Properties localSourceProp = new Properties();
    FileUtil.readProperties(sourceIDProps, localSourceProp);

    final String versionOld = localSourceProp.getProperty(P_WEASIS_VERSION);
    if (Utils.hasText(versionOld)) {
      serverProp.put("prev." + P_WEASIS_VERSION, versionOld); // NON-NLS
    }
    final String versionNew = serverProp.getOrDefault(P_WEASIS_VERSION, "0.0.0"); // NON-NLS
    String cleanCacheAfterCrash = localSourceProp.getProperty(ConfigData.P_WEASIS_CLEAN_CACHE);

    boolean update = false;
    // Loads the resource files
    String defaultResources = "/resources.zip"; // NON-NLS
    boolean mavenRepo = Utils.hasText(System.getProperty("maven.localRepository", null));
    String resPath = configData.getProperty(P_WEASIS_RESOURCES_URL, null);
    if (!Utils.hasText(resPath)) {
      resPath = serverProp.getOrDefault(P_WEASIS_RESOURCES_URL, null);
      if (!mavenRepo && !Utils.hasText(resPath)) {
        String cdb = configData.getProperty(P_WEASIS_CODEBASE_URL, null);
        // Don't try to guess remote URL from pure local distribution
        if (Utils.hasText(cdb) && !cdb.startsWith("file:")) { // NON-NLS
          resPath = cdb + defaultResources;
        }
      }
    }
    File cacheDir = null;
    try {
      if (isZipResource(resPath)) {
        cacheDir =
            new File(
                dir
                    + File.separator
                    + "data"
                    + File.separator
                    + System.getProperty(P_WEASIS_SOURCE_ID),
                F_RESOURCES);
        String date =
            FileUtil.writeResources(
                resPath, cacheDir, localSourceProp.getProperty(P_WEASIS_RES_DATE));
        if (date != null) {
          update = true;
          localSourceProp.put(P_WEASIS_RES_DATE, date);
        }
      }
    } catch (Exception e) {
      cacheDir = null;
      LOGGER.error("Loads the resource folder", e);
    }

    if (cacheDir == null) {
      if (mavenRepo) {
        // In Development mode
        File f = new File(System.getProperty("user.dir"));
        cacheDir = new File(f.getParent(), "weasis-distributions" + File.separator + F_RESOURCES);
      } else {
        String cdbl = configData.getProperty(P_WEASIS_CODEBASE_LOCAL);
        cacheDir = new File(cdbl, F_RESOURCES);
      }
    }
    serverProp.put("weasis.resources.path", cacheDir.getPath());

    // Splash screen that shows bundles loading
    final WeasisLoader loader = new WeasisLoader(cacheDir.toPath(), mainFrame);
    // Display splash screen
    loader.open();

    if (versionNew != null) {
      localSourceProp.put(P_WEASIS_VERSION, versionNew);
      if (versionOld == null || !versionOld.equals(versionNew)) {
        update = true;
      }
    }
    String showDisclaimer =
        getGeneralProperty(
            ConfigData.P_WEASIS_SHOW_DISCLAIMER,
            Boolean.TRUE.toString(),
            serverProp,
            currentProps,
            false,
            false);
    if (Utils.hasText(showDisclaimer)) {
      serverProp.put("prev." + ConfigData.P_WEASIS_SHOW_DISCLAIMER, showDisclaimer); // NON-NLS
    }
    String showRelease =
        getGeneralProperty(
            ConfigData.P_WEASIS_SHOW_RELEASE,
            Boolean.TRUE.toString(),
            serverProp,
            currentProps,
            false,
            false);
    if (Utils.hasText(showRelease)) {
      serverProp.put("prev." + ConfigData.P_WEASIS_SHOW_RELEASE, showRelease); // NON-NLS
    }

    // Clean cache if Weasis has crashed during the previous launch
    boolean cleanCache = Boolean.parseBoolean(serverProp.get("weasis.clean.previous.version"));
    if (Boolean.TRUE.toString().equals(cleanCacheAfterCrash)) {
      serverProp.put(
          Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
      localSourceProp.remove(ConfigData.P_WEASIS_CLEAN_CACHE);
      update = true;
      LOGGER.info("Clean plug-in cache because Weasis has crashed during the previous launch");
    }
    // Clean cache when version has changed
    else if (cleanCache && versionNew != null && !versionNew.equals(versionOld)) {
      LOGGER.info("Clean previous Weasis version: {}", versionOld);
      serverProp.put(
          Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
      LOGGER.info("Clean plug-in cache because the version has changed");
    }

    if (update) {
      FileUtil.storeProperties(sourceIDProps, localSourceProp, null);
      // Reset message when deploying a new release
      currentProps.setProperty("weasis.show.update.next.release", Boolean.TRUE.toString());
    }

    // Transmit weasis.properties
    Set<String> pKeys = currentProps.stringPropertyNames();
    serverProp.put("wp.list", String.join(",", pKeys)); // NON-NLS
    pKeys.forEach(key -> serverProp.put(key, currentProps.getProperty(key)));

    String pevConf = conf.toString();
    conf.setLength(0);
    conf.append("\n***** Configuration *****"); // NON-NLS
    conf.append("\n  Last running version = "); // NON-NLS
    conf.append(versionOld);
    conf.append("\n  Current version = "); // NON-NLS
    conf.append(versionNew);
    conf.append("\n  Application name = "); // NON-NLS
    conf.append(configData.getProperty(ConfigData.P_WEASIS_NAME));
    conf.append("\n  Application Source ID = "); // NON-NLS
    conf.append(System.getProperty(P_WEASIS_SOURCE_ID));
    conf.append("\n  Application Profile = "); // NON-NLS
    conf.append(profileName);
    conf.append(pevConf);
    conf.append("\n  User = "); // NON-NLS
    conf.append(System.getProperty(ConfigData.P_WEASIS_USER, "user")); // NON-NLS
    conf.append("\n  User home directory = "); // NON-NLS
    conf.append(dir);
    conf.append("\n  Resources path = "); // NON-NLS
    conf.append(cacheDir.getPath());
    conf.append("\n  Preferences directory = "); // NON-NLS
    conf.append(prefDir.getPath());
    conf.append("\n  Look and Feel = "); // NON-NLS
    conf.append(look);
    String i18nPath = System.getProperty(P_WEASIS_I18N);
    if (Utils.hasText(i18nPath)) {
      conf.append("\n  Languages path = "); // NON-NLS
      conf.append(i18nPath);
    }
    conf.append("\n  Languages available = "); // NON-NLS
    conf.append(System.getProperty("weasis.languages", "en")); // NON-NLS
    conf.append("\n  OSGI native specs = "); // NON-NLS
    conf.append(System.getProperty(ConfigData.P_NATIVE_LIB_SPEC));
    conf.append("\n  HTTP user agent = "); // NON-NLS
    conf.append(System.getProperty("http.agent")); // NON-NLS
    conf.append("\n  Operating system = "); // NON-NLS
    conf.append(System.getProperty(ConfigData.P_OS_NAME));
    conf.append(' ');
    conf.append(System.getProperty("os.version"));
    conf.append(' ');
    conf.append(System.getProperty("os.arch"));
    conf.append("\n  Java vendor = "); // NON-NLS
    conf.append(System.getProperty("java.vendor"));
    conf.append("\n  Java version = "); // NON-NLS
    conf.append(System.getProperty("java.version")); // NON-NLS
    conf.append("\n  Java Path = "); // NON-NLS
    conf.append(System.getProperty("java.home")); // NON-NLS
    conf.append("\n  Java max memory (less survivor space) = "); // NON-NLS
    conf.append(FileUtil.humanReadableByteCount(Runtime.getRuntime().maxMemory(), false));

    conf.append("\n***** End of Configuration *****"); // NON-NLS
    LOGGER.info(conf.toString());
    return loader;
  }

  private static boolean isZipResource(String path) {
    if (Utils.hasText(path) && path.endsWith(".zip")) {
      if (path.startsWith("file:")) { // NON-NLS
        try {
          URLConnection connection = new URL(path).openConnection();
          return connection.getContentLength() > 0;
        } catch (IOException e) {
          // Do nothing
        }
      } else {
        return true;
      }
    }
    return false;
  }

  private void loadI18nModules() {
    try {
      String cdbl = configData.getProperty(P_WEASIS_CODEBASE_LOCAL);
      String path = configData.getProperty(P_WEASIS_I18N, null);
      if (Utils.hasText(path)) {
        path +=
            path.endsWith("/") ? "buildNumber.properties" : "/buildNumber.properties"; // NON-NLS
        WeasisLauncher.readProperties(new URI(path), modulesi18n);
      } else if (cdbl == null) {
        String cdb = configData.getProperty(P_WEASIS_CODEBASE_URL, null);
        if (Utils.hasText(cdb)) {
          path = cdb.substring(0, cdb.lastIndexOf('/')) + "/weasis-i18n"; // NON-NLS
          WeasisLauncher.readProperties(
              new URI(path + "/buildNumber.properties"), modulesi18n); // NON-NLS
          if (!modulesi18n.isEmpty()) {
            System.setProperty(P_WEASIS_I18N, path);
          }
        }
      }

      // Try to find the native installation
      if (modulesi18n.isEmpty()) {
        if (cdbl == null) {
          cdbl = ConfigData.findLocalCodebase().getPath();
        }
        File file =
            new File(cdbl, "bundle-i18n" + File.separator + "buildNumber.properties"); // NON-NLS
        if (file.canRead()) {
          WeasisLauncher.readProperties(file.toURI(), modulesi18n);
          if (!modulesi18n.isEmpty()) {
            System.setProperty(P_WEASIS_I18N, file.getParentFile().toURI().toString());
          }
        }
      }

      if (!modulesi18n.isEmpty()) {
        System.setProperty("weasis.languages", modulesi18n.getProperty("languages", "")); // NON-NLS
      }
    } catch (Exception e) {
      LOGGER.error("Cannot load translation modules", e);
    }
  }

  static class HaltTask extends TimerTask {
    @Override
    public void run() {
      System.out.println("Force to close the application"); // NON-NLS
      Runtime.getRuntime().halt(1);
    }
  }

  /**
   * Returns the <code>Locale</code> value according the IETF BCP 47 language tag or the suffix of
   * the i18n jars. Null or empty string will return the ENGLISH <code>Locale</code>. The value
   * "system " returns the system default <code>Locale</code>.
   *
   * @return the <code>Locale</code> value
   */
  public static Locale textToLocale(String value) {
    if (!Utils.hasText(value)) {
      return Locale.ENGLISH;
    }

    if (!"system".equals(value)) { // NON-NLS
      return Locale.forLanguageTag(value.replace("_", "-"));
    }
    return Locale.getDefault();
  }

  private void registerAdditionalShutdownHook() {
    try {
      Class.forName("sun.misc.Signal");
      Class.forName("sun.misc.SignalHandler");
      sun.misc.Signal.handle(new sun.misc.Signal("TERM"), _ -> shutdownHook());
    } catch (IllegalArgumentException e) {
      LOGGER.error("Register shutdownHook", e);
    } catch (ClassNotFoundException e) {
      LOGGER.error("Cannot find sun.misc.Signal for shutdown hook extension", e);
    }
  }

  private void shutdownHook() {
    try {
      if (mFelix != null) {
        mFelix.stop();
        // wait asynchronous stop (max 30 seconds to stop all bundles)
        mFelix.waitForStop(30_000);
      }
    } catch (Exception ex) {
      System.err.println(STR."Error stopping framework: \{ex}"); // NON-NLS
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    } finally {
      cleanImageCache();
      stopSingletonServer();
      cleanOldFolders();

      // If System.exit() hangs call Runtime.getRuntime().halt(1) to kill the application
      Timer timer = new Timer();
      timer.schedule(new HaltTask(), 15000);
    }
  }

  private void cleanOldFolders() {
    String dir = System.getProperty(P_WEASIS_PATH);
    if (Utils.hasText(dir)) {
      Path path = Paths.get(dir);
      if (Files.isDirectory(path)) {
        try {
          List<Path> folders = listOldFolders(path);
          folders.forEach(
              p -> {
                System.err.println("Delete old folder: " + p); // NON-NLS
                FileUtil.delete(p.toFile());
                Optional<String> id = getID(p.getFileName().toString());
                if (id.isPresent()) {
                  Path file = Paths.get(dir, id.get() + ".properties");
                  if (Files.isReadable(file)) {
                    FileUtil.delete(file.toFile());
                  }
                  Path data = Paths.get(dir, "data", id.get());
                  if (Files.isReadable(data)) {
                    FileUtil.delete(data.toFile());
                  }
                }
              });
        } catch (IOException e) {
          System.err.println("Cannot clean old folders - " + e); // NON-NLS
        }
      }
    }
  }

  private List<Path> listOldFolders(Path dir) throws IOException {
    long days =
        Math.max(Long.parseLong(System.getProperty("weasis.clean.old.version.days", "100")), 30);
    try (Stream<Path> stream = Files.list(dir)) {
      return stream
          .filter(
              path ->
                  Files.isDirectory(path)
                      && path.getFileName().toString().startsWith("cache-")
                      && isOlderThan(path, days))
          .toList();
    }
  }

  private boolean isOlderThan(Path path, long days) {
    try {
      FileTime fileTime = Files.getLastModifiedTime(path);
      LocalDateTime now = LocalDateTime.now();
      LocalDateTime convertedFileTime =
          LocalDateTime.ofInstant(fileTime.toInstant(), ZoneId.systemDefault());
      long daysBetween = DAYS.between(convertedFileTime, now);
      return daysBetween > days;
    } catch (Exception e) {
      System.err.println("Cannot get the last modified time - " + e); // NON-NLS
    }
    return false;
  }

  public Optional<String> getID(String filename) {
    return Optional.ofNullable(filename)
        .filter(f -> f.contains("-"))
        .map(f -> f.substring(filename.lastIndexOf("-") + 1));
  }

  protected void stopSingletonServer() {
    // Do nothing in this class
  }

  static void cleanImageCache() {
    // Clean temp folder.
    String dir = System.getProperty("weasis.tmp.dir");
    if (Utils.hasText(dir)) {
      FileUtil.deleteDirectoryContents(new File(dir), 3, 0);
    }
  }
}
