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

import org.eclipse.iofog.connector_client.ConnectorClient;
import org.eclipse.iofog.local_api.MessageCallback;
import org.eclipse.iofog.local_api.RemoteMessageCallback;
import org.eclipse.iofog.microservice.Microservice;
import org.eclipse.iofog.microservice.RouteConfig;
import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientMessage;

import java.util.ArrayList;
import java.util.List;

import static org.eclipse.iofog.utils.logging.LoggingService.logWarning;

/**
 * receiver {@link Microservice}
 * 
 * @author saeid
 *
 */
public class MessageReceiver implements AutoCloseable{
	private static final String MODULE_NAME = "MessageReceiver";

	private final String name;
	private boolean isLocal;
	private RouteConfig routeConfig;
	private MessageListener listener;
	private final ClientConsumer consumer;
	private ConnectorClient connectorClient;

	public MessageReceiver(String name, boolean isLocal, RouteConfig routeConfig, ClientConsumer consumer) {
		this.name = name;
		this.isLocal = isLocal;
		this.consumer = consumer;
		this.routeConfig = routeConfig;
		if (!isLocal) {
			this.connectorClient = new ConnectorClient(routeConfig);
		}
	}

	public boolean isLocal() {
		return isLocal;
	}

	public RouteConfig getRouteConfig() {
		return routeConfig;
	}

	/**
	 * receivers list of {@link Message} sent to this {@link Microservice}
	 * 
	 * @return list of {@link Message}
	 * @throws Exception
	 */
	synchronized List<Message> getMessages() throws Exception {
		List<Message> result = new ArrayList<>();
		
		if (consumer != null || listener == null) {
			Message message = getMessage();
			while (message != null) {
				result.add(message);
				message = getMessage();
			}
		}
		return result;
	}

	/**
	 * receives only one {@link Message}
	 * 
	 * @return {@link Message}
	 * @throws Exception
	 */
	private Message getMessage() throws Exception {
		if (consumer == null || listener != null)
			return null;

		Message result = null; 
		ClientMessage msg = consumer.receiveImmediate();
		if (msg != null) {
			msg.acknowledge();
			result = new Message(msg.getBytesProperty("message"));
		}
		return result;
	}

	protected String getName() {
		return name;
	}
	
	/**
	 * enables real-time receiving for this {@link Microservice}
	 * 
	 */
	void enableRealTimeReceiving() {
		if (consumer == null || consumer.isClosed())
			return;
		MessageCallback messageCallback = isLocal
				? new MessageCallback(name)
				: new RemoteMessageCallback(name, connectorClient);
		listener = new MessageListener(messageCallback);
		try {
			consumer.setMessageHandler(listener);
		} catch (Exception e) {
			listener = null;
		}
	}
	
	/**
	 * disables real-time receiving for this {@link Microservice}
	 * 
	 */
	void disableRealTimeReceiving() {
		try {
			if (consumer == null || listener == null || consumer.getMessageHandler() == null)
				return;
			if (!isLocal) {
				connectorClient.closeProducer();
			}
			listener = null;
			consumer.setMessageHandler(null);
		} catch (Exception exp) {
			logWarning(MODULE_NAME, exp.getMessage());
		}
	}
	
	public void close() {
		if (consumer == null)
			return;
		disableRealTimeReceiving();
		try {
			consumer.close();
		} catch (Exception exp) {
			logWarning(MODULE_NAME, exp.getMessage());
		}
	}
}
