package org.jabref;

import java.io.IOException;
import java.net.Authenticator;
import java.util.Map;

import javax.swing.SwingUtilities;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import org.jabref.cli.ArgumentProcessor;
import org.jabref.gui.nativemessaging.NativeMessagingClient;
import org.jabref.gui.nativemessaging.NativeMessagingService;
import org.jabref.gui.nativemessaging.NativeMessagingServiceImpl;
import org.jabref.gui.nativemessaging.StreamNativeMessagingClient;
import org.jabref.gui.remote.JabRefMessageHandler;
import org.jabref.logic.exporter.ExportFormat;
import org.jabref.logic.exporter.ExportFormats;
import org.jabref.logic.exporter.SavePreferences;
import org.jabref.logic.formatter.casechanger.ProtectTermsFormatter;
import org.jabref.logic.journals.JournalAbbreviationLoader;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.layout.LayoutFormatterPreferences;
import org.jabref.logic.net.ProxyAuthenticator;
import org.jabref.logic.net.ProxyPreferences;
import org.jabref.logic.net.ProxyRegisterer;
import org.jabref.logic.protectedterms.ProtectedTermsLoader;
import org.jabref.logic.remote.RemotePreferences;
import org.jabref.logic.remote.client.RemoteListenerClient;
import org.jabref.logic.util.OS;
import org.jabref.migrations.PreferencesMigrations;
import org.jabref.model.EntryTypes;
import org.jabref.model.database.BibDatabaseMode;
import org.jabref.model.entry.InternalBibtexFields;
import org.jabref.preferences.JabRefPreferences;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JabRef MainClass
 */
public class JabRefMain extends Application {

    private static final Log LOGGER = LogFactory.getLog(JabRefMain.class);
    private static String[] arguments;

    public static void main(String[] args) {
        arguments = args;

        launch(arguments);
    }

