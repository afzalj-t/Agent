/*******************************************************************************
 * Copyright (c) 2018 Edgeworx, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Saeid Baghbidi
 * Kilton Hopkins
 * Ashita Nagar
 *******************************************************************************/
package org.eclipse.iofog.field_agent;

import org.apache.commons.lang.SystemUtils;
import org.eclipse.iofog.IOFogModule;
import org.eclipse.iofog.command_line.util.CommandShellExecutor;
import org.eclipse.iofog.command_line.util.CommandShellResultSet;
import org.eclipse.iofog.connector_client.*;
import org.eclipse.iofog.diagnostics.ImageDownloadManager;
import org.eclipse.iofog.diagnostics.strace.MicroserviceStraceData;
import org.eclipse.iofog.diagnostics.strace.StraceDiagnosticManager;
import org.eclipse.iofog.field_agent.enums.RequestType;
import org.eclipse.iofog.local_api.LocalApi;
import org.eclipse.iofog.message_bus.MessageBus;
import org.eclipse.iofog.microservice.*;
import org.eclipse.iofog.network.IOFogNetworkInterface;
import org.eclipse.iofog.process_manager.ProcessManager;
import org.eclipse.iofog.proxy.SshConnection;
import org.eclipse.iofog.proxy.SshProxyManager;
import org.eclipse.iofog.status_reporter.StatusReporter;
import org.eclipse.iofog.utils.Orchestrator;
import org.eclipse.iofog.utils.configuration.Configuration;
import org.eclipse.iofog.utils.logging.LoggingService;

import javax.json.*;
import javax.net.ssl.SSLHandshakeException;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.HttpMethod;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.netty.util.internal.StringUtil.isNullOrEmpty;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.eclipse.iofog.command_line.CommandLineConfigParam.*;
import static org.eclipse.iofog.field_agent.VersionHandler.isReadyToRollback;
import static org.eclipse.iofog.field_agent.VersionHandler.isReadyToUpgrade;
import static org.eclipse.iofog.resource_manager.ResourceManager.*;
import static org.eclipse.iofog.utils.CmdProperties.getVersion;
import static org.eclipse.iofog.utils.Constants.*;
import static org.eclipse.iofog.utils.Constants.ControllerStatus.NOT_PROVISIONED;
import static org.eclipse.iofog.utils.Constants.ControllerStatus.OK;

/**
 * Field Agent module
 *
 * @author saeid
 */
public class FieldAgent implements IOFogModule {

    private final String MODULE_NAME = "Field Agent";
    private final String filesPath = SystemUtils.IS_OS_WINDOWS ? SNAP_COMMON + "./etc/iofog-agent/" : SNAP_COMMON + "/etc/iofog-agent/";

    private Orchestrator orchestrator;
    private SshProxyManager sshProxyManager;
    private long lastGetChangesList;
    private MicroserviceManager microserviceManager;
    private ConnectorManager connectorManager;
    private static FieldAgent instance;
    private boolean initialization;
    private boolean connected = false;

    private FieldAgent() {
        lastGetChangesList = 0;
        initialization = true;
    }

    @Override
    public int getModuleIndex() {
        return FIELD_AGENT;
    }

    @Override
    public String getModuleName() {
        return MODULE_NAME;
    }

    public static FieldAgent getInstance() {
        if (instance == null) {
            synchronized (FieldAgent.class) {
                if (instance == null)
                    instance = new FieldAgent();
            }
        }
        return instance;
    }

    /**
     * creates IOFog status report
     *
     * @return Map
     */
    private JsonObject getFogStatus() {
        return Json.createObjectBuilder()
                .add("daemonStatus", StatusReporter.getSupervisorStatus().getDaemonStatus().toString())
                .add("daemonOperatingDuration", StatusReporter.getSupervisorStatus().getOperationDuration())
                .add("daemonLastStart", StatusReporter.getSupervisorStatus().getDaemonLastStart())
                .add("memoryUsage", StatusReporter.getResourceConsumptionManagerStatus().getMemoryUsage())
                .add("diskUsage", StatusReporter.getResourceConsumptionManagerStatus().getDiskUsage())
                .add("cpuUsage", StatusReporter.getResourceConsumptionManagerStatus().getCpuUsage())
                .add("memoryViolation", StatusReporter.getResourceConsumptionManagerStatus().isMemoryViolation())
                .add("diskViolation", StatusReporter.getResourceConsumptionManagerStatus().isDiskViolation())
                .add("cpuViolation", StatusReporter.getResourceConsumptionManagerStatus().isCpuViolation())
                .add("microserviceStatus", StatusReporter.getProcessManagerStatus().getJsonMicroservicesStatus())
                .add("repositoryCount", StatusReporter.getProcessManagerStatus().getRegistriesCount())
                .add("repositoryStatus", StatusReporter.getProcessManagerStatus().getJsonRegistriesStatus())
                .add("systemTime", StatusReporter.getStatusReporterStatus().getSystemTime())
                .add("lastStatusTime", StatusReporter.getStatusReporterStatus().getLastUpdate())
                .add("ipAddress", IOFogNetworkInterface.getCurrentIpAddress())
                .add("processedMessages", StatusReporter.getMessageBusStatus().getProcessedMessages())
                .add("microserviceMessageCounts", StatusReporter.getMessageBusStatus().getJsonPublishedMessagesPerMicroservice())
                .add("messageSpeed", StatusReporter.getMessageBusStatus().getAverageSpeed())
                .add("lastCommandTime", StatusReporter.getFieldAgentStatus().getLastCommandTime())
                .add("tunnelStatus", StatusReporter.getSshManagerStatus().getJsonProxyStatus())
                .add("version", getVersion())
                .add("isReadyToUpgrade", isReadyToUpgrade())
                .add("isReadyToRollback", isReadyToRollback())
                .build();
    }

