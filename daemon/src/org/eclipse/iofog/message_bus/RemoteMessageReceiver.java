/*
 * *******************************************************************************
 *  * Copyright (c) 2019 Edgeworx, Inc.
 *  *
 *  * This program and the accompanying materials are made available under the
 *  * terms of the Eclipse Public License v. 2.0 which is available at
 *  * http://www.eclipse.org/legal/epl-2.0
 *  *
 *  * SPDX-License-Identifier: EPL-2.0
 *  *******************************************************************************
 *
 */
package org.eclipse.iofog.message_bus;

import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.MessageHandler;
import org.eclipse.iofog.connector_client.ConnectorManager;
import org.eclipse.iofog.connector_client.ConnectorProducer;
import org.eclipse.iofog.local_api.RemoteMessageCallback;
import org.eclipse.iofog.microservice.Receiver;

import static org.eclipse.iofog.utils.logging.LoggingService.logError;

/**
 * Remote Message Receiver
 * @author epankou
 */
public class RemoteMessageReceiver extends MessageReceiver {

    private static final String MODULE_NAME = "Remote Message Receiver";

    private ConnectorProducer connectorProducer;

    public RemoteMessageReceiver(Receiver receiver, ClientConsumer consumer) {
        super(receiver, consumer);
        setConnectorHandler();
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    /**
     * checks if any message handler is set for message bus consumer
     * @return true if handler is set, false otherwise
     */
    public synchronized boolean isConsumerListenerEnabled() {
        boolean result = false;
        if (consumer != null && !consumer.isClosed()) {
            try {
                MessageHandler messageHandler = consumer.getMessageHandler();
                result = messageHandler != null;
            } catch (ActiveMQException e) {
                logError(MODULE_NAME, "Unable to get message bus handler: " + e.getMessage(), e);
            }
        }
        return result;
    }

    synchronized ConnectorProducer getConnectorProducer() {
        return connectorProducer;
    }

    /**
     * sets message handler to send messages to IoFog Connector
     */
    synchronized void setConnectorHandler() {
        if (consumer != null && !consumer.isClosed()) {

            if (connectorProducer == null || connectorProducer.isClosed()) {
                setMessageHandler(null);
            }

            ConnectorProducer connectorProducer = ConnectorManager.INSTANCE.getProducer(receiver.getMicroserviceUuid(), receiver.getConnectorProducerConfig());
            if (connectorProducer != null && !connectorProducer.isClosed()) {
                this.connectorProducer = connectorProducer;
                listener = new MessageListener(new RemoteMessageCallback(
                    receiver.getConnectorProducerConfig().getPublisherId(),
                    connectorProducer)
                );
                setMessageHandler(listener);
            }
        }
    }

    private void setMessageHandler(MessageHandler handler) {
        try {
            consumer.setMessageHandler(handler);
        } catch (ActiveMQException e) {
            logError(MODULE_NAME, "Unable to set message bus handler: " + e.getMessage(), e);
        }
    }

    private void unsetConnectorHandler() {
        if (connectorProducer != null) {
            ConnectorManager.INSTANCE.removeProducer(connectorProducer.getName());
        }
        connectorProducer = null;
        setMessageHandler(null);
    }

    /**
     * updates remote message receiver with new one
     * @param receiver
     */
    @Override
    public synchronized void update(Receiver receiver) {
        if (!this.receiver.getConnectorProducerConfig().equals(receiver.getConnectorProducerConfig())) {
            unsetConnectorHandler();
            this.receiver = receiver;
            setConnectorHandler();
        }
    }

    @Override
    public synchronized void close() {
        if (consumer == null)
            return;
        unsetConnectorHandler();
        try {
            consumer.close();
        } catch (Exception exp) {
            logError(MODULE_NAME, exp.getMessage(), exp);
        }
    }
}