    private static void start(String[] args) throws IOException {
        NativeMessagingClient nativeMessagingClient = new StreamNativeMessagingClient(System.in, System.out);

        nativeMessagingClient.send("{\"m\":\"ka\"}");

        FallbackExceptionHandler.installExceptionHandler();

        JabRefPreferences preferences = JabRefPreferences.getInstance();

        nativeMessagingClient.send("{\"m\":\"kb\"}");

        ProxyPreferences proxyPreferences = preferences.getProxyPreferences();
        ProxyRegisterer.register(proxyPreferences);
        if (proxyPreferences.isUseProxy() && proxyPreferences.isUseAuthentication()) {
            Authenticator.setDefault(new ProxyAuthenticator());
        }

        nativeMessagingClient.send("{\"m\":\"kc\"}");
        Globals.prefs = preferences;
        Globals.startBackgroundTasks();
        nativeMessagingClient.send("{\"m\":\"kd\"}");
        Localization.setLanguage(preferences.get(JabRefPreferences.LANGUAGE));
        Globals.prefs.setLanguageDependentDefaultValues();
        nativeMessagingClient.send("{\"m\":\"ke\"}");

        // Perform Migrations
        // Perform checks and changes for users with a preference set from an older JabRef version.
        PreferencesMigrations.upgradePrefsToOrgJabRef();
        PreferencesMigrations.upgradeSortOrder();
        PreferencesMigrations.upgradeFaultyEncodingStrings();
        PreferencesMigrations.upgradeLabelPatternToBibtexKeyPattern();
        PreferencesMigrations.upgradeStoredCustomEntryTypes();
        PreferencesMigrations.upgradeKeyBindingsToJavaFX();
        PreferencesMigrations.addCrossRefRelatedFieldsForAutoComplete();

        nativeMessagingClient.send("{\"m\":\"kf\"}");

        // Update handling of special fields based on preferences
        InternalBibtexFields
                .updateSpecialFields(Globals.prefs.getBoolean(JabRefPreferences.SERIALIZESPECIALFIELDS));
        // Update name of the time stamp field based on preferences
        InternalBibtexFields.updateTimeStampField(Globals.prefs.getTimestampPreferences().getTimestampField());
        // Update which fields should be treated as numeric, based on preferences:
        InternalBibtexFields.setNumericFields(Globals.prefs.getStringList(JabRefPreferences.NUMERIC_FIELDS));

        nativeMessagingClient.send("{\"m\":\"kg\"}");

        // Read list(s) of journal names and abbreviations
        Globals.journalAbbreviationLoader = new JournalAbbreviationLoader();

        nativeMessagingClient.send("{\"m\":\"kh\"}");

        /* Build list of Import and Export formats */
        Globals.IMPORT_FORMAT_READER.resetImportFormats(Globals.prefs.getImportFormatPreferences(),
                Globals.prefs.getXMPPreferences());
        EntryTypes.loadCustomEntryTypes(preferences.loadCustomEntryTypes(BibDatabaseMode.BIBTEX),
                preferences.loadCustomEntryTypes(BibDatabaseMode.BIBLATEX));
        Map<String, ExportFormat> customFormats = Globals.prefs.customExports.getCustomExportFormats(Globals.prefs,
                Globals.journalAbbreviationLoader);
        LayoutFormatterPreferences layoutPreferences = Globals.prefs
                .getLayoutFormatterPreferences(Globals.journalAbbreviationLoader);
        SavePreferences savePreferences = SavePreferences.loadForExportFromPreferences(Globals.prefs);
        ExportFormats.initAllExports(customFormats, layoutPreferences, savePreferences);

        nativeMessagingClient.send("{\"m\":\"ki\"}");

        // Initialize protected terms loader
        Globals.protectedTermsLoader = new ProtectedTermsLoader(Globals.prefs.getProtectedTermsPreferences());
        ProtectTermsFormatter.setProtectedTermsLoader(Globals.protectedTermsLoader);

        nativeMessagingClient.send("{\"m\":\"kj\"}");

        // Check for running JabRef
        RemotePreferences remotePreferences = Globals.prefs.getRemotePreferences();
        if (remotePreferences.useRemoteServer()) {
            Globals.REMOTE_LISTENER.open(new JabRefMessageHandler(), remotePreferences.getPort());

            if (!Globals.REMOTE_LISTENER.isOpen()) {
                // we are not alone, there is already a server out there, try to contact already running JabRef:
                if (RemoteListenerClient.sendToActiveJabRefInstance(args, remotePreferences.getPort())) {
                    // We have successfully sent our command line options through the socket to another JabRef instance.
                    // So we assume it's all taken care of, and quit.
                    LOGGER.info(Localization.lang("Arguments passed on to running JabRef instance. Shutting down."));
                    Globals.shutdownThreadPools();
                    // needed to tell JavaFx to stop
                    Platform.exit();
                    return;
                }
            }
            // we are alone, we start the server
            Globals.REMOTE_LISTENER.start();
        }

        nativeMessagingClient.send("{\"m\":\"kk\"}");

        // override used newline character with the one stored in the preferences
        // The preferences return the system newline character sequence as default
        OS.NEWLINE = Globals.prefs.get(JabRefPreferences.NEWLINE);

        nativeMessagingClient.send("{\"m\":\"kl\"}");

        JSONObject obj = new JSONObject();
        obj.put("m", new JSONArray().put(args));
        nativeMessagingClient.send(obj.toString());

        // Process arguments
        ArgumentProcessor argumentProcessor = new ArgumentProcessor(args, ArgumentProcessor.Mode.INITIAL_START);

        nativeMessagingClient.send("{\"m\":\"ko\"}");

        // See if we should shut down now
        if (argumentProcessor.shouldShutDown()) {
            Globals.shutdownThreadPools();
            return;
        }

        // Start native messaging
        nativeMessagingClient.send("{\"m\":\"hi\"}");
        NativeMessagingService messagingService = new NativeMessagingServiceImpl(nativeMessagingClient);

        // If not, start GUI
        SwingUtilities
                .invokeLater(() -> new JabRefGUI(argumentProcessor.getParserResults(),
                        argumentProcessor.isBlank()));
    }

    @Override
    public void start(Stage mainStage) throws Exception {
        Platform.setImplicitExit(false);
        SwingUtilities.invokeLater(() -> {
            try {
                                   start(arguments);
            } catch(IOException e)
          {
               System.out.println(e.getMessage());
          }}
                );
    }
}