    /**
     * executes actions after successful status post request
     */
    private void onPostStatusSuccess() {
        StatusReporter.getProcessManagerStatus().removeNotRunningMicroserviceStatus();
    }

    /**
     * checks if IOFog is not provisioned
     *
     * @return boolean
     */
    private boolean notProvisioned() {
        boolean notProvisioned = StatusReporter.getFieldAgentStatus().getControllerStatus().equals(NOT_PROVISIONED);
        if (notProvisioned) {
            logWarning("Not provisioned");
        }
        return notProvisioned;
    }

    /**
     * sends IOFog instance status to IOFog controller
     */
    private final Runnable postStatus = () -> {
        while (true) {
            logInfo("Start posting ioFog status");
            try {
                Thread.sleep(Configuration.getStatusFrequency() * 1000);

                JsonObject status = getFogStatus();
                if (Configuration.debugging) {
                    logInfo(status.toString());
                }
                logInfo("Post ioFog status");
                connected = isControllerConnected(false);
                if (!connected)
                    continue;
                logInfo("controller connection verified");

                logInfo("sending IOFog status...");
                orchestrator.request("status", RequestType.PUT, null, status);
                onPostStatusSuccess();
            } catch (CertificateException | SSLHandshakeException e) {
                verificationFailed(e);
            } catch (ForbiddenException e) {
                deProvision(true);
            } catch (Exception e) {
                logError("Unable to send status : " + e.getMessage(), e);
            }
        }
    };

    private final Runnable postDiagnostics = () -> {
        while (true) {
            if (StraceDiagnosticManager.getInstance().getMonitoringMicroservices().size() > 0) {
                JsonBuilderFactory factory = Json.createBuilderFactory(null);
                JsonArrayBuilder arrayBuilder = factory.createArrayBuilder();

                for (MicroserviceStraceData microservice : StraceDiagnosticManager.getInstance().getMonitoringMicroservices()) {
                    arrayBuilder.add(factory.createObjectBuilder()
                        .add("microserviceUuid", microservice.getMicroserviceUuid())
                        .add("buffer", microservice.getResultBufferAsString())
                    );
                    microservice.getResultBuffer().clear();
                }

                JsonObject json = factory.createObjectBuilder()
                    .add("straceData", arrayBuilder).build();

                try {
                    orchestrator.request("strace", RequestType.PUT, null, json);
                } catch (Exception e) {
                    logError("Unable send strace logs : " + e.getMessage(), e);
                }

                try {
                    Thread.sleep(Configuration.getPostDiagnosticsFreq() * 1000);
                } catch (InterruptedException e) {
                    logWarning(e.getMessage());
                }
            }
        }
    };

    /**
     * logs and sets appropriate status when controller
     * certificate is not verified
     */
    private void verificationFailed(Exception e) {
        connected = false;
        if (!notProvisioned()) {
            StatusReporter.setFieldAgentStatus().setControllerStatus(ControllerStatus.BROKEN_CERTIFICATE);
            logError("controller certificate verification failed", e);
        }
        StatusReporter.setFieldAgentStatus().setControllerVerified(false);
    }


    /**
     * retrieves IOFog changes list from IOFog controller
     */
    private final Runnable getChangesList = () -> {
        while (true) {
            try {
                Thread.sleep(Configuration.getChangeFrequency() * 1000);

                logInfo("Get changes list");
                if (notProvisioned() || !isControllerConnected(false)) {
                    continue;
                }


                JsonObject result;
                try {
                    result = orchestrator.request("config/changes", RequestType.GET, null, null);
                } catch (CertificateException | SSLHandshakeException e) {
                    verificationFailed(e);
                    continue;
                } catch (Exception e) {
                    logError("Unable to get changes : " + e.getMessage(), e);
                    continue;
                }


                StatusReporter.setFieldAgentStatus().setLastCommandTime(lastGetChangesList);

                JsonObject changes = result;
                if (changes.getBoolean("deleteNode") && !initialization) {
                    deleteNode();
                } else {
                    if (changes.getBoolean("reboot") && !initialization) {
                        reboot();
                    }
                    if (changes.getBoolean("isImageSnapshot") && !initialization) {
                        createImageSnapshot();
                    }
                    if (changes.getBoolean("config") && !initialization) {
                        getFogConfig();
                    }
                    if (changes.getBoolean("version") && !initialization) {
                        changeVersion();
                    }
                    if (changes.getBoolean("registries") || initialization) {
                        loadRegistries(false);
                        ProcessManager.getInstance().update();
                    }
                    if (changes.getBoolean("microserviceConfig") || changes.getBoolean("microserviceList") || initialization) {
                        boolean microserviceConfig = changes.getBoolean("microserviceConfig");
                        boolean routing = changes.getBoolean("routing");

                        List<Microservice> microservices = loadMicroservices(false);

                        if (microserviceConfig) {
                            processMicroserviceConfig(microservices);
                            LocalApi.getInstance().update();
                        }
                    }
                    if (changes.getBoolean("routing") || initialization) {
                        loadRoutes(false);
                        loadConnectors(false);
                        MessageBus.getInstance().update();
                    }
                    if (changes.getBoolean("tunnel") && !initialization) {
                        sshProxyManager.update(getProxyConfig());
                    }
                    if (changes.getBoolean("diagnostics") && !initialization) {
                        updateDiagnostics();
                    }
                }

                initialization = false;
            } catch (Exception e) {
                logInfo("Error getting changes list : " + e.getMessage());
            }
        }
    };

