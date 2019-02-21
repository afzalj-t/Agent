package org.eclipse.iofog.connector_client;

import org.eclipse.iofog.message_bus.Message;
import org.eclipse.iofog.message_bus.MessageBusUtil;

import static org.eclipse.iofog.utils.logging.LoggingService.logInfo;

class ConnectorMessageCallback {
    void sendConnectorMessage(Message message) {
        MessageBusUtil messageBus = new MessageBusUtil();
        messageBus.publishMessage(message);
        logInfo("Connector Callback", "Received message from connector");
    }
}