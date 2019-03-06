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
 *  Ashita Nagar
 *******************************************************************************/
package org.eclipse.iofog.message_bus;

import org.eclipse.iofog.IOFogModule;
import org.eclipse.iofog.microservice.Microservice;
import org.eclipse.iofog.microservice.MicroserviceManager;
import org.eclipse.iofog.microservice.Receiver;
import org.eclipse.iofog.microservice.Route;
import org.eclipse.iofog.status_reporter.StatusReporter;
import org.eclipse.iofog.utils.configuration.Configuration;
import org.eclipse.iofog.utils.functional.Pair;
import org.eclipse.iofog.utils.logging.LoggingService;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.stream.Collectors.*;
import static org.eclipse.iofog.utils.Constants.MESSAGE_BUS;
import static org.eclipse.iofog.utils.Constants.ModulesStatus.STOPPED;

/**
 * Message Bus module
 *
 * @author saeid
 */
public class MessageBus implements IOFogModule {

    final static String MODULE_NAME = "Message Bus";

    private MessageBusServer messageBusServer;
    private Map<String, Route> routes;
    private Map<String, MessagePublisher> publishers;
    private Map<String, LocalMessageReceiver> localReceivers;
    private Map<Pair<String, String>, RemoteMessageReceiver> remoteReceivers;
    private MessageIdGenerator idGenerator;
    private static MessageBus instance;
    private MicroserviceManager microserviceManager;
    private final Object updateLock = new Object();

    private long lastSpeedTime, lastSpeedMessageCount;

    private MessageBus() {
    }

    @Override
    public int getModuleIndex() {
        return MESSAGE_BUS;
    }

    @Override
    public String getModuleName() {
        return MODULE_NAME;
    }

    public static MessageBus getInstance() {
        if (instance == null) {
            synchronized (MessageBus.class) {
                if (instance == null) {
                    instance = new MessageBus();
                }
            }
        }
        return instance;
    }


    /**
     * enables real-time {@link Message} receiving of an {@link Microservice}
     *
     * @param receiver - ID of {@link Microservice}
     */
    public synchronized void enableRealTimeReceiving(String receiver) {
        LocalMessageReceiver rec = localReceivers.get(receiver);
        if (rec != null) {
            rec.enableRealTimeReceiving();
        }
    }

    /**
     * disables real-time {@link Message} receiving of an {@link Microservice}
     *
     * @param receiver - ID of {@link Microservice}
     */
    public synchronized void disableRealTimeReceiving(String receiver) {
        LocalMessageReceiver rec = localReceivers.get(receiver);
        if (rec != null) {
            rec.disableRealTimeReceiving();
        }
    }