    /**
     * Deletes current fog node from controller and makes deprovision
     */
    private void deleteNode() {
        logInfo("start deleting node");
        try {
            orchestrator.request("delete-node", RequestType.DELETE, null, null);
        } catch (Exception e) {
            logInfo("can't send delete node command");
        }
        deProvision(false);
    }

    /**
     * Remote reboot of Linux machine from IOFog controller
     */
    private void reboot() {
        LoggingService.logInfo(MODULE_NAME, "Start rebooting");
        if (SystemUtils.IS_OS_WINDOWS) {
            return; // TODO implement
        }

        CommandShellResultSet<List<String>, List<String>> result = CommandShellExecutor.executeCommand("shutdown -r now");
        if (result.getError().size() > 0) {
            LoggingService.logWarning(MODULE_NAME, result.toString());
        }
    }

    /**
     * performs change version operation, received from ioFog controller
     */
    private void changeVersion() {
        LoggingService.logInfo(MODULE_NAME, "Get change version action");
        if (notProvisioned() || !isControllerConnected(false)) {
            return;
        }

        try {
            JsonObject result = orchestrator.request("version", RequestType.GET, null, null);

            VersionHandler.changeVersion(result);

        } catch (CertificateException | SSLHandshakeException e) {
            verificationFailed(e);
        } catch (Exception e) {
            LoggingService.logError(MODULE_NAME, "Unable to get version command : " + e.getMessage(), e);
        }
    }

    private void updateDiagnostics() {
        LoggingService.logInfo(MODULE_NAME, "Getting changes for diagnostics");
        if (notProvisioned() || !isControllerConnected(false)) {
            return;
        }

        if (SystemUtils.IS_OS_WINDOWS) {
            return; // TODO implement
        }

        try {
            JsonObject result = orchestrator.request("strace", RequestType.GET, null, null);

            StraceDiagnosticManager.getInstance().updateMonitoringMicroservices(result);

        } catch (CertificateException | SSLHandshakeException e) {
            verificationFailed(e);
        } catch (Exception e) {
            LoggingService.logError(MODULE_NAME, "Unable to get diagnostics updates : " + e.getMessage(), e);
        }
    }

    /**
     * gets list of registries from file or IOFog controller
     *
     * @param fromFile - load from file
     */
    private void loadRegistries(boolean fromFile) {
        logInfo("get registries");
        if (notProvisioned() || !isControllerConnected(fromFile)) {
            return;
        }

        String filename = "registries.json";
        try {
            JsonArray registriesList;
            if (fromFile) {
                registriesList = readFile(filesPath + filename);
                if (registriesList == null) {
                    loadRegistries(false);
                    return;
                }
            } else {
                JsonObject result = orchestrator.request("registries", RequestType.GET, null, null);

                registriesList = result.getJsonArray("registries");
                saveFile(registriesList, filesPath + filename);
            }

            List<Registry> registries = new ArrayList<>();
            for (int i = 0; i < registriesList.size(); i++) {
                JsonObject registry = registriesList.getJsonObject(i);
                Registry.RegistryBuilder registryBuilder = new Registry.RegistryBuilder()
                        .setUrl(registry.getString("url"))
                        .setIsPublic(registry.getBoolean("isPublic", false));
                if (!registry.getBoolean("isPublic", false)) {
                    registryBuilder.setUserName(registry.getString("username"))
                            .setPassword(registry.getString("password"))
                            .setUserEmail(registry.getString("userEmail"));

                }
                registries.add(registryBuilder.build());
            }
            microserviceManager.setRegistries(registries);
        } catch (CertificateException | SSLHandshakeException e) {
            verificationFailed(e);
        } catch (Exception e) {
            logError("Unable to get registries : " + e.getMessage(), e);
        }
    }

    /**
     * gets list of Microservice configurations from file or IOFog controller
     */
    private void processMicroserviceConfig(List<Microservice> microservices) {
        Map<String, String> configs = new HashMap<>();
        for (Microservice microservice : microservices) {
            configs.put(microservice.getMicroserviceUuid(), microservice.getConfig());
        }

        microserviceManager.setConfigs(configs);
    }

    private Map<String, Route> parseRoutesJson(JsonArray routesJson) {
        return IntStream.range(0, routesJson.size())
               .boxed()
               .map(routesJson::getJsonObject)
               .map(routeJson -> {
                   String producerMicroserviceUuid = routeJson.getString("microserviceUuid");
                   boolean isLocalPublisher = routeJson.getBoolean("isLocal");
                   ConnectorClientConfig connectorConsumerConfig = null;
                   if (!isLocalPublisher) {
                       connectorConsumerConfig = parseConnectorClientConfigJson(routeJson);
                   }
                   Producer producer = new Producer(producerMicroserviceUuid, isLocalPublisher, connectorConsumerConfig);
                   List<Receiver> receivers = parseReceiversJson(routeJson);
                   return new Route(producer, receivers);
               })
               .collect(toMap(route -> route.getProducer().getMicroserviceId(), Function.identity()));
    }

    private ConnectorClientConfig parseConnectorClientConfigJson(JsonObject jsonObject) {
        JsonObject configJson = jsonObject.getJsonObject("config");
        int connectorId = configJson.getInt("connectorId");
        String publisherId = configJson.getString("publisherId");
        String passKey = configJson.getString("passKey");
        return new ConnectorClientConfig(connectorId, publisherId, passKey);
    }

