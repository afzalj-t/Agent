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
package org.eclipse.iofog.microservice;

import org.eclipse.iofog.connector_client.ClientConfig;

/**
 * represents microservice producing messages
 * @author epankou
 */
public class Producer {
	private String microserviceId;
	private boolean isLocal;
	private ClientConfig connectorConsumerConfig;

	public Producer(String microserviceId, boolean isLocal, ClientConfig connectorConsumerConfig) {
		this.microserviceId = microserviceId;
		this.isLocal = isLocal;
		this.connectorConsumerConfig = connectorConsumerConfig;
	}

	public String getMicroserviceId() {
		return microserviceId;
	}

	public void setMicroserviceId(String microserviceId) {
		this.microserviceId = microserviceId;
	}

	public boolean isLocal() {
		return isLocal;
	}

	public void setLocal(boolean local) {
		isLocal = local;
	}

	public ClientConfig getConnectorConsumerConfig() {
		return connectorConsumerConfig;
	}

	public void setConnectorConsumerConfig(ClientConfig connectorConsumerConfig) {
		this.connectorConsumerConfig = connectorConsumerConfig;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		Producer producer = (Producer) o;

		if (isLocal != producer.isLocal) return false;
		if (!microserviceId.equals(producer.microserviceId)) return false;
		return connectorConsumerConfig != null ? connectorConsumerConfig.equals(producer.connectorConsumerConfig) : producer.connectorConsumerConfig == null;
	}

	@Override
	public int hashCode() {
		int result = microserviceId.hashCode();
		result = 31 * result + (isLocal ? 1 : 0);
		result = 31 * result + (connectorConsumerConfig != null ? connectorConsumerConfig.hashCode() : 0);
		return result;
	}
}