    private Map<String, Route> getFilteredRoutes(Map<String, Route> routes) {
        Map<String, Route> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, Route> entry : routes.entrySet()) {
            Route route = entry.getValue();
            List<Receiver> receivers = route.getReceivers();
            Map<Integer, List<Receiver>> receiverMap = receivers.stream()
                .filter(receiver -> !receiver.isLocal())
                .collect(groupingBy(receiver -> receiver.getConnectorProducerConfig().getConnectorId()));

            List<Receiver> remoteReceivers = receiverMap.values().stream()
                .map(Collections::min)
                .collect(toList());

            List<Receiver> filteredReceivers = Stream.concat(
                receivers.stream().filter(Receiver::isLocal),
                remoteReceivers.stream()
            ).collect(toList());

            Route filteredRoute = new Route(route.getProducer(), filteredReceivers);

            result.put(entry.getKey(), filteredRoute);
        }
        return result;
    }

    /**
     * initialize list of {@link Message} publishers and receivers
     */
    private void init() {
        lastSpeedMessageCount = 0;
        lastSpeedTime = System.currentTimeMillis();

        routes = getFilteredRoutes(microserviceManager.getRoutes());
        idGenerator = new MessageIdGenerator();
        publishers = new ConcurrentHashMap<>();
        localReceivers = new ConcurrentHashMap<>();
        remoteReceivers = new ConcurrentHashMap<>();

        if (routes == null)
            return;

        routes.entrySet().stream()
            .filter(route -> route.getValue() != null)
            .filter(route -> route.getValue().getReceivers() != null)
            .forEach(entry -> {
                String publisher = entry.getKey();
                Route route = entry.getValue();
                initPublishers(publisher, route);
                initReceivers(publisher, route);
            });

    }

    private void initPublishers(String publisher, Route route) {
        try {
            messageBusServer.createProducer(publisher);
        } catch (Exception e) {
            LoggingService.logWarning(MODULE_NAME + "(" + publisher + ")",
                "unable to start publisher module --> " + e.getMessage());
        }
        publishers.put(publisher, new MessagePublisher(route, messageBusServer.getProducer(publisher)
        ));
    }

    private void initReceivers(String publisher, Route route) {
        initLocalReceivers(route);
        initRemoteReceivers(publisher, route);
    }

    private void initLocalReceivers(Route route) {
        localReceivers.putAll(route.getReceivers().stream()
            .filter(Receiver::isLocal)
            .filter(receiver -> !localReceivers.containsKey(receiver.getMicroserviceUuid()))
            .collect(toMap(Receiver::getMicroserviceUuid, receiver -> {
                try {
                    messageBusServer.createConsumer(receiver.getMicroserviceUuid());
                } catch (Exception e) {
                    LoggingService.logError(MODULE_NAME + "(" + receiver.getMicroserviceUuid() + ")",
                        "unable to start receiver module --> " + e.getMessage(), e);
                }
                return new LocalMessageReceiver(receiver, messageBusServer.getConsumer(receiver.getMicroserviceUuid()));
            })));
    }

    private void initRemoteReceivers(String publisher, Route route) {
        remoteReceivers.putAll(route.getReceivers().stream()
            .filter(receiver -> !receiver.isLocal())
            .filter(receiver -> !localReceivers.containsKey(receiver.getMicroserviceUuid()))
            .collect(toMap(receiver -> Pair.of(publisher, receiver.getMicroserviceUuid()), receiver -> {
                try {
                    messageBusServer.createConsumer(receiver.getMicroserviceUuid());
                } catch (Exception e) {
                    LoggingService.logError(MODULE_NAME + "(" + receiver.getMicroserviceUuid() + ")",
                        "unable to start receiver module --> " + e.getMessage(), e);
                }
                return new RemoteMessageReceiver(receiver, messageBusServer.getConsumer(receiver.getMicroserviceUuid()));
            })));
    }

    /**
     * calculates the average speed of {@link Message} moving through ioFog
     */
    private final Runnable calculateSpeed = () -> {
        while (true) {
            try {
                Thread.sleep(Configuration.getSpeedCalculationFreqMinutes() * 60 * 1000);

                logInfo("calculating message processing speed");

                long now = System.currentTimeMillis();
                long msgs = StatusReporter.getMessageBusStatus().getProcessedMessages();

                float speed = ((float) (msgs - lastSpeedMessageCount)) / ((now - lastSpeedTime) / 1000f);
                StatusReporter.setMessageBusStatus().setAverageSpeed(speed);
                lastSpeedMessageCount = msgs;
                lastSpeedTime = now;
            } catch (Exception exp) {
                logError(exp.getMessage(), exp);
            }
        }
    };

    /**
     * monitors ActiveMQ server
     */
    private final Runnable checkMessageServerStatus = () -> {
        while (true) {
            try {
                Thread.sleep(5000);

                logInfo("Check message bus server status");
                checkMessageBusServer();
                checkPublishers();
                checkReceivers();
            } catch (Exception exp) {
                logWarning(exp.getMessage());
            }
        }
    };

    private void checkMessageBusServer() {
        if (!messageBusServer.isServerActive()) {
            logWarning("Server is not active. restarting...");
            stop();
            try {
                messageBusServer.startServer();
                logInfo("Server restarted");
                init();
            } catch (Exception e) {
                logError("Server restart failed --> " + e.getMessage(), e);
            }
        }
    }

    private void checkPublishers() {
        publishers.forEach((publisherName, messagePublisher) -> {
            if (messageBusServer.isProducerClosed(publisherName)) {
                logWarning("Producer module for " + publisherName + " stopped. restarting...");
                messagePublisher.close();
                Route route = routes.get(publisherName);
                if (route == null || route.getReceivers() == null || route.getReceivers().size() == 0) {
                    publishers.remove(publisherName);
                } else {
                    try {
                        messageBusServer.createProducer(publisherName);
                        publishers.put(publisherName, new MessagePublisher(
                            route,
                            messageBusServer.getProducer(publisherName)));
                        logInfo("Producer module restarted");
                    } catch (Exception e) {
                        logWarning("Unable to restart producer module for " + publisherName + " --> " + e.getMessage());
                    }
                }
            } else if (!messagePublisher.getRoute().getProducer().isLocal()
                && (messagePublisher.getConnectorConsumer() == null
                || messagePublisher.getConnectorConsumer().isClosed())) {
                messagePublisher.enableConnectorConsuming();
            }
        });
    }

    private void checkReceivers() {
       checkLocalReceivers();
       checkRemoteReceivers();
    }

    private void checkLocalReceivers() {
        localReceivers.forEach((receiverName, messageReceiver) -> {
            if (messageBusServer.isConsumerClosed(receiverName)) {
                logWarning("Consumer module for " + receiverName + " stopped. restarting...");
                messageReceiver.close();
                try {
                    messageBusServer.createConsumer(receiverName);
                    LocalMessageReceiver newMessageReceiver = new LocalMessageReceiver(
                        messageReceiver.getReceiver(),
                        messageBusServer.getConsumer(receiverName)
                    );
                    localReceivers.put(receiverName, newMessageReceiver);
                    logInfo("Consumer module restarted");
                } catch (Exception e) {
                    logWarning("Unable to restart consumer module for " + receiverName + " --> " + e.getMessage());
                }
            }
        });
    }

    private void checkRemoteReceivers() {
        remoteReceivers.forEach((pair, messageReceiver) -> {
            if (messageBusServer.isConsumerClosed(pair._2())) {
                logWarning("Consumer module for " + pair._2() + " stopped. restarting...");
                messageReceiver.close();
                try {
                    messageBusServer.createConsumer(pair._2());
                    RemoteMessageReceiver newMessageReceiver = new RemoteMessageReceiver(
                        messageReceiver.getReceiver(),
                        messageBusServer.getConsumer(pair._2())
                    );
                    remoteReceivers.put(pair, newMessageReceiver);
                } catch (Exception e) {
                    logWarning("Unable to restart consumer module for " + pair._2() + " --> " + e.getMessage());
                }
            } else if (messageReceiver.getConnectorProducer() == null
                || messageReceiver.getConnectorProducer().isClosed()) {
                messageReceiver.enableConnectorProducing();
            }
        });
    }

    /**
     * updates routing, list of publishers and receivers
     * Field Agent calls this method when any changes applied
     */
    public void update() {
        synchronized (updateLock) {
            Map<String, Route> newRoutes = getFilteredRoutes(microserviceManager.getRoutes());
            updatePublishers(newRoutes);
            updateReceivers(newRoutes);

            routes = newRoutes;

            List<Microservice> latestMicroservices = microserviceManager.getLatestMicroservices();
            Map<String, Long> publishedMessagesPerMicroservice = StatusReporter.getMessageBusStatus().getPublishedMessagesPerMicroservice();
            publishedMessagesPerMicroservice.keySet().removeIf(key -> !microserviceManager.microserviceExists(latestMicroservices, key));
            latestMicroservices.forEach(e -> {
                if (!publishedMessagesPerMicroservice.keySet().contains(e.getMicroserviceUuid())) {
                    publishedMessagesPerMicroservice.put(e.getMicroserviceUuid(), 0L);
                }
            });
        }
    }

    private void updateReceivers(Map<String, Route> newRoutes) {
        updateLocalReceivers(newRoutes);
        updateRemoteReceivers(newRoutes);
    }

    private void updateRemoteReceivers(Map<String, Route> newRoutes) {
        Map<Pair<String, String>, Receiver> remoteReceiverMap = new HashMap<>();
        newRoutes.forEach((publisher, route) -> {
            Map<Pair<String, String>, Receiver> publisherReceivers = route.getReceivers().stream()
                .filter(receiver -> !receiver.isLocal())
                .collect(toMap(receiver -> Pair.of(publisher, receiver.getMicroserviceUuid()), Function.identity()));
            remoteReceiverMap.putAll(publisherReceivers);
        });
        remoteReceivers.forEach((pair, messageReceiver) -> {
            if (!remoteReceiverMap.containsKey(pair)) {
                messageReceiver.close();
                messageBusServer.removeConsumer(pair._2());
            } else {
                messageReceiver.update(remoteReceiverMap.get(pair));
            }
        });
        remoteReceivers.entrySet().removeIf(entry -> !remoteReceiverMap.containsKey(entry.getKey()));
        remoteReceivers.putAll(
            remoteReceiverMap.entrySet().stream()
                .filter(entry -> !remoteReceivers.containsKey(entry.getKey()))
                .collect(toMap(Map.Entry::getKey,
                    entry -> new RemoteMessageReceiver(entry.getValue(), messageBusServer.getConsumer(entry.getValue().getMicroserviceUuid()))
                )));
    }

    private void updateLocalReceivers(Map<String, Route> newRoutes) {
        Map<String, Receiver> localReceiverMap = newRoutes.values().stream()
            .flatMap(route -> route.getReceivers().stream())
            .filter(Receiver::isLocal)
            .distinct()
            .collect(toMap(Receiver::getMicroserviceUuid, Function.identity()));
        localReceivers.forEach((receiverName, messageReceiver) -> {
            if (!localReceiverMap.containsKey(receiverName)) {
                messageReceiver.close();
                messageBusServer.removeConsumer(receiverName);
            }
        });
        localReceivers.entrySet().removeIf(entry -> !localReceiverMap.containsKey(entry.getKey()));
        localReceivers.putAll(
            localReceiverMap.values().stream()
                .filter(newReceiver -> !localReceivers.containsKey(newReceiver.getMicroserviceUuid()))
                .collect(toMap(Receiver::getMicroserviceUuid,
                    newReceiver -> new LocalMessageReceiver(newReceiver, messageBusServer.getConsumer(newReceiver.getMicroserviceUuid()))
                )));
    }

    private void updatePublishers(Map<String, Route> newRoutes) {
        publishers.forEach((publisherName, messagePublisher) -> {
            if (!newRoutes.containsKey(publisherName)) {
                messagePublisher.close();
                messageBusServer.removeProducer(publisherName);
            } else {
                messagePublisher.updateRoute(newRoutes.get(publisherName));
            }
        });
        publishers.entrySet().removeIf(entry -> !newRoutes.containsKey(entry.getKey()));
        publishers.putAll(
            newRoutes.values().stream()
                .filter(route -> !publishers.containsKey(route.getProducer().getMicroserviceId()))
                .collect(toMap(publisher -> publisher.getProducer().getMicroserviceId(),
                    route -> new MessagePublisher(
                        route,
                        messageBusServer.getProducer(route.getProducer().getMicroserviceId())
                    ))));
    }

    /**
     * sets  memory usage limit of ActiveMQ
     * {@link Configuration} calls this method when any changes applied
     */
    public void instanceConfigUpdated() {
        messageBusServer.setMemoryLimit();
    }

    /**
     * starts Message Bus module
     */
    public void start() {
        microserviceManager = MicroserviceManager.getInstance();

        messageBusServer = new MessageBusServer();
        try {
            logInfo("STARTING MESSAGE BUS SERVER");
            messageBusServer.startServer();
            messageBusServer.initialize();
        } catch (Exception e) {
            try {
                messageBusServer.stopServer();
            } catch (Exception exp) {
                logWarning(exp.getMessage());
            }
            logWarning("Unable to start message bus server --> " + e.getMessage());
            StatusReporter.setSupervisorStatus().setModuleStatus(MESSAGE_BUS, STOPPED);
        }

        logInfo("MESSAGE BUS SERVER STARTED");
        init();

        new Thread(calculateSpeed, "MessageBus : CalculateSpeed").start();
        new Thread(checkMessageServerStatus, "MessageBus : CheckMessageBusServerStatus").start();
    }

    /**
     * closes receivers and publishers and stops ActiveMQ server
     */
    public void stop() {
        for (MessageReceiver receiver : localReceivers.values()) {
            receiver.close();
        }
        for (MessageReceiver receiver : remoteReceivers.values()) {
            receiver.close();
        }

        for (MessagePublisher publisher : publishers.values()) {
            publisher.close();
        }
        try {
            messageBusServer.stopServer();
        } catch (Exception exp) {
            logError(exp.getMessage(), exp);
        }
    }

    /**
     * returns {@link MessagePublisher}
     *
     * @param publisher - ID of {@link Microservice}
     * @return
     */
    public MessagePublisher getPublisher(String publisher) {
        return publishers.get(publisher);
    }

    /**
     * returns {@link MessageReceiver}
     *
     * @param receiver - ID of {@link Microservice}
     * @return
     */
    public LocalMessageReceiver getReceiver(String receiver) {
        return localReceivers.get(receiver);
    }

    /**
     * returns next generated message id
     *
     * @return
     */
    public synchronized String getNextId() {
        return idGenerator.getNextId();
    }

    /**
     * returns routes
     *
     * @return
     */
    public synchronized Map<String, Route> getRoutes() {
        return microserviceManager.getRoutes();
    }
}