    private Map<Integer, ConnectorConfig> parseConnectorsJson(JsonArray connectorsJson) {
        return IntStream.range(0, connectorsJson.size())
            .boxed()
            .map(connectorsJson::getJsonObject)
            .collect(toMap((JsonObject connectorJson) -> connectorJson.getInt("id"), (JsonObject connectorJson) -> {
                String name = connectorJson.getString("name");
                String host = connectorJson.getString("host");
                int port = connectorJson.getInt("port");
                String user = connectorJson.getString("user");
                String userPassword = connectorJson.getString("userPassword");
                boolean isDevModeEnabled = connectorJson.getBoolean("devMode");
                String cert = connectorJson.getString("cert");
                boolean selfSignedCerts = connectorJson.getBoolean("selfSignedCerts");
                String keystorePassword = connectorJson.getString("keystorePassword");
                return new ConnectorConfig(name, host, port, user, userPassword,
                    isDevModeEnabled, cert, selfSignedCerts, keystorePassword);
            }));
    }

    private List<Receiver> parseReceiversJson(JsonObject jsonObject) {
        JsonArray receiversJson = jsonObject.getJsonArray("receivers");
        return IntStream.range(0 , receiversJson.size())
                .boxed()
                .map(receiversJson::getJsonObject)
                .map(receiverJson -> {
                    String receiver = receiverJson.getString("microserviceUuid");
                    boolean isLocalReceiver = receiverJson.getBoolean("isLocal");
                    ConnectorClientConfig connectorProducerConfig = null;
                    if (!isLocalReceiver) {
                        connectorProducerConfig = parseConnectorClientConfigJson(receiverJson);
                    }
                    return new Receiver(receiver, isLocalReceiver, connectorProducerConfig);
                })
                .collect(toList());
    }

    private void loadRoutes(boolean fromFile) {
        logInfo("loading routes...");
        if (notProvisioned() || !isControllerConnected(fromFile)) {
            return;
        }

        String filename = "routes.json";
        JsonArray routesJson;
        try {
            if (fromFile) {
                routesJson = readFile(filesPath + filename);
                if (routesJson == null) {
                    JsonObject result = orchestrator.request("routes", RequestType.GET, null, null);
                    routesJson = result.getJsonArray("routes");
                }
            } else {
                JsonObject result = orchestrator.request("routes", RequestType.GET, null, null);
                routesJson = result.getJsonArray("routes");
                saveFile(routesJson, filesPath + filename);
            }
            Map<String, Route> routesMap = parseRoutesJson(routesJson);
            microserviceManager.setRoutes(routesMap);
        } catch (CertificateException | SSLHandshakeException e) {
            verificationFailed();
        } catch (Exception e) {
            logWarning("Unable to get routes: " + e.getMessage());
        }
    }

    private void loadConnectors(boolean fromFile) {
        logInfo("loading connectors...");
        if (notProvisioned() || !isControllerConnected(fromFile)) {
            return;
        }

        String filename = "connectors.json";
        JsonArray connectorsJson;
        try {
            if (fromFile) {
                connectorsJson = readFile(filesPath + filename);
                if (connectorsJson == null) {
                    JsonObject result = orchestrator.request("connectors", RequestType.GET, null, null);
                    connectorsJson = result.getJsonArray("connectors");
                }
            } else {
                JsonObject result = orchestrator.request("connectors", RequestType.GET, null, null);
                connectorsJson = result.getJsonArray("connectors");
                saveFile(connectorsJson, filesPath + filename);
            }
            Map<Integer, ConnectorConfig> connectorsMap = parseConnectorsJson(connectorsJson);
            connectorManager.setConnectors(connectorsMap);
        } catch (CertificateException | SSLHandshakeException e) {
            verificationFailed();
        } catch (Exception e) {
            logWarning("Unable to get routes: " + e.getMessage());
        }
    }

    /**
     * gets list of Microservices from file or IOFog controller
     *
     * @param fromFile - load from file
     */
    private List<Microservice> loadMicroservices(boolean fromFile) {
        logInfo("loading microservices...");
        if (notProvisioned() || !isControllerConnected(fromFile)) {
            return new ArrayList<>();
        }

        String filename = "microservices.json";
        JsonArray microservicesJson;
        try {
            if (fromFile) {
                microservicesJson = readFile(filesPath + filename);
                if (microservicesJson == null) {
                    return loadMicroservices(false);
                } else {
                    return IntStream.range(0, microservicesJson.size())
                            .boxed()
                            .map(microservicesJson::getJsonObject)
                            .map(containerJsonObjectToMicroserviceFunction())
                            .collect(toList());
                }
            } else {
                JsonObject result = orchestrator.request("microservices", RequestType.GET, null, null);
                microservicesJson = result.getJsonArray("microservices");
                saveFile(microservicesJson, filesPath + filename);

                List<Microservice> microservices = IntStream.range(0, microservicesJson.size())
                        .boxed()
                        .map(microservicesJson::getJsonObject)
                        .map(containerJsonObjectToMicroserviceFunction())
                        .collect(toList());
                microserviceManager.setLatestMicroservices(microservices);
                return microservices;
            }
        } catch (CertificateException | SSLHandshakeException e) {
            verificationFailed(e);
        } catch (Exception e) {
            logError("Unable to get microservices: " + e.getMessage(), e);
        }

        return new ArrayList<>();
    }

    private Function<JsonObject, Microservice> containerJsonObjectToMicroserviceFunction() {
        return jsonObj -> {
            Microservice microservice = new Microservice(jsonObj.getString("uuid"), jsonObj.getString("imageId"));
            microservice.setConfig(jsonObj.getString("config"));
            microservice.setRebuild(jsonObj.getBoolean("rebuild"));
            microservice.setRootHostAccess(jsonObj.getBoolean("rootHostAccess"));
            microservice.setRegistry(jsonObj.getString("registryUrl"));
            microservice.setLogSize(jsonObj.getJsonNumber("logSize").longValue());
            microservice.setDelete(jsonObj.getBoolean("delete"));
            microservice.setDeleteWithCleanup(jsonObj.getBoolean("deleteWithCleanup"));

            JsonValue portMappingValue = jsonObj.get("portMappings");
            if (!portMappingValue.getValueType().equals(JsonValue.ValueType.NULL)) {
                JsonArray portMappingObjs = (JsonArray) portMappingValue;
                List<PortMapping> pms = portMappingObjs.size() > 0
                        ? IntStream.range(0, portMappingObjs.size())
                        .boxed()
                        .map(portMappingObjs::getJsonObject)
                        .map(portMapping -> new PortMapping(portMapping.getInt("portExternal"),
                                portMapping.getInt("portInternal")))
                        .collect(toList())
                        : null;

                microservice.setPortMappings(pms);
            }

            JsonValue volumeMappingValue = jsonObj.get("volumeMappings");
            if (!volumeMappingValue.getValueType().equals(JsonValue.ValueType.NULL)) {
                JsonArray volumeMappingObj = (JsonArray) volumeMappingValue;
                List<VolumeMapping> vms = volumeMappingObj.size() > 0
                        ? IntStream.range(0, volumeMappingObj.size())
                        .boxed()
                        .map(volumeMappingObj::getJsonObject)
                        .map(volumeMapping -> new VolumeMapping(volumeMapping.getString("hostDestination"),
                                volumeMapping.getString("containerDestination"),
                                volumeMapping.getString("accessMode")))
                        .collect(toList())
                        : null;

                microservice.setVolumeMappings(vms);
            }

            try {
                LoggingService.setupMicroserviceLogger(microservice.getMicroserviceUuid(), microservice.getLogSize());
            } catch (IOException e) {
                logError("Error at setting up microservice logger", e);
            }
            return microservice;
        };
    }

    /**
     * pings IOFog controller
     */
    private boolean ping() {
        if (notProvisioned()) {
            return false;
        }

        try {
            if (orchestrator.ping()) {
                StatusReporter.setFieldAgentStatus().setControllerStatus(OK);
                StatusReporter.setFieldAgentStatus().setControllerVerified(true);
                return true;
            }
        } catch (CertificateException | SSLHandshakeException e) {
            verificationFailed(e);
        } catch (Exception e) {
            StatusReporter.setFieldAgentStatus().setControllerStatus(ControllerStatus.BROKEN_CERTIFICATE);
            logWarning("Error pinging for controller: " + e.getMessage());
        }
        return false;
    }

    /**
     * pings IOFog controller
     */
    private final Runnable pingController = () -> {
        while (true) {
            try {
                Thread.sleep(Configuration.getPingControllerFreqSeconds() * 1000);
                logInfo("Ping controller");
                ping();
            } catch (Exception e) {
                logWarning("Exception pinging controller : " + e.getMessage());
            }
        }
    };

    /**
     * computes SHA1 checksum
     *
     * @param data - input data
     * @return String
     */
    private String checksum(String data) {
        try {
            byte[] base64 = Base64.getEncoder().encode(data.getBytes(UTF_8));
            MessageDigest md = MessageDigest.getInstance("SHA1");
            md.update(base64);
            byte[] mdbytes = md.digest();
            StringBuilder sb = new StringBuilder("");
            for (byte mdbyte : mdbytes) {
                sb.append(Integer.toString((mdbyte & 0xff) + 0x100, 16).substring(1));
            }
            return sb.toString();
        } catch (Exception e) {
            logError("Error computing checksum : " + e.getMessage(), e);
            return "";
        }
    }

    /**
     * reads json data from file and compare data checksum
     * if checksum failed, returns null
     *
     * @param filename - file name to read data from
     * @return JsonArray
     */
    private JsonArray readFile(String filename) {
        if (!Files.exists(Paths.get(filename), NOFOLLOW_LINKS))
            return null;

        JsonObject object = readObject(filename);
        String checksum = object.getString("checksum");
        JsonArray data = object.getJsonArray("data");
        if (!checksum(data.toString()).equals(checksum))
            return null;
        long timestamp = object.getJsonNumber("timestamp").longValue();
        if (lastGetChangesList == 0)
            lastGetChangesList = timestamp;
        else
            lastGetChangesList = Long.min(timestamp, lastGetChangesList);
        return data;
    }

    private JsonObject readObject(String filename) {
        JsonObject object = null;
        try (JsonReader reader = Json.createReader(new InputStreamReader(new FileInputStream(filename), UTF_8))) {
            object = reader.readObject();
        } catch (FileNotFoundException ex) {
            LoggingService.logError(MODULE_NAME, "Invalid file: " + filename, ex);
        }
        return object;
    }

    /**
     * saves data and checksum to json file
     *
     * @param data     - data to be written into file
     * @param filename - file name
     */
    private void saveFile(JsonArray data, String filename) {
        String checksum = checksum(data.toString());
        JsonObject object = Json.createObjectBuilder()
                .add("checksum", checksum)
                .add("timestamp", lastGetChangesList)
                .add("data", data)
                .build();
        try (JsonWriter writer = Json.createWriter(new OutputStreamWriter(new FileOutputStream(filename), UTF_8))) {
            writer.writeObject(object);
        } catch (IOException e) {
            logWarning("Error saving data to file '" + filename + "': " + e.getMessage());
        }
    }

    /**
     * gets IOFog instance configuration from IOFog controller
     */
    private void getFogConfig() {
        logInfo("get fog config");
        if (notProvisioned() || !isControllerConnected(false)) {
            return;
        }

        if (initialization) {
            postFogConfig();
            return;
        }

        try {
            JsonObject configs = orchestrator.request("config", RequestType.GET, null, null);

            String networkInterface = configs.getString(NETWORK_INTERFACE.getJsonProperty());
            String dockerUrl = configs.getString(DOCKER_URL.getJsonProperty());
            double diskLimit = configs.getJsonNumber(DISK_CONSUMPTION_LIMIT.getJsonProperty()).doubleValue();
            String diskDirectory = configs.getString(DISK_DIRECTORY.getJsonProperty());
            double memoryLimit = configs.getJsonNumber(MEMORY_CONSUMPTION_LIMIT.getJsonProperty()).doubleValue();
            double cpuLimit = configs.getJsonNumber(PROCESSOR_CONSUMPTION_LIMIT.getJsonProperty()).doubleValue();
            double logLimit = configs.getJsonNumber(LOG_DISK_CONSUMPTION_LIMIT.getJsonProperty()).doubleValue();
            String logDirectory = configs.getString(LOG_DISK_DIRECTORY.getJsonProperty());
            int logFileCount = configs.getInt(LOG_FILE_COUNT.getJsonProperty());
            int statusFrequency = configs.getInt(STATUS_FREQUENCY.getJsonProperty());
            int changeFrequency = configs.getInt(CHANGE_FREQUENCY.getJsonProperty());
            int deviceScanFrequency = configs.getInt(DEVICE_SCAN_FREQUENCY.getJsonProperty());
            boolean watchdogEnabled = configs.getBoolean(WATCHDOG_ENABLED.getJsonProperty());
            double latitude = configs.getJsonNumber("latitude").doubleValue();
            double longitude = configs.getJsonNumber("longitude").doubleValue();
            String gpsCoordinates = latitude + "," + longitude;

            Map<String, Object> instanceConfig = new HashMap<>();

            if (!NETWORK_INTERFACE.getDefaultValue().equals(Configuration.getNetworkInterface()) &&
                    !Configuration.getNetworkInterface().equals(networkInterface))
                instanceConfig.put(NETWORK_INTERFACE.getCommandName(), networkInterface);

            if (!Configuration.getDockerUrl().equals(dockerUrl))
                instanceConfig.put(DOCKER_URL.getCommandName(), dockerUrl);

            if (Configuration.getDiskLimit() != diskLimit)
                instanceConfig.put(DISK_CONSUMPTION_LIMIT.getCommandName(), diskLimit);

            if (!Configuration.getDiskDirectory().equals(diskDirectory))
                instanceConfig.put(DISK_DIRECTORY.getCommandName(), diskDirectory);

            if (Configuration.getMemoryLimit() != memoryLimit)
                instanceConfig.put(MEMORY_CONSUMPTION_LIMIT.getCommandName(), memoryLimit);

            if (Configuration.getCpuLimit() != cpuLimit)
                instanceConfig.put(PROCESSOR_CONSUMPTION_LIMIT.getCommandName(), cpuLimit);

            if (Configuration.getLogDiskLimit() != logLimit)
                instanceConfig.put(LOG_DISK_CONSUMPTION_LIMIT.getCommandName(), logLimit);

            if (!Configuration.getLogDiskDirectory().equals(logDirectory))
                instanceConfig.put(LOG_DISK_DIRECTORY.getCommandName(), logDirectory);

            if (Configuration.getLogFileCount() != logFileCount)
                instanceConfig.put(LOG_FILE_COUNT.getCommandName(), logFileCount);

            if (Configuration.getStatusFrequency() != statusFrequency)
                instanceConfig.put(STATUS_FREQUENCY.getCommandName(), statusFrequency);

            if (Configuration.getChangeFrequency() != changeFrequency)
                instanceConfig.put(CHANGE_FREQUENCY.getCommandName(), changeFrequency);

            if (Configuration.getDeviceScanFrequency() != deviceScanFrequency)
                instanceConfig.put(DEVICE_SCAN_FREQUENCY.getCommandName(), deviceScanFrequency);

            if (Configuration.isWatchdogEnabled() != watchdogEnabled)
                instanceConfig.put(WATCHDOG_ENABLED.getCommandName(), watchdogEnabled ? "on" : "off");

            if (!Configuration.getGpsCoordinates().equals(gpsCoordinates)) {
                instanceConfig.put(GPS_COORDINATES.getCommandName(), gpsCoordinates);
            }

            if (!instanceConfig.isEmpty())
                Configuration.setConfig(instanceConfig, false);

        } catch (CertificateException | SSLHandshakeException e) {
            verificationFailed(e);
        } catch (Exception e) {
            logError("Unable to get ioFog config : " + e.getMessage(), e);
        }
    }

    /**
     * sends IOFog instance configuration to IOFog controller
     */
    private void postFogConfig() {
        logInfo("Post fog config");
        if (notProvisioned() || !isControllerConnected(false)) {
            return;
        }

        logInfo("posting fog config");
        double latitude, longitude;
        try {
            String gpsCoordinates = Configuration.getGpsCoordinates();

            String[] coords = gpsCoordinates.split(",");

            latitude = Double.parseDouble(coords[0]);
            longitude = Double.parseDouble(coords[1]);
        } catch (Exception e) {
            latitude = 0;
            longitude = 0;
            logError("Error while parsing GPS coordinates", e);
        }

        JsonObject json = Json.createObjectBuilder()
                .add(NETWORK_INTERFACE.getJsonProperty(), IOFogNetworkInterface.getNetworkInterface())
                .add(DOCKER_URL.getJsonProperty(), Configuration.getDockerUrl())
                .add(DISK_CONSUMPTION_LIMIT.getJsonProperty(), Configuration.getDiskLimit())
                .add(DISK_DIRECTORY.getJsonProperty(), Configuration.getDiskDirectory())
                .add(MEMORY_CONSUMPTION_LIMIT.getJsonProperty(), Configuration.getMemoryLimit())
                .add(PROCESSOR_CONSUMPTION_LIMIT.getJsonProperty(), Configuration.getCpuLimit())
                .add(LOG_DISK_CONSUMPTION_LIMIT.getJsonProperty(), Configuration.getLogDiskLimit())
                .add(LOG_DISK_DIRECTORY.getJsonProperty(), Configuration.getLogDiskDirectory())
                .add(LOG_FILE_COUNT.getJsonProperty(), Configuration.getLogFileCount())
                .add(STATUS_FREQUENCY.getJsonProperty(), Configuration.getStatusFrequency())
                .add(CHANGE_FREQUENCY.getJsonProperty(), Configuration.getChangeFrequency())
                .add(DEVICE_SCAN_FREQUENCY.getJsonProperty(), Configuration.getDeviceScanFrequency())
                .add(WATCHDOG_ENABLED.getJsonProperty(), Configuration.isWatchdogEnabled())
                .add(GPS_MODE.getJsonProperty(), Configuration.getGpsMode().name().toLowerCase())
                .add("latitude", latitude)
                .add("longitude", longitude)
                .build();

        try {
            orchestrator.request("config", RequestType.PATCH, null, json);
        } catch (CertificateException | SSLHandshakeException e) {
            verificationFailed(e);
        } catch (Exception e) {
            logError("Unable to post ioFog config : " + e.getMessage(), e);
        }
    }

    /**
     * gets IOFog proxy configuration from IOFog controller
     */
    private JsonObject getProxyConfig() {
        LoggingService.logInfo(MODULE_NAME, "get proxy config");
        JsonObject result = null;

        if (!notProvisioned() && isControllerConnected(false)) {
            try {
                JsonObject response = orchestrator.request("tunnel", RequestType.GET, null, null);
                result = response.getJsonObject("proxy");
            } catch (Exception e) {
                LoggingService.logError(MODULE_NAME, "Unable to get proxy config : " + e.getMessage(), e);
            }
        }

        return result;
    }

    /**
     * does the provisioning.
     * If successfully provisioned, updates Iofog UUID and Access Token in
     * configuration file and loads Microservice data, otherwise sets appropriate
     * status.
     *
     * @param key - provisioning key sent by command-line
     * @return String
     */
    public JsonObject provision(String key) {
        logInfo("Provisioning");
        JsonObject provisioningResult;

        try {
            provisioningResult = orchestrator.provision(key);

            StatusReporter.setFieldAgentStatus().setControllerStatus(OK);
            Configuration.setIofogUuid(provisioningResult.getString("uuid"));
            Configuration.setAccessToken(provisioningResult.getString("token"));

            Configuration.saveConfigUpdates();

            postFogConfig();
            loadRegistries(false);
            List<Microservice> microservices = loadMicroservices(false);
            processMicroserviceConfig(microservices);
            loadRoutes(false);
            loadConnectors(false);
            notifyModules();

            sendHWInfoFromHalToController();

            logInfo("Provisioning success");

        } catch (CertificateException | SSLHandshakeException e) {
            verificationFailed(e);
            provisioningResult = buildProvisionFailResponse("Certificate error", e);
        } catch (UnknownHostException e) {
            StatusReporter.setFieldAgentStatus().setControllerVerified(false);
            provisioningResult = buildProvisionFailResponse("Connection error: unable to connect to fog controller.", e);
        } catch (Exception e) {
            provisioningResult = buildProvisionFailResponse(e.getMessage(), e);
        }
        return provisioningResult;
    }

    private JsonObject buildProvisionFailResponse(String message, Exception e) {
        logWarning("Provisioning failed - " + e.getMessage());
        return Json.createObjectBuilder()
                .add("status", "failed")
                .add("errorMessage", message)
                .build();
    }

    /**
     * notifies other modules
     */
    private void notifyModules() {
        MessageBus.getInstance().update();
        LocalApi.getInstance().update();
        ProcessManager.getInstance().update();
    }

    /**
     * does de-provisioning
     *
     * @return String
     */
    public String deProvision(boolean isTokenExpired) {
        logInfo("Deprovisioning");

        if (notProvisioned()) {
            return "\nFailure - not provisioned";
        }

        if (!isTokenExpired) {
            try {
                orchestrator.request("deprovision", RequestType.POST, null, getDeprovisionBody());
            } catch (CertificateException | SSLHandshakeException e) {
                verificationFailed(e);
            } catch (Exception e) {
                logError("Unable to make deprovision request : " + e.getMessage(), e);
            }
        }

        StatusReporter.setFieldAgentStatus().setControllerStatus(NOT_PROVISIONED);
        try {
            Configuration.setIofogUuid("");
            Configuration.setAccessToken("");
            Configuration.saveConfigUpdates();
        } catch (Exception e) {
            logInfo("Error saving config updates : " + e.getMessage());
        }
        microserviceManager.clear();
        notifyModules();
        return "\nSuccess - tokens, identifiers and keys removed";
    }

    private JsonObject getDeprovisionBody() {
        JsonArrayBuilder arrayBuilder = Json.createArrayBuilder();

        Set<String> microserviceUuids = Stream.concat(
            microserviceManager.getLatestMicroservices().stream(),
            microserviceManager.getCurrentMicroservices().stream()
        )
            .map(Microservice::getMicroserviceUuid)
            .collect(Collectors.toSet());

        microserviceUuids.forEach(arrayBuilder::add);

        return Json.createObjectBuilder()
            .add("microserviceUuids", arrayBuilder)
            .build();
    }

    /**
     * sends IOFog configuration when any changes applied
     */
    public void instanceConfigUpdated() {
        try {
            postFogConfig();
        } catch (Exception e) {
            logError("Error posting updated for config : " + e.getMessage(), e);
        }
        orchestrator.update();
    }

    /**
     * starts Field Agent module
     */
    public void start() {
        if (isNullOrEmpty(Configuration.getIofogUuid()) || isNullOrEmpty(Configuration.getAccessToken()))
            StatusReporter.setFieldAgentStatus().setControllerStatus(NOT_PROVISIONED);

        microserviceManager = MicroserviceManager.getInstance();
        connectorManager = ConnectorManager.INSTANCE;
        orchestrator = new Orchestrator();
        sshProxyManager = new SshProxyManager(new SshConnection());

        boolean isConnected = ping();
        getFogConfig();
        if (!notProvisioned()) {
            loadRegistries(!isConnected);
            List<Microservice> microservices = loadMicroservices(!isConnected);
            processMicroserviceConfig(microservices);
            loadRoutes(!isConnected);
            loadConnectors(!isConnected);
        }

        new Thread(pingController, "FieldAgent : Ping").start();
        new Thread(getChangesList, "FieldAgent : GetChangesList").start();
        new Thread(postStatus, "FieldAgent : PostStatus").start();
        new Thread(postDiagnostics, "FieldAgent : PostDiagnostics").start();
    }

    /**
     * checks if IOFog controller connection is broken
     *
     * @param fromFile
     * @return boolean
     */
    private boolean isControllerConnected(boolean fromFile) {
        boolean isConnected = false;
        if ((!StatusReporter.getFieldAgentStatus().getControllerStatus().equals(OK) && !ping()) && !fromFile) {
            handleBadControllerStatus();
        } else {
            isConnected = true;
        }
        return isConnected;
    }

    private void handleBadControllerStatus() {
        String errMsg = "Connection to controller has broken";
        if (StatusReporter.getFieldAgentStatus().isControllerVerified()) {
            logWarning(errMsg);
        } else {
            verificationFailed(new Exception(errMsg));
        }
    }

    public void sendUSBInfoFromHalToController() {
        if (notProvisioned()) {
            return;
        }
        Optional<StringBuilder> response = getResponse(USB_INFO_URL);
        if (isResponseValid(response)) {
            String usbInfo = response.get().toString();
            StatusReporter.setResourceManagerStatus().setUsbConnectionsInfo(usbInfo);

            JsonObject json = Json.createObjectBuilder()
                    .add("info", usbInfo)
                    .build();
            try {
                orchestrator.request(COMMAND_USB_INFO, RequestType.PUT, null, json);
            } catch (Exception e) {
                LoggingService.logError(MODULE_NAME, e.getMessage(), e);
            }
        }
    }

    public void sendHWInfoFromHalToController() {
        if (notProvisioned()) {
            return;
        }
        Optional<StringBuilder> response = getResponse(HW_INFO_URL);
        if (isResponseValid(response)) {
            String hwInfo = response.get().toString();
            StatusReporter.setResourceManagerStatus().setHwInfo(hwInfo);

            JsonObject json = Json.createObjectBuilder()
                    .add("info", hwInfo)
                    .build();

            JsonObject jsonSendHWInfoResult = null;
            try {
                jsonSendHWInfoResult = orchestrator.request(COMMAND_HW_INFO, RequestType.PUT, null, json);
            } catch (Exception e) {
                LoggingService.logError(MODULE_NAME, e.getMessage(), e);
            }

            if (jsonSendHWInfoResult == null) {
                LoggingService.logInfo(MODULE_NAME, "Can't get HW Info from HAL.");
            }
        }
    }

    private boolean isResponseValid(Optional<StringBuilder> response) {
        return response.isPresent() && !response.get().toString().isEmpty();
    }

    private Optional<StringBuilder> getResponse(String spec) {
        Optional<HttpURLConnection> connection = sendHttpGetReq(spec);
        StringBuilder content = null;
        if (connection.isPresent()) {
            content = new StringBuilder();
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(connection.get().getInputStream(), UTF_8))) {
                String inputLine;
                content = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine);
                }
            } catch (IOException exc) {
                // HAL is not enabled for this Iofog Agent at the moment
            }
            connection.get().disconnect();
        }
        return Optional.ofNullable(content);
    }

    private Optional<HttpURLConnection> sendHttpGetReq(String spec) {
        HttpURLConnection connection;
        try {
            URL url = new URL(spec);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(HttpMethod.GET);
            connection.getResponseCode();
        } catch (IOException exc) {
            connection = null;
            // HAL is not enabled for this Iofog Agent at the moment
        }
        return Optional.ofNullable(connection);
    }

    private void createImageSnapshot() {
        if (notProvisioned() || !isControllerConnected(false)) {
            return;
        }

        LoggingService.logInfo(MODULE_NAME, "Create image snapshot");

        String microserviceUuid = null;

        try {
            JsonObject jsonObject = orchestrator.request("image-snapshot", RequestType.GET, null, null);
            microserviceUuid = jsonObject.getString("uuid");
        } catch (Exception e) {
            logError("Unable get name of image snapshot : " + e.getMessage(), e);
        }

        if (SystemUtils.IS_OS_WINDOWS) {
            return; // TODO implement
        }

        if (microserviceUuid != null) {
            ImageDownloadManager.createImageSnapshot(orchestrator, microserviceUuid);
        }
    }

}